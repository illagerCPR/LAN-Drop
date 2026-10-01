import { useCallback, useEffect, useState } from "react";

import type { DeviceInfoDto } from "@lan-drop/protocol";

import { ApiError, credentials, listDevices, revokeDevice } from "../api.ts";

function formatSeen(ts: number | null): string {
  if (ts === null) return "从未连接";
  return new Date(ts).toLocaleString();
}

/**
 * 设备管理面板：列出全部已配对设备，可撤销（删除即凭据立即失效 + 踢下线）。
 *
 * 撤销是共享房间模型里唯一的止损手段，入口放在宿主的 Web 控制台——
 * 服务端把 DELETE 限制在回环地址，其他已配对设备无权互相踢。
 */
export function DevicesPanel(props: { onClose: () => void }) {
  const [devices, setDevices] = useState<DeviceInfoDto[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);

  const reload = useCallback(async () => {
    try {
      const page = await listDevices();
      setDevices(page.items);
      setError(null);
    } catch (cause) {
      setError(cause instanceof ApiError ? `加载失败（HTTP ${cause.status}）` : String(cause));
    }
  }, []);

  useEffect(() => {
    void reload();
  }, [reload]);

  const revoke = async (device: DeviceInfoDto): Promise<void> => {
    const isSelf = device.id === credentials.deviceId;
    const confirmed = window.confirm(
      isSelf
        ? "撤销本机将使当前浏览器解除配对，回到配对页。确定？"
        : `撤销「${device.name}」？该设备的凭据将立即失效并被踢下线。`,
    );
    if (!confirmed) return;

    setBusyId(device.id);
    try {
      await revokeDevice(device.id);
      if (isSelf) {
        // 本机凭据已死：整页重载后 App 状态机会在 401 处清凭据、回到配对页
        window.location.reload();
        return;
      }
      await reload();
    } catch (cause) {
      setError(cause instanceof ApiError ? `撤销失败（HTTP ${cause.status}）` : String(cause));
    } finally {
      setBusyId(null);
    }
  };

  return (
    // 点遮罩关闭；stopPropagation 防止点面板内部冒泡误关
    <div className="devices-overlay" role="presentation" onClick={props.onClose}>
      <section
        className="devices-panel"
        role="dialog"
        aria-modal="true"
        aria-label="设备管理"
        onClick={(event) => event.stopPropagation()}
      >
        <header className="devices-header">
          <h2>设备管理</h2>
          <button type="button" className="ghost" onClick={props.onClose}>
            关闭
          </button>
        </header>

        {error !== null && <p className="error-text">{error}</p>}

        {devices === null ? (
          <p className="dim">加载中…</p>
        ) : (
          <ul className="device-list">
            {devices.map((device) => {
              const isSelf = device.id === credentials.deviceId;
              return (
                <li key={device.id} className="device-row">
                  <div className="device-info">
                    <span className="device-name">
                      {device.name}
                      {isSelf ? "（本机）" : ""}
                    </span>
                    <span className="device-meta dim">
                      {device.platform} · {device.online ? "在线" : `最近活跃 ${formatSeen(device.lastSeenAt)}`}
                    </span>
                  </div>
                  <button
                    type="button"
                    className="danger"
                    disabled={busyId !== null}
                    onClick={() => void revoke(device)}
                  >
                    {busyId === device.id ? "撤销中…" : "撤销"}
                  </button>
                </li>
              );
            })}
          </ul>
        )}

        <p className="devices-hint dim">撤销后该设备需重新配对才能收发；历史消息保留。</p>
      </section>
    </div>
  );
}
