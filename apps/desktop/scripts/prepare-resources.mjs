#!/usr/bin/env node
/**
 * 准备 Tauri 桌面壳的构建资源（便携包流水线的后继者，P4-1 已放弃、产物布局思路延续）：
 *
 *   node apps/desktop/scripts/prepare-resources.mjs
 *
 * 产出（全部落在 src-tauri/ 下，git 忽略，构建时打进安装包）：
 *   - resources/server/server.js   esbuild 打包的自包含服务端 bundle
 *   - resources/web/dist/          浏览器控制台前端产物
 *   - binaries/node-x86_64-pc-windows-msvc.exe   Windows 构建的 sidecar node
 *   - binaries/node-x86_64-unknown-linux-gnu     Linux 构建的 sidecar node
 *   （按运行本脚本的平台自动选择，不做交叉；npmmirror 下载 + SHASUMS256.txt 校验）
 *
 * 资源布局与服务端 app.ts 的 resolveStaticRoot() 候选顺序对齐：
 * server.js 所在目录的上级找 web/dist —— 即 resources/server/server.js + resources/web/dist。
 * 必须自包含：桌面壳内没有 node_modules，任何未内联的裸依赖都会在用户机器上启动即炸。
 */

import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { existsSync } from "node:fs";
import { chmod, cp, mkdir, readFile, rm, stat, writeFile } from "node:fs/promises";
import { builtinModules } from "node:module";
import { fileURLToPath } from "node:url";
import { join } from "node:path";

import { build as esbuild } from "esbuild";

const repoRoot = fileURLToPath(new URL("../../..", import.meta.url));
const desktopRoot = join(repoRoot, "apps/desktop");
const tauriDir = join(desktopRoot, "src-tauri");
const resourcesDir = join(tauriDir, "resources");
const binariesDir = join(tauriDir, "binaries");
const cacheDir = join(repoRoot, "build", "cache");

/** sidecar 使用的 node 版本；与已放弃的便携包保持一致，固定版本才能复现。 */
const BUNDLED_NODE_VERSION = process.env.LAN_DROP_NODE_VERSION ?? "24.20.0";
const NODE_MIRROR = "https://registry.npmmirror.com/-/binary/node";
const WINDOWS_TARGET_TRIPLE = "x86_64-pc-windows-msvc";
const LINUX_TARGET_TRIPLE = "x86_64-unknown-linux-gnu";

/** fastify/ws 生态的可选原生加速模块：装了才 require，未装自动降级，绝不打包。 */
const OPTIONAL_NATIVES = ["bufferutil", "utf-8-validate"];
const BUILTINS = new Set([...builtinModules, ...builtinModules.map((name) => `node:${name}`)]);

function log(message) {
  console.log(`[desktop-resources] ${message}`);
}

async function gitShortSha() {
  const result = spawnSync("git", ["rev-parse", "--short", "HEAD"], { encoding: "utf8" });
  return (result.stdout ?? "").trim() || "unknown";
}

/** 与 scripts/package.mjs（已随便携包放弃删除）同一套判定：metafile 查真实模块图。 */
function assertSelfContained(metafile) {
  const key = Object.keys(metafile.outputs).find((candidate) => candidate.endsWith("server/server.js"));
  if (key === undefined) throw new Error("metafile 里没有 server/server.js 的输出记录");
  const externals = (metafile.outputs[key]?.imports ?? [])
    .filter((item) => item.external === true)
    .map((item) => item.path);
  const offenders = [
    ...new Set(externals.filter((path) => !BUILTINS.has(path) && !OPTIONAL_NATIVES.includes(path))),
  ];
  if (offenders.length > 0) {
    throw new Error(
      `bundle 不是自包含的，以下模块没被打进去（桌面壳里没有 node_modules）：\n  ` +
        offenders.join("\n  "),
    );
  }
  return [...new Set(externals)];
}

async function bundleServer(version, commit) {
  const outfile = join(resourcesDir, "server", "server.js");
  const banner = [
    "/*",
    " * LAN-Drop 服务端 —— 桌面壳构建产物，不要直接编辑。",
    ` * 版本 ${version}（commit ${commit}），由 apps/desktop/scripts/prepare-resources.mjs 生成。`,
    " */",
    // ESM 产物里没有 require，fastify/avvio/ws 这些 CJS 依赖会 require("node:events")。
    // 必须在 banner 里定义 createRequire：要早于 esbuild 的 __require 兜底初始化。
    'import { createRequire as __lanDropCreateRequire } from "node:module";',
    "const require = __lanDropCreateRequire(import.meta.url);",
  ].join("\n");

  const result = await esbuild({
    entryPoints: [join(repoRoot, "apps/server/src/index.ts")],
    outfile,
    bundle: true,
    platform: "node",
    target: "node24",
    format: "esm",
    external: OPTIONAL_NATIVES,
    banner: { js: banner },
    legalComments: "none",
    logLevel: "warning",
    metafile: true,
  });
  const externals = assertSelfContained(result.metafile);
  const size = (await stat(outfile)).size;
  log(`服务端 bundle：${(size / 1024).toFixed(0)} KB，自包含检查通过（外部依赖仅：${externals.join("、") || "无"}）`);
}

async function copyWebDist() {
  const webDist = join(repoRoot, "apps/web/dist");
  if (!existsSync(join(webDist, "index.html"))) {
    throw new Error("apps/web/dist/index.html 不存在：先跑 `pnpm web:build`");
  }
  await cp(webDist, join(resourcesDir, "web", "dist"), { recursive: true });
  log("已拷贝 web/dist 控制台前端");
}

/** 下载（或复用 build/cache）node 官方分发包，并对照同目录 SHASUMS256.txt 校验 sha256。 */
async function fetchNodeDistribution(version, archiveName) {
  const archivePath = join(cacheDir, archiveName);
  await mkdir(cacheDir, { recursive: true });

  if (!existsSync(archivePath)) {
    const url = `${NODE_MIRROR}/v${version}/${archiveName}`;
    log(`下载 sidecar node：${url}`);
    const response = await fetch(url);
    if (!response.ok) throw new Error(`下载 node 失败：HTTP ${response.status} ${url}`);
    const buffer = Buffer.from(await response.arrayBuffer());
    await writeFile(archivePath, buffer);

    const digest = createHash("sha256").update(buffer).digest("hex");
    try {
      const shasums = await fetch(`${NODE_MIRROR}/v${version}/SHASUMS256.txt`);
      if (shasums.ok) {
        const line = (await shasums.text()).split("\n").find((row) => row.trim().endsWith(archiveName));
        const expected = line?.trim().split(/\s+/)[0];
        if (expected && expected !== digest) {
          throw new Error(`node 分发包校验和不匹配：期望 ${expected}，实际 ${digest}`);
        }
        log(expected ? "  分发包 sha256 校验通过" : "  SHASUMS256.txt 里没有该文件，跳过校验");
      } else {
        log(`  取不到 SHASUMS256.txt（HTTP ${shasums.status}），跳过校验`);
      }
    } catch (error) {
      if (error instanceof Error && error.message.includes("校验和不匹配")) throw error;
      log(`  校验步骤出错，跳过：${error instanceof Error ? error.message : String(error)}`);
    }
  } else {
    log(`复用缓存的 node 分发包：${archiveName}`);
  }
  return archivePath;
}

/** Windows 版 node：zip 包里的 node.exe。 */
async function fetchWindowsNode(version) {
  const archivePath = await fetchNodeDistribution(version, `node-v${version}-win-x64.zip`);
  await rm(join(cacheDir, "node.exe"), { force: true });
  const extract = spawnSync(
    "unzip",
    ["-o", "-j", archivePath, `node-v${version}-win-x64/node.exe`, "-d", cacheDir],
    { stdio: "ignore" },
  );
  if (extract.error) throw extract.error;
  if (extract.status !== 0) throw new Error(`unzip 提取 node.exe 失败（exit ${extract.status}）`);
  const nodeExe = join(cacheDir, "node.exe");
  if (!existsSync(nodeExe)) throw new Error("从压缩包里没提取到 node.exe");
  return nodeExe;
}

/** Linux 版 node：tar.xz 包里的 bin/node（--strip-components 去掉包内三级目录）。 */
async function fetchLinuxNode(version) {
  const archivePath = await fetchNodeDistribution(version, `node-v${version}-linux-x64.tar.xz`);
  await rm(join(cacheDir, "node"), { force: true });
  const extract = spawnSync(
    "tar",
    [
      "-xJf",
      archivePath,
      "-C",
      cacheDir,
      `node-v${version}-linux-x64/bin/node`,
      // 包内路径 node-v<ver>/bin/node 共 3 层，剥前 2 层后落盘为 ./node；
      // 剥 3 层会把文件名本身也剥掉（tar 会静默跳过空路径成员）。
      "--strip-components=2",
    ],
    { stdio: "ignore" },
  );
  if (extract.error) throw extract.error;
  if (extract.status !== 0) throw new Error(`tar 提取 node 失败（exit ${extract.status}）`);
  const nodeBinary = join(cacheDir, "node");
  if (!existsSync(nodeBinary)) throw new Error("从 tar.xz 里没提取到 node");
  return nodeBinary;
}

/** 在哪个平台构建就取哪边的 node：sidecar 必须与主程序同平台，不做交叉。
 *  例外：为 Windows 构建树准备资源时，可在 WSL 里用 LAN_DROP_TARGET_PLATFORM=win32 覆盖
 *  （取值与 process.platform 同空间：win32 / linux），产出的 binaries/resources 平台无关可直拷。 */
function sidecarPlatform() {
  const override = process.env.LAN_DROP_TARGET_PLATFORM?.trim().toLowerCase();
  switch (override || process.platform) {
    case "win32": {
      return { os: "windows", triple: WINDOWS_TARGET_TRIPLE, suffix: ".exe" };
    }
    case "linux": {
      return { os: "linux", triple: LINUX_TARGET_TRIPLE, suffix: "" };
    }
    default: {
      throw new Error(`暂不支持为 ${override || process.platform} 构建桌面壳 sidecar`);
    }
  }
}

async function placeSidecarNode() {
  const platform = sidecarPlatform();
  const nodeBinary =
    platform.os === "windows"
      ? await fetchWindowsNode(BUNDLED_NODE_VERSION)
      : await fetchLinuxNode(BUNDLED_NODE_VERSION);
  await mkdir(binariesDir, { recursive: true });
  const target = join(binariesDir, `node-${platform.triple}${platform.suffix}`);
  await rm(target, { force: true });
  await cp(nodeBinary, target);
  await chmod(target, 0o755); // Windows 忽略；Linux 的 fs.cp 不保留执行位，必须补
  const size = (await stat(target)).size;
  log(`sidecar node：binaries/node-${platform.triple}${platform.suffix}（${(size / 1024 / 1024).toFixed(0)} MB，v${BUNDLED_NODE_VERSION}，${platform.os}）`);
}

const commit = await gitShortSha();
await rm(resourcesDir, { recursive: true, force: true });
await mkdir(join(resourcesDir, "server"), { recursive: true });
await bundleServer("0.1.0", commit);
await copyWebDist();
await placeSidecarNode();
log("完成：src-tauri/{resources,binaries} 已就绪");
