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
 * 主冒烟之后还会用**独立实例**（端口 8897，LAN_DROP_RESERVE_BYTES 调到 4 TiB）
 * 确定性验证磁盘满预检：主冒烟机器的剩余空间通常远大于探测值，507 分支只有
 * 在「预留空间必然大于剩余空间」的实例上才是必经路径。
 *
 * 冒烟结束后还会对服务端日志做一次「token 泄漏」扫描：任何访问日志里出现
 * 未脱敏的 `token=<长串>` 都判失败——脱敏逻辑（app.ts 的 req 序列化器）回归时
 * 在这里拦住，而不是等日志落盘后才被人发现。
 *
 * 任何一步失败立即停止并让进程以非零码退出，适合直接当 CI 门禁（`pnpm verify`）。
 */

import { spawn, spawnSync } from "node:child_process";
import { existsSync, mkdtempSync, rmSync } from "node:fs";
import http from "node:http";
import https from "node:https";
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

/** 轮询 /healthz 直到就绪；服务端进程提前退出或 15 秒超时立即抛错。 */
async function waitForServer(baseUrl, exited) {
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
      const response = await fetch(`${baseUrl}/healthz`);
      if (response.ok) return;
    } catch {
      // 还没起来，继续等
    }
  }
}

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
        // 发现服务要开着（冒烟有 UDP 一节），但换到 8898 端口，不碰真实 8788
        LAN_DROP_DISCOVERY_PORT: String(DISCOVERY_PORT),
        LAN_DROP_DATA_ROOT: dataRoot,
        LAN_DROP_SERVER_NAME: "lan-drop-verify",
        LAN_DROP_LOG_LEVEL: "info",
        // TLS 默认开启（生产语义）；冒烟是明文协议测试，TLS 路径由下面的独立实例覆盖
        LAN_DROP_TLS: "0",
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
    await waitForServer(BASE_URL, exited);
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

  // ------------------------------------------------------------ 磁盘满预检（独立实例）
  //
  // 主冒烟所在机器的剩余空间通常远大于探测值，507 分支无法确定性触发。这里
  // 单独拉起一个把 LAN_DROP_RESERVE_BYTES 调到 4 TiB 的服务端：任何上传都必然
  // 「剩余空间不足」，建会话预检（507 disk_full）因此在任何机器上都是必经路径。
  console.log("\n───── 磁盘满预检（独立实例，预留空间 4 TiB）─────");
  const probeRoot = mkdtempSync(join(tmpdir(), "lan-drop-verify-disk-"));
  const probeChild = spawn(
    process.execPath,
    [join(ROOT, "apps", "server", "src", "index.ts")],
    {
      cwd: ROOT,
      stdio: ["ignore", "pipe", "pipe"],
      env: {
        ...process.env,
        LAN_DROP_PORT: "8897",
        LAN_DROP_DISCOVERY: "0",
        LAN_DROP_DATA_ROOT: probeRoot,
        LAN_DROP_SERVER_NAME: "lan-drop-verify-disk",
        LAN_DROP_RESERVE_BYTES: String(4 * 1024 ** 4),
        LAN_DROP_TLS: "0",
      },
    },
  );
  let probeLog = "";
  probeChild.stdout.on("data", (chunk) => {
    probeLog += String(chunk);
  });
  probeChild.stderr.on("data", (chunk) => {
    probeLog += String(chunk);
  });
  const probeExited = new Promise((resolve) =>
    probeChild.on("exit", (code, signal) => resolve({ code, signal })),
  );

  try {
    await waitForServer("http://127.0.0.1:8897", probeExited);

    const api = (path) => `http://127.0.0.1:8897${path}`;
    const codeRes = await fetch(api("/api/v1/pair/code"));
    if (!codeRes.ok) throw new Error(`预检实例读配对码失败（status=${codeRes.status}）`);
    const { code } = await codeRes.json();
    const pairRes = await fetch(api("/api/v1/pair"), {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ code, deviceName: "磁盘满预检", platform: "test" }),
    });
    if (!pairRes.ok) throw new Error(`预检实例配对失败（status=${pairRes.status}）`);
    const { deviceToken } = await pairRes.json();

    const createUpload = async (size) => {
      const response = await fetch(api("/api/v1/uploads"), {
        method: "POST",
        headers: {
          "content-type": "application/json",
          authorization: `Bearer ${deviceToken}`,
        },
        body: JSON.stringify({ name: `probe-${size}.bin`, size }),
      });
      return { status: response.status, body: await response.json() };
    };

    const failWithLog = (message) =>
      new Error(`${message}\n预检实例日志尾部：\n${probeLog.slice(-1200)}`);

    const fullSmall = await createUpload(10);
    if (fullSmall.status !== 507 || fullSmall.body?.error !== "disk_full") {
      throw failWithLog(
        `小文件也应被预留空间门槛拒绝，实际 status=${fullSmall.status} body=${JSON.stringify(fullSmall.body)}`,
      );
    }
    if (
      typeof fullSmall.body.freeBytes !== "number" ||
      typeof fullSmall.body.requiredBytes !== "number"
    ) {
      throw failWithLog(`507 响应缺少 freeBytes/requiredBytes：${JSON.stringify(fullSmall.body)}`);
    }

    // 预检先于空文件落盘：0 字节会话同样必须被拦，不能在 writeEmptyFile 上炸 500
    const fullZero = await createUpload(0);
    if (fullZero.status !== 507 || fullZero.body?.error !== "disk_full") {
      throw failWithLog(
        `0 字节会话也应被预检拒绝，实际 status=${fullZero.status} body=${JSON.stringify(fullZero.body)}`,
      );
    }

    console.log("磁盘满预检通过：507 disk_full + freeBytes/requiredBytes 齐全，0 字节同样被拦");
  } finally {
    if (probeChild.exitCode === null && probeChild.signalCode === null) {
      probeChild.kill(IS_WINDOWS ? undefined : "SIGTERM");
      const forceKill = setTimeout(() => probeChild.kill("SIGKILL"), 3000);
      probeChild.once("exit", () => clearTimeout(forceKill));
    }
    try {
      rmSync(probeRoot, { recursive: true, force: true, maxRetries: 3 });
    } catch {
      // 同主实例：临时目录删不掉不影响结论
    }
  }

  // ------------------------------------------------------------ TLS 独立实例
  //
  // 明文冒烟不覆盖 TLS 路径。这里按生产默认（LAN_DROP_TLS 不设 = 开）拉起一个
  // 实例：LAN 端口 https（自签证书）+ 回环端口明文，验证证书生成、指纹下发、
  // 二维码携带指纹、双监听器同时可达——客户端指纹固定依赖的四个事实。
  console.log("\n───── TLS 独立实例（自签证书 + 双监听器）─────");
  const tlsRoot = mkdtempSync(join(tmpdir(), "lan-drop-verify-tls-"));
  const tlsChild = spawn(
    process.execPath,
    [join(ROOT, "apps", "server", "src", "index.ts")],
    {
      cwd: ROOT,
      stdio: ["ignore", "pipe", "pipe"],
      env: {
        ...process.env,
        LAN_DROP_PORT: "8895",
        LAN_DROP_LOOPBACK_PORT: "8894",
        LAN_DROP_DISCOVERY: "0",
        LAN_DROP_DATA_ROOT: tlsRoot,
        LAN_DROP_SERVER_NAME: "lan-drop-verify-tls",
      },
    },
  );
  let tlsLog = "";
  tlsChild.stdout.on("data", (chunk) => {
    tlsLog += String(chunk);
  });
  tlsChild.stderr.on("data", (chunk) => {
    tlsLog += String(chunk);
  });
  const tlsExited = new Promise((resolve) =>
    tlsChild.on("exit", (code, signal) => resolve({ code, signal })),
  );

  try {
    await waitForServer("http://127.0.0.1:8894", tlsExited);

    const getJson = (url) =>
      new Promise((resolve, reject) => {
        const request = (url.startsWith("https:") ? https : http).get(
          url,
          { rejectUnauthorized: false },
          (response) => {
            let data = "";
            response.on("data", (chunk) => {
              data += chunk;
            });
            response.on("end", () => {
              try {
                resolve({ status: response.statusCode, body: JSON.parse(data) });
              } catch (error) {
                reject(new Error(`响应不是 JSON（${url}）：${data.slice(0, 200)}`));
              }
            });
          },
        );
        request.on("error", reject);
      });

    const healthTls = await getJson("https://127.0.0.1:8895/healthz");
    if (healthTls.status !== 200 || healthTls.body?.ok !== true) {
      throw new Error(`https /healthz 异常：status=${healthTls.status} body=${JSON.stringify(healthTls.body)}`);
    }

    const infoTls = await getJson("https://127.0.0.1:8895/api/v1/info");
    if (infoTls.body?.tls !== true) {
      throw new Error(`https /info 应声明 tls=true，实际 ${JSON.stringify(infoTls.body)}`);
    }
    const fingerprint = infoTls.body?.tlsFingerprint;
    if (typeof fingerprint !== "string" || !/^[A-Za-z0-9_-]{43}$/.test(fingerprint)) {
      throw new Error(`https /info 的 tlsFingerprint 缺失或形态不对：${String(fingerprint)}`);
    }

    const infoLoop = await getJson("http://127.0.0.1:8894/api/v1/info");
    if (infoLoop.body?.tls !== true || infoLoop.body?.tlsFingerprint !== fingerprint) {
      throw new Error("回环监听器的 /info 应与 LAN 监听器报告同一份 tls/tlsFingerprint");
    }

    const codeInfo = await getJson("http://127.0.0.1:8894/api/v1/pair/code");
    const url0 = codeInfo.body?.urls?.[0];
    if (
      codeInfo.body?.tls !== true ||
      typeof url0 !== "string" ||
      !url0.startsWith("https://") ||
      !url0.includes("#pair=") ||
      !url0.endsWith(`&fp=${fingerprint}`)
    ) {
      throw new Error(`配对二维码应携带 https + 指纹，实际 ${String(url0)}`);
    }

    const keyFile = join(tlsRoot, "tls", "key.pem");
    if (!existsSync(keyFile)) {
      throw new Error("TLS 私钥未持久化到数据根（tls/key.pem 不存在）");
    }

    console.log(`TLS 实例通过：https 可达、指纹 ${fingerprint.slice(0, 12)}… 下发一致、二维码携带指纹、密钥已持久化`);
  } finally {
    if (tlsChild.exitCode === null && tlsChild.signalCode === null) {
      tlsChild.kill("SIGTERM");
      const forceKill = setTimeout(() => tlsChild.kill("SIGKILL"), 3000);
      tlsChild.once("exit", () => clearTimeout(forceKill));
    }
    try {
      rmSync(tlsRoot, { recursive: true, force: true, maxRetries: 3 });
    } catch {
      // 同主实例：临时目录删不掉不影响结论
    }
  }

  console.log("\n✅ 全部通过：类型检查、单元测试、端到端冒烟、磁盘满预检、TLS 双监听器");
}

main().catch((error) => {
  console.error("\nverify-all 异常终止：", error instanceof Error ? error.message : error);
  process.exit(1);
});
