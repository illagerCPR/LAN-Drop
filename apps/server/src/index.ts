import { join } from "node:path";

import type { FastifyInstance } from "fastify";
import { WsEventType } from "@lan-drop/protocol";

import { createApp } from "./app.ts";
import { PairingManager } from "./auth.ts";
import { loadConfig } from "./config.ts";
import type { AppContext } from "./context.ts";
import { DiscoveryService } from "./discovery.ts";
import { Hub } from "./hub.ts";
import { loadOrCreateIdentity } from "./identity.ts";
import { lanAddresses } from "./net.ts";
import { removeFileQuietly } from "./storage.ts";
import { loadOrCreateTlsMaterial, type TlsLogger } from "./tls.ts";
import { Store } from "./store.ts";

/** 超时未完成的上传，其 .part 临时文件在超过该时长后被回收。 */
const STALE_UPLOAD_MS = 24 * 60 * 60 * 1000;

/** Fastify 起来之前的引导日志：此刻还没有 app 实例可借用。 */
const bootstrapLog: TlsLogger = {
  info: (obj, msg) => console.log(`[lan-drop] ${msg ?? ""} ${JSON.stringify(obj)}`),
  warn: (obj, msg) => console.warn(`[lan-drop] ${msg ?? ""} ${JSON.stringify(obj)}`),
};

async function main(): Promise<void> {
  const config = loadConfig();

  const store = new Store(join(config.dataRoot, "lan-drop.sqlite"));
  const identity = await loadOrCreateIdentity(config.dataRoot);
  const hub = new Hub();
  const pairing = new PairingManager();

  // TLS 材料（默认开启）：生成/载入失败就让启动失败，把「要不要明文」留给
  // 显式的 LAN_DROP_TLS=0，绝不静默退回——静默降级正是安全功能最坏的失败方式。
  let tlsFingerprint: string | null = null;
  let httpsOptions: { key: string; cert: string } | undefined;
  if (config.tlsEnabled) {
    const material = await loadOrCreateTlsMaterial(config.dataRoot, bootstrapLog);
    tlsFingerprint = material.fingerprintUrlSafe;
    httpsOptions = { key: material.keyPem, cert: material.certPem };
  }

  const ctx: AppContext = {
    config,
    store,
    hub,
    pairing,
    serverId: identity.serverId,
    tlsFingerprint,
  };

  const app = createApp(ctx, httpsOptions ? { https: httpsOptions } : {});
  await app.listen({ host: config.host, port: config.port });

  // 回环明文监听器（仅 TLS 开启时有）：LAN 端口是自签证书，本机浏览器访问要吃
  // 证书警告；控制台与配对码接口走 127.0.0.1 明文端口，宿主机体验不受影响，
  // 局域网侧流量照常全程加密。
  let loopbackApp: FastifyInstance | null = null;
  if (httpsOptions) {
    loopbackApp = createApp(ctx);
    await loopbackApp.listen({ host: "127.0.0.1", port: config.loopbackPort });
  }

  // UDP 自动发现：绑定失败只降级（日志说明），不影响 HTTP 服务
  const discovery = new DiscoveryService(config, identity.serverId, tlsFingerprint !== null);
  discovery.start(app.log);

  const addresses = lanAddresses();
  const { code, expiresAt } = pairing.current();
  const minutes = Math.max(1, Math.round((expiresAt - Date.now()) / 60000));

  const lanScheme = tlsFingerprint !== null ? "https" : "http";
  const lines: string[] = [
    "",
    "  ┌────────────────────────────────────────────────┐",
    "  │  LAN-Drop 服务端已启动                          │",
    "  └────────────────────────────────────────────────┘",
    "",
    `  数据目录   ${config.dataRoot}`,
    `  文件仓库   ${config.filesRoot}`,
    `  服务端 ID  ${identity.serverId.slice(0, 8)}…`,
    "",
  ];

  if (tlsFingerprint !== null) {
    lines.push(
      `  本机访问   http://localhost:${config.loopbackPort}   （回环明文，控制台/配对码免证书警告）`,
      `  TLS        已启用，SPKI 指纹 ${tlsFingerprint}`,
      `             （随配对二维码下发，客户端固定校验；证书轮换 = 全部设备重新配对）`,
    );
  } else {
    lines.push(`  本机访问   http://localhost:${config.port}   （本机免配对，直接可用）`);
  }

  for (const ip of addresses) {
    lines.push(`  手机访问   ${lanScheme}://${ip}:${config.port}`);
  }

  if (config.discoveryEnabled) {
    lines.push(`  自动发现   UDP ${config.discoveryPort} 端口（手机端点「扫描局域网」即可找到本服务端）`);
  }

  lines.push(
    "",
    `  配对码     ${code}   （${minutes} 分钟内有效，用一次即失效）`,
    "",
    "  手机浏览器打开上面的「手机访问」地址，输入配对码即可开始收发。",
    "",
  );

  app.log.info(lines.join("\n"));

  // P4-5 消息保留策略：天数 + 条数双阈值（env 配置，0 = 关闭）
  const retentionOlderThanMs =
    config.retentionDays > 0 ? config.retentionDays * 24 * 60 * 60 * 1000 : null;
  const retentionKeepCount = config.retentionMaxMessages > 0 ? config.retentionMaxMessages : null;

  /** 执行一轮保留清理；删了消息就广播 `messages.purged`，让在线客户端同步删本地缓存。 */
  const purgeRetention = async (): Promise<void> => {
    if (retentionOlderThanMs === null && retentionKeepCount === null) return;

    const result = store.purgeExpiredMessages({
      olderThanMs: retentionOlderThanMs,
      keepCount: retentionKeepCount,
    });
    if (result.purged === 0) return;

    for (const file of result.files) {
      await removeFileQuietly(join(config.filesRoot, file.relPath));
    }

    app.log.info(
      { purged: result.purged, uptoSeq: result.uptoSeq, files: result.files.length },
      "已按保留策略清除旧消息",
    );
    ctx.hub.broadcast({
      type: WsEventType.messagesPurged,
      payload: { uptoSeq: result.uptoSeq },
    });
  };

  // 启动即清一次（否则两次运行间隔内过期消息要等满一个小时才被清）
  try {
    await purgeRetention();
  } catch (error) {
    app.log.warn({ err: error }, "启动时执行保留清理失败");
  }

  // 定期回收中断的上传：客户端可能中途退出，留下永不完成的会话与 .part 文件
  const cleanupTimer = setInterval(() => {
    void (async () => {
      try {
        const stale = store.listStaleUploads(STALE_UPLOAD_MS);
        for (const upload of stale) {
          store.setUploadState(upload.id, "aborted");
          await removeFileQuietly(upload.tempPath);
        }
        if (stale.length > 0) {
          app.log.info({ count: stale.length }, "已回收超时未完成的上传");
        }
      } catch (error) {
        app.log.warn({ err: error }, "回收超时上传时出错");
      }

      try {
        await purgeRetention();
      } catch (error) {
        app.log.warn({ err: error }, "执行保留清理时出错");
      }
    })();
  }, config.cleanupIntervalMs);
  cleanupTimer.unref();

  let shuttingDown = false;
  const shutdown = async (signal: string): Promise<void> => {
    if (shuttingDown) return;
    shuttingDown = true;
    app.log.info({ signal }, "正在关闭…");
    clearInterval(cleanupTimer);
    try {
      discovery.stop();
      await app.close();
      // 两个实例的 onClose 都会关 store（幂等），关两次无害
      await loopbackApp?.close();
    } finally {
      process.exit(0);
    }
  };

  for (const signal of ["SIGINT", "SIGTERM"] as const) {
    process.on(signal, () => {
      void shutdown(signal);
    });
  }
}

main().catch((error: unknown) => {
  console.error("[lan-drop] 启动失败：", error);
  process.exitCode = 1;
});
