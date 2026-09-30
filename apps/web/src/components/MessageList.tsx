import { isLinkText, type MessageDto } from "@lan-drop/protocol";
import type { ReactElement } from "react";

import { formatDayLabel, formatTime } from "../util.ts";
import { FileCard } from "./FileCard.tsx";

/**
 * 消息时间线：按 seq 升序渲染，跨天插入日期分隔条。
 * 自己发的消息靠右，其他设备的靠左并展示发送者名称。
 */
export function MessageList(props: { messages: readonly MessageDto[]; ownDeviceId: string | null }) {
  const { messages, ownDeviceId } = props;

  const nodes: ReactElement[] = [];
  let lastDay = "";

  for (const message of messages) {
    const day = formatDayLabel(message.createdAt);
    if (day !== lastDay) {
      lastDay = day;
      nodes.push(
        <li key={`day-${message.seq}`} className="day-divider">
          <span>{day}</span>
        </li>,
      );
    }

    const own = ownDeviceId !== null && message.senderId === ownDeviceId;
    const text = message.text ?? "";
    // kind 由发送方自填，服务端不做语义校验；只有整段就是 http(s) 链接时才渲染成锚点，
    // 别的一律当纯文本——否则 `javascript:` 这类伪协议会被塞进 href
    const link = message.kind === "link" && isLinkText(text);

    nodes.push(
      <li key={message.seq} className={own ? "msg own" : "msg"}>
        {!own && <div className="msg-sender">{message.senderName}</div>}
        <div className="msg-body">
          {message.kind === "file" && message.file !== undefined ? (
            <FileCard file={message.file} />
          ) : null}
          {link ? (
            <a className="msg-link" href={text} target="_blank" rel="noreferrer">
              {text}
            </a>
          ) : null}
          {!link && message.kind !== "file" && text.length > 0 ? (
            <div className="msg-text">{text}</div>
          ) : null}
        </div>
        <div className="msg-time">{formatTime(message.createdAt)}</div>
      </li>,
    );
  }

  if (nodes.length === 0) {
    return (
      <ul className="message-list-inner empty-hint">
        <li className="empty">还没有任何内容 —— 发送一段文字，或把文件拖进来试试</li>
      </ul>
    );
  }

  return <ul className="message-list-inner">{nodes}</ul>;
}
