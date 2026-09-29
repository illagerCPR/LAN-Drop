import { QRCodeSVG } from "qrcode.react";
import { useCallback, useEffect, useState } from "react";

import type { PairResponse, ServerInfoDto } from "@lan-drop/protocol";

import { ApiError, getPairingCode, pairLocal, pairWithCode, type PairingCodeInfo } from "../api.ts";
import { describeBrowser } from "../util.ts";

type Mode = "checking" | "host" | "guest";

/**
 * 配对页。
 *
 * - 本机（回环）打开：读得到配对码，展示二维码给手机扫，同时提供「一键配对」；
 * - 远端（手机/其他设备）打开：读不到配对码（403），只能走
 *   ① 扫码进入（hash 已带码，App.tsx 已处理，不会到这）或 ② 手输配对码。
 */
export function PairingScreen(props: { info: ServerInfoDto; onPaired: (pairing: PairResponse) => void }) {
  const { info, onPaired } = props;

  const [mode, setMode] = useState<Mode>("checking");
  const [codeInfo, setCodeInfo] = useState<PairingCodeInfo | null>(null);
  const [manualCode, setManualCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [errorText, setErrorText] = useState<string | null>(null);
  const [now, setNow] = useState(() => Date.now());

  // 判断本端是不是宿主：读得到配对码即为回环访问
  useEffect(() => {
    let cancelled = false;
    void getPairingCode()
      .then((result) => {
        if (cancelled) return;
        setCodeInfo(result);
        setMode("host");
      })
      .catch((cause: unknown) => {
        if (cancelled) return;
        if (cause instanceof ApiError && cause.status === 403) {
          setMode("guest");
        } else {
          setErrorText(cause instanceof Error ? cause.message : String(cause));
          setMode("guest");
        }
      });
    return () => {
      cancelled = true;
    };
  }, []);

  // 配对码倒计时
  useEffect(() => {
    if (mode !== "host") return;
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [mode]);

  // 配对码过期后自动刷新
  useEffect(() => {
    if (mode !== "host" || codeInfo === null) return;
    if (now < codeInfo.expiresAt) return;
    let cancelled = false;
    void getPairingCode()
      .then((result) => {
        if (!cancelled) setCodeInfo(result);
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [mode, codeInfo, now]);

  const pair = useCallback(
    async (action: () => Promise<PairResponse>) => {
      setBusy(true);
      setErrorText(null);
      try {
        const pairing = await action();
        onPaired(pairing);
      } catch (cause) {
        if (cause instanceof ApiError && cause.code === "invalid_pairing_code") {
          setErrorText("配对码无效或已过期，请在电脑端查看最新配对码");
        } else {
          setErrorText(cause instanceof Error ? cause.message : String(cause));
        }
      } finally {
        setBusy(false);
      }
    },
    [onPaired],
  );

  const remainingSeconds =
    codeInfo !== null ? Math.max(0, Math.ceil((codeInfo.expiresAt - now) / 1000)) : 0;

  return (
    <div className="center-screen">
      <div className="card pairing-card">
        <h1>
          <span className="logo" aria-hidden="true">
            📤
          </span>
          LAN-Drop
        </h1>
        <p className="dim subtitle">
          服务器「{info.serverName}」需要配对后才能收发内容
        </p>

        {mode === "checking" && (
          <div className="spinner" aria-hidden="true" />
        )}

        {mode === "host" && codeInfo !== null && (
          <div className="pair-host">
            <p className="qr-hint">用手机浏览器扫码，自动完成配对</p>
            <div className="qr-box">
              <QRCodeSVG
                value={codeInfo.urls[0] ?? ""}
                size={208}
                marginSize={1}
                bgColor="#ffffff"
                fgColor="#0b1220"
              />
            </div>
            <p className="pair-url dim">{codeInfo.urls[0]}</p>
            <div className="code-row">
              <span className="pair-code">{codeInfo.code}</span>
              <span className="dim">有效 {Math.floor(remainingSeconds / 60)}:{String(remainingSeconds % 60).padStart(2, "0")}</span>
            </div>
            <button
              type="button"
              className="primary"
              disabled={busy}
              onClick={() => void pair(() => pairLocal(describeBrowser()))}
            >
              本机一键配对
            </button>
          </div>
        )}

        {mode === "guest" && (
          <div className="pair-guest">
            <p>在电脑端屏幕上找到配对码，输入到这里：</p>
            <form
              onSubmit={(event) => {
                event.preventDefault();
                const code = manualCode.trim();
                if (code.length === 0 || busy) return;
                void pair(() => pairWithCode(code, describeBrowser()));
              }}
            >
              <input
                className="code-input"
                value={manualCode}
                onChange={(event) => setManualCode(event.target.value)}
                placeholder="例如 7F3K-9QPD"
                autoComplete="off"
                autoCapitalize="characters"
                spellCheck={false}
                autoFocus
              />
              <button type="submit" className="primary" disabled={busy || manualCode.trim().length === 0}>
                {busy ? "配对中…" : "配对"}
              </button>
            </form>
          </div>
        )}

        {errorText !== null && <p className="error-text">{errorText}</p>}
      </div>
    </div>
  );
}
