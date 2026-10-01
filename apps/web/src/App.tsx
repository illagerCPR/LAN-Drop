import { useCallback, useEffect, useState } from "react";

import type { PairResponse, ServerInfoDto } from "@lan-drop/protocol";

import {
  ApiError,
  credentials,
  getInfo,
  listMessages,
  pairWithCode,
} from "./api.ts";
import { ChatScreen } from "./components/ChatScreen.tsx";
import { PairingScreen } from "./components/PairingScreen.tsx";
import { clearHash, readPairCodeFromHash, describeBrowser } from "./util.ts";

type Phase = "loading" | "pairing" | "chat" | "error";

/**
 * 应用入口状态机：
 *
 *   loading → 读 /info → hash 里带配对码？ ─ 是 → 直接配对 → chat
 *                                └─ 否 → 本地有有效凭据？ ─ 是 → chat
 *                                                        └─ 否 → pairing
 *
 * 「本地凭据有效」通过一次最小化鉴权请求（limit=1 的增量拉取）验证，
 * 401 时清掉凭据回到配对页，其他错误（如网络不通）则进入错误页。
 */
export function App() {
  const [phase, setPhase] = useState<Phase>("loading");
  const [info, setInfo] = useState<ServerInfoDto | null>(null);
  const [errorText, setErrorText] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;

    void (async () => {
      try {
        const serverInfo = await getInfo();
        if (cancelled) return;
        setInfo(serverInfo);

        // 手机扫码进入：hash 直接带着一次性配对码
        const pairCode = readPairCodeFromHash();
        if (pairCode !== null) {
          const pairing = await pairWithCode(pairCode, describeBrowser());
          if (cancelled) return;
          credentials.save(pairing);
          clearHash();
          setPhase("chat");
          return;
        }

        // 已有凭据且服务器没换过：用一次最小请求校验令牌是否仍有效
        if (credentials.token !== null && credentials.serverId === serverInfo.serverId) {
          try {
            await listMessages(0, 1);
            if (!cancelled) setPhase("chat");
            return;
          } catch (cause) {
            if (cause instanceof ApiError && cause.status === 401) {
              credentials.clear();
            } else {
              throw cause;
            }
          }
        }

        if (!cancelled) setPhase("pairing");
      } catch (cause) {
        if (!cancelled) {
          setErrorText(cause instanceof Error ? cause.message : String(cause));
          setPhase("error");
        }
      }
    })();

    return () => {
      cancelled = true;
    };
  }, []);

  const handlePaired = useCallback((pairing: PairResponse) => {
    credentials.save(pairing);
    clearHash();
    setPhase("chat");
  }, []);

  if (phase === "loading") {
    return (
      <div className="center-screen" role="status">
        <div className="spinner" aria-hidden="true" />
        <p className="dim">正在连接服务器…</p>
      </div>
    );
  }

  if (phase === "error") {
    return (
      <div className="center-screen">
        <div className="card error-card">
          <h1>连接失败</h1>
          <p className="dim">{errorText}</p>
          <button type="button" className="primary" onClick={() => location.reload()}>
            重试
          </button>
        </div>
      </div>
    );
  }

  if (phase === "pairing" || info === null) {
    return info === null ? (
      <div className="center-screen">
        <div className="spinner" aria-hidden="true" />
      </div>
    ) : (
      <PairingScreen info={info} onPaired={handlePaired} />
    );
  }

  return <ChatScreen info={info} />;
}
