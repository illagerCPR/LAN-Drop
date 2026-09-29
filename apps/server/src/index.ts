import { join } from "node:path";

import { createApp } from "./app.ts";
import { PairingManager } from "./auth.ts";
import { loadConfig } from "./config.ts";
import type { AppContext } from "./context.ts";
import { Hub } from "./hub.ts";
import { loadOrCreateIdentity } from "./identity.ts";
import { lanAddresses } from "./net.ts";
import { removeFileQuietly } from "./storage.ts";
import { Store } from "./store.ts";

/** 超时未完成的上传，其 .part 临时文件在超过该时长后被回收。 */
const STALE_UPLOAD_MS = 24 * 60 * 60 * 1000;
const CLEANUP_INTERVAL_MS = 60 * 60 * 1000;

async function main(): Promise<void> {
  const config = loadConfig();

  const store = new Store(join(config.dataRoot, "lan-drop.sqlite"));
  const identity = await loadOrCreateIdentity(config.dataRoot);
  const hub = new Hub();
  const pairing = new PairingManager();

  const ctx: AppContext = {
    config,
    store,
    hub,
    pairing,
    serverId: identity.serverId,
  };

  const app = createApp(ctx);
  await app.listen({ host: config.host, port: config.port });

  const addresses = lanAddresses();
  const { code, expiresAt } = pairing.current();
  const minutes = Math.max(1, Math.round((expiresAt - Date.now()) / 60000));

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
    `  本机访问   http://localhost:${config.port}   （本机免配对，直接可用）`,
  ];

  for (const ip of addresses) {
    lines.push(`  手机访问   http://${ip}:${config.port}`);
  }

  lines.push(
    "",
    `  配对码     ${code}   （${minutes} 分钟内有效，用一次即失效）`,
    "",
    "  手机浏览器打开上面的「手机访问」地址，输入配对码即可开始收发。",
    "",
  );

  app.log.info(lines.join("\n"));

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
    })();
  }, CLEANUP_INTERVAL_MS);
  cleanupTimer.unref();

  let shuttingDown = false;
  const shutdown = async (signal: string): Promise<void> => {
    if (shuttingDown) return;
    shuttingDown = true;
    app.log.info({ signal }, "正在关闭…");
    clearInterval(cleanupTimer);
    try {
      await app.close();
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
