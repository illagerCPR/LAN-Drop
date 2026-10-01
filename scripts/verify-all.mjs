#!/usr/bin/env node
/**
 * LAN-Drop 一键验证：类型检查 → 单元测试 → 自起服务端跑全量冒烟。
 *
 *   node scripts/verify-all.mjs
 *
 * 与手工三步（pnpm -r typecheck / pnpm -r test / 起服务端 + node smoke-api.mjs）
 * 等价，但服务端由本脚本自己拉起：
 *   - 端口固定 8899、发现端口 8898，绝不碰开发者可能在跑的 8787/8788；
 *   - 数据根用一次性临时目录，冒烟产生的文件与设备行不污染真实数据；
 *   - 退出前兜底杀进程、删临时目录（finally，即便冒烟失败也清理）。
 *
 * 冒烟结束后还会对服务端日志做一次「token 泄漏」扫描：任何访问日志里出现
 * 未脱敏的 `token=<长串>` 都判失败——脱敏逻辑（app.ts 的 req 序列化器）回归时
 * 在这里拦住，而不是等日志落盘后才被人发现。
 *
 * 任何一步失败立即停止并让进程以非零码退出，适合直接当 CI 门禁（`pnpm verify`）。
 */

import { spawn, spawnSync } from "node:child_process";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = fileURLToPath(new URL("..", import.meta.url));
const PORT = 8899;
const DISCOVERY_PORT = 8898;
const BASE_URL = `http://127.0.0.1:${PORT}`;

/** 是否 Windows：spawn 需要 shell 来解析 pnpm.cmd。 */
const IS_WINDOWS = process.platform === "win32";

function runStep(name, command, args) {
  console.log(`\n───── ${name} ─────`);
  const result = spawnSync(command, args, {
    cwd: ROOT,
    stdio: "inherit",
    shell: IS_WINDOWS,
    env: process.env,
  });
  if (result.status !== 0) {
    console.error(`\n✗ ${name} 失败（exit=${result.status ?? result.signal}）`);
    process.exit(1);
  }
}

/** 访问日志里出现未脱敏的 token 即视为泄漏（脱敏后的形态是 token=[REDACTED]）。 */
const TOKEN_LEAK_RE = /token=(?!\[REDACTED\])[A-Za-z0-9_-]{16,}/;

async function main() {
  console.log("LAN-Drop 一键验证");

  runStep("类型检查", "pnpm", ["-r", "typecheck"]);
  runStep("单元测试", "pnpm", ["-r", "test"]);

  // ------------------------------------------------------------ 自起服务端跑冒烟
  console.log("\n───── 端到端冒烟（自起服务端）─────");
  const dataRoot = mkdtempSync(join(tmpdir(), "lan-drop-verify-"));
  const child = spawn(
    process.execPath,
    [join(ROOT, "apps", "server", "src", "index.ts")],
    {
      cwd: ROOT,
      stdio: ["ignore", "pipe", "pipe"],
      env: {
        ...process.env,
        LAN_DROP_PORT: String(PORT),
        LAN_DROP_DISCOVERY_PORT: String(DISCOVERY_PORT),
        LAN_DROP_DISCOVERY: "0",
        LAN_DROP_DATA_ROOT: dataRoot,
        LAN_DROP_SERVER_NAME: "lan-drop-verify",
        LAN_DROP_LOG_LEVEL: "info",
      },
    },
  );

  let serverLog = "";
  child.stdout.on("data", (chunk) => {
    const text = String(chunk);
    serverLog += text;
    process.stdout.write(text);
  });
  child.stderr.on("data", (chunk) => {
    const text = String(chunk);
    serverLog += text;
    process.stderr.write(text);
  });

  const exited = new Promise((resolve) => child.on("exit", (code, signal) => resolve({ code, signal })));

  try {
    // 等 /healthz 就绪；服务端进程先退（端口被占、启动崩）则立刻失败
    const deadline = Date.now() + 15_000;
    for (;;) {
      const early = await Promise.race([
        exited,
        new Promise((resolve) => setTimeout(() => resolve(null), 300)),
      ]);
      if (early !== null) {
        throw new Error(`服务端提前退出（code=${early.code} signal=${early.signal}）`);
      }
      if (Date.now() > deadline) {
        throw new Error("15 秒内 /healthz 未就绪");
      }
      try {
        const response = await fetch(`${BASE_URL}/healthz`);
        if (response.ok) break;
      } catch {
        // 还没起来，继续等
      }
    }
    console.log("服务端已就绪");

    const smoke = spawnSync(
      process.execPath,
      [join(ROOT, "scripts", "smoke-api.mjs"), BASE_URL],
      {
        cwd: ROOT,
        stdio: "inherit",
        env: { ...process.env, LAN_DROP_DISCOVERY_PORT: String(DISCOVERY_PORT) },
      },
    );
    if (smoke.status !== 0) {
      throw new Error(`冒烟测试失败（exit=${smoke.status ?? smoke.signal}）`);
    }

    // 冒烟全绿之后，回头看一眼服务端日志：token 绝不能以原文出现
    const leakLine = serverLog.split("\n").find((line) => TOKEN_LEAK_RE.test(line));
    if (leakLine !== undefined) {
      console.error("\n✗ 服务端日志出现未脱敏的 token：");
      console.error(`  ${leakLine.trim().slice(0, 400)}`);
      throw new Error("日志中发现 token 泄漏");
    }
    console.log("服务端日志未发现 token 泄漏");
  } finally {
    if (child.exitCode === null && child.signalCode === null) {
      child.kill(IS_WINDOWS ? undefined : "SIGTERM");
      const forceKill = setTimeout(() => child.kill("SIGKILL"), 3000);
      child.once("exit", () => clearTimeout(forceKill));
    }
    try {
      rmSync(dataRoot, { recursive: true, force: true, maxRetries: 3 });
    } catch {
      // 临时目录删不掉不影响结论（在系统临时目录里，系统会兜底清理）
    }
  }

  console.log("\n✅ 全部通过：类型检查、单元测试、端到端冒烟");
}

main().catch((error) => {
  console.error("\nverify-all 异常终止：", error instanceof Error ? error.message : error);
  process.exit(1);
});
