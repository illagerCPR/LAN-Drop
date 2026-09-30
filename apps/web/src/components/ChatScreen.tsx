import { useCallback, useEffect, useRef, useState } from "react";
import type { UIEvent } from "react";

import {
  ApiPath,
  WsEventType,
  isLinkText,
  type DevicePresencePayload,
  type MessageDto,
  type MessagesPurgedPayload,
  type ServerInfoDto,
  type TypingPayload,
  type WsEnvelopeDto,
  type WsHelloPayload,
} from "@lan-drop/protocol";

import {
  ApiError,
  clearMessages,
  credentials,
  listMessages,
  sendText,
  uploadTransport,
} from "../api.ts";
import { Uploader, type UploadPhase, type UploadSnapshot } from "../upload.ts";
import { LanDropSocket, type WsStatus } from "../ws.ts";
import { MessageList } from "./MessageList.tsx";

const PAGE_SIZE = 1000;
const TYPING_THROTTLE_MS = 2000;
const TYPING_DISPLAY_MS = 4000;
/** 上传成功后让「完成」在面板上停留一下再消失 */
const DONE_LINGER_MS = 2500;

/** 传输阶段的中文说明；百分比由进度条旁边的数字单独给。 */
const PHASE_LABEL: Record<UploadPhase, string> = {
  queued: "排队中",
  running: "上传中",
  pausing: "暂停中…",
  paused: "已暂停",
  failed: "失败",
  done: "完成",
};

/**
 * 聊天主界面。
 *
 * 数据流：
 *   - 进场：从 seq=0 全量分页拉取历史（协议约定），WS 并行建立；
 *   - 实时：WS `message.new` 增量追加，`hello.latestSeq` 驱动缺口补拉；
 *   - 发送：文字走 POST，文件交给 [Uploader]（建会话 → 分片追加 → 完成，
 *     支持暂停/继续/取消，进度全部以服务端确认的字节数为准）；
 *   - 全程以服务端 seq 为唯一去重/排序依据，重复投递无副作用。
 */
export function ChatScreen(props: { info: ServerInfoDto }) {
  const { info } = props;

  const [messages, setMessages] = useState<MessageDto[]>([]);
  const [wsStatus, setWsStatus] = useState<WsStatus>("connecting");
  const [onlineCount, setOnlineCount] = useState(0);
  const [transfers, setTransfers] = useState<UploadSnapshot[]>([]);
  const [dragging, setDragging] = useState(false);
  const [draft, setDraft] = useState("");
  const [sending, setSending] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [typingName, setTypingName] = useState<string | null>(null);

  const seqRef = useRef(0);
  const socketRef = useRef<LanDropSocket | null>(null);
  const listRef = useRef<HTMLDivElement | null>(null);
  const stickToBottomRef = useRef(true);
  const dragDepthRef = useRef(0);
  const typingTimerRef = useRef<number | null>(null);
  const lastTypingSentRef = useRef(0);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const dismissedRef = useRef(new Set<string>());

  // ---------------------------------------------------------------- 消息合并

  /** 合并进本地列表：按 seq 去重排序，并推进本地游标。 */
  const appendMessages = useCallback((incoming: readonly MessageDto[]) => {
    if (incoming.length === 0) return;
    setMessages((prev) => {
      const bySeq = new Map<number, MessageDto>();
      for (const message of prev) bySeq.set(message.seq, message);
      for (const message of incoming) bySeq.set(message.seq, message);
      return [...bySeq.values()].sort((a, b) => a.seq - b.seq);
    });
    for (const message of incoming) {
      if (message.seq > seqRef.current) seqRef.current = message.seq;
    }
  }, []);

  // ---------------------------------------------------------------- 上传队列

  /**
   * 上传控制器只建一次。
   *
   * 它持有每个任务的 `uploadId` 与服务端确认的进度，重建等于把续传锚点丢掉——
   * 所以不能随渲染重建，也不能放在会被重挂的组件里。
   */
  const [uploader] = useState(
    () =>
      new Uploader({
        transport: uploadTransport,
        onChange: setTransfers,
        onComplete: (message) => appendMessages([message]),
      }),
  );

  const reportError = useCallback((cause: unknown) => {
    setActionError(cause instanceof Error ? cause.message : String(cause));
  }, []);

  /** 把本地游标之后的消息全部补齐（自动翻页直到追上服务端水位）。 */
  const loadMissing = useCallback(async () => {
    for (;;) {
      const page = await listMessages(seqRef.current, PAGE_SIZE);
      // 补上离线期间错过的保留清理：服务端删到哪，本地缓存同步删到哪（幂等）
      if (page.purgedUpto > 0) {
        setMessages((prev) => prev.filter((message) => message.seq > page.purgedUpto));
      }
      appendMessages(page.items);

      const lastItem = page.items[page.items.length - 1];
      if (page.hasMore && lastItem !== undefined) {
        seqRef.current = Math.max(seqRef.current, lastItem.seq);
        continue;
      }
      // 追上水位：用服务端权威 latestSeq 校准游标（防止本地游标超前或落后）
      seqRef.current = Math.max(seqRef.current, page.latestSeq);
      return;
    }
  }, [appendMessages]);

  // ---------------------------------------------------------------- 首次加载

  useEffect(() => {
    void loadMissing().catch(reportError);
  }, [loadMissing, reportError]);

  // ---------------------------------------------------------------- WebSocket

  useEffect(() => {
    const protocol = location.protocol === "https:" ? "wss:" : "ws:";
    const url = `${protocol}//${location.host}${ApiPath.ws}?token=${encodeURIComponent(credentials.token ?? "")}`;

    const socket = new LanDropSocket(url, {
      onStatus: setWsStatus,
      onEvent: (event: WsEnvelopeDto) => {
        switch (event.type) {
          case WsEventType.hello: {
            const payload = event.payload as WsHelloPayload | undefined;
            if (payload === undefined) break;
            setOnlineCount(payload.onlineCount);
            if (payload.latestSeq > seqRef.current) {
              void loadMissing().catch(reportError);
            }
            break;
          }

          case WsEventType.messageNew: {
            const payload = event.payload as MessageDto | undefined;
            if (payload !== undefined) appendMessages([payload]);
            break;
          }

          case WsEventType.messageDeleted: {
            // 服务端记录被清空（本机操作触发），本地一并清掉并重新校准游标
            setMessages([]);
            seqRef.current = 0;
            void loadMissing().catch(reportError);
            break;
          }

          case WsEventType.messagesPurged: {
            // 保留策略删掉了 seq <= uptoSeq 的旧消息；这是前缀删除，游标不用回退
            const purged = event.payload as MessagesPurgedPayload | undefined;
            if (purged === undefined) break;
            setMessages((prev) => prev.filter((message) => message.seq > purged.uptoSeq));
            break;
          }

          case WsEventType.deviceOnline:
          case WsEventType.deviceOffline: {
            const payload = event.payload as DevicePresencePayload | undefined;
            if (payload?.onlineCount !== undefined) setOnlineCount(payload.onlineCount);
            break;
          }

          case WsEventType.typing: {
            const payload = event.payload as TypingPayload | undefined;
            if (payload === undefined || payload.deviceId === credentials.deviceId) break;
            setTypingName(payload.deviceName);
            if (typingTimerRef.current !== null) window.clearTimeout(typingTimerRef.current);
            typingTimerRef.current = window.setTimeout(() => {
              typingTimerRef.current = null;
              setTypingName(null);
            }, TYPING_DISPLAY_MS);
            break;
          }

          default:
            break;
        }
      },
    });

    socketRef.current = socket;
    socket.connect();

    return () => {
      socket.close();
      socketRef.current = null;
      if (typingTimerRef.current !== null) {
        window.clearTimeout(typingTimerRef.current);
        typingTimerRef.current = null;
      }
    };
  }, [appendMessages, loadMissing, reportError]);

  // ---------------------------------------------------------------- 滚动

  useEffect(() => {
    const list = listRef.current;
    if (list !== null && stickToBottomRef.current) {
      list.scrollTop = list.scrollHeight;
    }
  }, [messages.length, transfers.length]);

  const handleListScroll = useCallback((event: UIEvent<HTMLDivElement>) => {
    const el = event.currentTarget;
    stickToBottomRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < 80;
  }, []);

  // ---------------------------------------------------------------- 发送文字

  const submit = useCallback(async () => {
    const text = draft.trim();
    if (text.length === 0 || sending) return;
    setSending(true);
    setActionError(null);
    try {
      // 整段就是一个链接时按 link 类型发，两端都会渲染成可点击的锚点
      const message = await sendText(isLinkText(text) ? "link" : "text", text);
      appendMessages([message]);
      setDraft("");
    } catch (cause) {
      reportError(cause);
    } finally {
      setSending(false);
    }
  }, [appendMessages, draft, reportError, sending]);

  const onDraftChange = useCallback((value: string) => {
    setDraft(value);
    const now = Date.now();
    if (value.length > 0 && now - lastTypingSentRef.current > TYPING_THROTTLE_MS) {
      lastTypingSentRef.current = now;
      socketRef.current?.sendEvent(WsEventType.typing);
    }
  }, []);

  // ---------------------------------------------------------------- 发送文件

  const handleFiles = useCallback(
    (files: FileList | null) => {
      if (files === null || files.length === 0) return;
      // 一次性全部入队：控制器内部串行执行（避免多个大文件并发抢占局域网带宽），
      // 排队中的任务会立刻出现在面板里，用户能看到「还有几个没发」。
      for (const file of Array.from(files)) uploader.add(file);
    },
    [uploader],
  );

  // 完成的任务在面板上停留一会儿再消失，让人看清「传完了」而不是闪一下没了。
  // 失败的任务留着不动：它带着「重试」「移除」两个按钮，得等用户处理。
  useEffect(() => {
    for (const transfer of transfers) {
      if (transfer.phase !== "done" || dismissedRef.current.has(transfer.id)) continue;
      dismissedRef.current.add(transfer.id);
      window.setTimeout(() => uploader.dismiss(transfer.id), DONE_LINGER_MS);
    }
  }, [transfers, uploader]);

  // ---------------------------------------------------------------- 清空记录

  const handleClear = useCallback(async () => {
    const confirmed = window.confirm(
      "确定清空服务端上的全部聊天与传输记录？\n已保存到各自设备上的记录与已下载的文件不受影响。",
    );
    if (!confirmed) return;
    try {
      await clearMessages();
      setMessages([]);
      seqRef.current = 0;
      void loadMissing().catch(reportError);
    } catch (cause) {
      if (cause instanceof ApiError && cause.status === 403) {
        setActionError("只有在本机（服务端自己）打开的页面才能清空记录");
      } else {
        reportError(cause);
      }
    }
  }, [loadMissing, reportError]);

  // ---------------------------------------------------------------- 渲染

  const statusText =
    wsStatus === "open" ? "已连接" : wsStatus === "connecting" ? "连接中…" : "已断开，正在重连";

  return (
    <div
      className="chat"
      onDragEnter={(event) => {
        event.preventDefault();
        dragDepthRef.current += 1;
        setDragging(true);
      }}
      onDragOver={(event) => event.preventDefault()}
      onDragLeave={() => {
        dragDepthRef.current = Math.max(0, dragDepthRef.current - 1);
        if (dragDepthRef.current === 0) setDragging(false);
      }}
      onDrop={(event) => {
        event.preventDefault();
        dragDepthRef.current = 0;
        setDragging(false);
        handleFiles(event.dataTransfer.files);
      }}
    >
      <header className="chat-header">
        <div className="chat-title">
          <span className="logo" aria-hidden="true">
            📤
          </span>
          <div>
            <div className="chat-server">{info.serverName}</div>
            <div className={`chat-status ${wsStatus}`}>
              <span className="status-dot" aria-hidden="true" />
              {statusText} · 在线 {onlineCount} 台设备
            </div>
          </div>
        </div>
        <button type="button" className="ghost" onClick={() => void handleClear()}>
          清空记录
        </button>
      </header>

      <div className="message-list" ref={listRef} onScroll={handleListScroll}>
        <MessageList messages={messages} ownDeviceId={credentials.deviceId} />
      </div>

      {typingName !== null && <div className="typing-hint">{typingName} 正在输入…</div>}

      {transfers.length > 0 && (
        <div className="transfer-panel">
          {transfers.map((transfer) => (
            <TransferRow
              key={transfer.id}
              transfer={transfer}
              onPause={() => uploader.pause(transfer.id)}
              onResume={() => uploader.resume(transfer.id)}
              onRetry={() => uploader.retry(transfer.id)}
              onCancel={() => uploader.cancel(transfer.id)}
            />
          ))}
        </div>
      )}

      {actionError !== null && (
        <div className="action-error">
          <span>{actionError}</span>
          <button type="button" className="ghost" onClick={() => setActionError(null)}>
            关闭
          </button>
        </div>
      )}

      <footer className="composer">
        <input
          ref={fileInputRef}
          type="file"
          multiple
          hidden
          onChange={(event) => {
            handleFiles(event.target.files);
            event.target.value = "";
          }}
        />
        <button
          type="button"
          className="icon-btn"
          title="选择文件发送"
          onClick={() => fileInputRef.current?.click()}
        >
          📎
        </button>
        <textarea
          className="composer-input"
          value={draft}
          rows={1}
          placeholder="输入文字，Enter 发送；文件可直接拖进窗口或粘贴"
          onChange={(event) => onDraftChange(event.target.value)}
          onKeyDown={(event) => {
            // isComposing：中文输入法组词期间的 Enter 不应触发发送
            if (event.key === "Enter" && !event.shiftKey && !event.nativeEvent.isComposing) {
              event.preventDefault();
              void submit();
            }
          }}
          onPaste={(event) => {
            if (event.clipboardData.files.length > 0) {
              event.preventDefault();
              handleFiles(event.clipboardData.files);
            }
          }}
        />
        <button
          type="button"
          className="primary send-btn"
          disabled={draft.trim().length === 0 || sending}
          onClick={() => void submit()}
        >
          {sending ? "发送中…" : "发送"}
        </button>
      </footer>

      {dragging && (
        <div className="drop-overlay">
          <div className="drop-hint">松开即可发送文件</div>
        </div>
      )}
    </div>
  );
}

/**
 * 上传面板里的一行。
 *
 * 百分号显示的是**服务端已确认落盘**的字节数，不是「已经发出去多少」——
 * 断点续传的锚点就是这个数，界面必须与它一致，否则暂停时看到的数字会对不上账。
 */
function TransferRow(props: {
  transfer: UploadSnapshot;
  onPause: () => void;
  onResume: () => void;
  onRetry: () => void;
  onCancel: () => void;
}) {
  const { transfer, onPause, onResume, onRetry, onCancel } = props;
  const ratio = transfer.size > 0 ? Math.min(1, transfer.offset / transfer.size) : 1;
  const percent = Math.round(ratio * 100);

  const active =
    transfer.phase === "queued" ||
    transfer.phase === "running" ||
    transfer.phase === "pausing" ||
    transfer.phase === "paused";

  return (
    <div className={`transfer ${transfer.phase}`}>
      <div className="transfer-row">
        <span className="transfer-name" title={transfer.name}>
          {transfer.name}
        </span>
        <span className="transfer-percent">
          {transfer.error !== null
            ? PHASE_LABEL.failed
            : active
              ? `${PHASE_LABEL[transfer.phase]} ${percent}%`
              : PHASE_LABEL[transfer.phase]}
        </span>
      </div>
      <div className="transfer-bar">
        <div className="transfer-fill" style={{ width: `${percent}%` }} />
      </div>
      {transfer.error !== null && <div className="transfer-error">{transfer.error}</div>}

      <div className="transfer-actions">
        {transfer.phase === "running" || transfer.phase === "queued" ? (
          <button type="button" className="ghost" onClick={onPause}>
            暂停
          </button>
        ) : null}
        {transfer.phase === "pausing" ? (
          <button type="button" className="ghost" disabled>
            暂停中…
          </button>
        ) : null}
        {transfer.phase === "paused" ? (
          <button type="button" className="ghost" onClick={onResume}>
            继续
          </button>
        ) : null}
        {transfer.phase === "failed" ? (
          <button type="button" className="ghost" onClick={onRetry}>
            重试
          </button>
        ) : null}
        {transfer.phase !== "done" ? (
          <button type="button" className="ghost" onClick={onCancel}>
            {transfer.phase === "failed" ? "移除" : "取消"}
          </button>
        ) : null}
      </div>
    </div>
  );
}
