#!/usr/bin/env node
/**
 * LAN-Drop 便携包构建流水线（P4-1）。
 *
 *   node scripts/package.mjs --target stage     # 只产出 build/app（bundle + web/dist + public）
 *   node scripts/package.mjs --target win       # 追加 Windows 便携包（含便携 node.exe）→ dist/*.zip
 *   node scripts/package.mjs --target linux     # 追加 Linux 包（tar.gz + systemd user unit）
 *   node scripts/package.mjs --target all       # 两者都出（默认）
 *   node scripts/package.mjs --skip-web         # 不重跑前端构建，复用 apps/web/dist
 *
 * 设计要点：
 *   1. **编译只发生在出包流水线**。仓库里服务端始终是 `node src/index.ts` 无编译直跑；
 *      这里用 esbuild 打成一个自包含的 `server/server.js`，产物不依赖 node_modules。
 *   2. **产物必须自包含**：用 esbuild metafile 检查真实模块图，出现 `node:` 内建与少数可选
 *      原生模块（bufferutil / utf-8-validate）之外的**外部**依赖就直接失败——那种包在干净机器上必炸。
 *   3. 布局固定为 `app/server/server.js` + `app/web/dist`，与服务端 `app.ts` 的
 *      静态目录解析顺序对齐（见那里的注释）。数据目录由 `config.ts` 决定
 *      （XDG / %LOCALAPPDATA%），与程序目录天然分离，升级 = 覆盖程序目录。
 */

import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { existsSync } from "node:fs";
import { chmod, cp, mkdir, readFile, readdir, rm, stat, writeFile } from "node:fs/promises";
import { builtinModules } from "node:module";
import { basename, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

import { build } from "esbuild";

const repoRoot = fileURLToPath(new URL("..", import.meta.url));
const buildDir = join(repoRoot, "build");
/** 便携包里 `app/` 的内容（bundle + 前端产物 + 兜底页），平台无关。 */
const stageDir = join(buildDir, "app");
const distDir = join(repoRoot, "dist");
const cacheDir = join(buildDir, "cache");

/** Windows 便携包内置的 node.exe 版本。固定版本才能复现；用 LAN_DROP_NODE_VERSION 覆盖。 */
const BUNDLED_NODE_VERSION = "24.20.0";
/** Node 官方分发包的国内镜像（nodejs.org 在本机很慢，官方源实测 44~210 KB/s）。 */
const NODE_MIRROR = "https://registry.npmmirror.com/-/binary/node";

/** fastify/ws 生态里的可选原生加速模块：装了才 require，未装会自动降级，绝不能打进产物。 */
const OPTIONAL_NATIVES = ["bufferutil", "utf-8-validate"];

/** Node 内建模块（含裸名与 `node:` 前缀两种写法），它们本来就该留在产物外部。 */
const BUILTINS = new Set([...builtinModules, ...builtinModules.map((name) => `node:${name}`)]);

function log(message) {
  console.log(`[package] ${message}`);
}

function run(command, args, options = {}) {
  const result = spawnSync(command, args, { stdio: "inherit", ...options });
  if (result.error) throw result.error;
  if (result.status !== 0) {
    throw new Error(`命令失败（exit ${result.status}）：${command} ${args.join(" ")}`);
  }
}

async function pathExists(path) {
  try {
    await stat(path);
    return true;
  } catch {
    return false;
  }
}

async function readVersion() {
  const pkg = JSON.parse(await readFile(join(repoRoot, "package.json"), "utf8"));
  const commit = spawnSync("git", ["rev-parse", "--short", "HEAD"], {
    cwd: repoRoot,
    encoding: "utf8",
  });
  const dirty = spawnSync("git", ["status", "--porcelain"], { cwd: repoRoot, encoding: "utf8" });
  const sha = (commit.stdout ?? "").trim() || "unknown";
  const isDirty = (dirty.stdout ?? "").trim().length > 0;
  return {
    version: pkg.version,
    commit: isDirty ? `${sha}-dirty` : sha,
    node: BUNDLED_NODE_VERSION,
  };
}

/**
 * 用 esbuild 的 metafile 检查产物是否自包含：列出真正留在产物外部的模块。
 *
 * 便携包里没有 node_modules，任何没被内联的裸模块都会在**启动时**才炸，所以出包阶段就拦下来。
 *
 * **不能用「正则扫产物文本」**：ajv 会把 `require("ajv/dist/runtime/uri").default`
 * 作为**字符串字面量**写进生成代码（`uri.code = '...'`），文本扫描会把这类字符串误判成
 * 未打包的依赖——而它们其实就在产物里。metafile 记录真实模块图，不会误报。
 */
function assertSelfContained(metafile, outfile) {
  const key = Object.keys(metafile.outputs).find((candidate) =>
    candidate.endsWith("server/server.js"),
  );
  if (key === undefined) throw new Error(`metafile 里没有 ${outfile} 的输出记录`);
  const output = metafile.outputs[key];

  const externals = (output?.imports ?? [])
    .filter((item) => item.external === true)
    .map((item) => item.path);
  // CJS 依赖里写的是裸内建名（require("assert")），ESM 里是 node: 前缀，两种都要认
  const offenders = [
    ...new Set(
      externals.filter((path) => !BUILTINS.has(path) && !OPTIONAL_NATIVES.includes(path)),
    ),
  ];

  if (offenders.length > 0) {
    throw new Error(
      `bundle 不是自包含的，以下模块没被打进去（便携包里没有 node_modules）：\n  ` +
        offenders.join("\n  ") +
        "\n若是可选依赖就加进 package.mjs 的 external 列表，否则修正导入方式。",
    );
  }

  return [...new Set(externals)];
}

/** esbuild 打服务端 bundle + 拷前端产物，得到平台无关的 `build/app`。 */
export async function buildStage() {
  const meta = await readVersion();
  const { version, commit } = meta;

  await rm(stageDir, { recursive: true, force: true });
  await mkdir(join(stageDir, "server"), { recursive: true });

  log(`打包服务端 bundle（esbuild，版本 ${version}，commit ${commit}）…`);
  const banner = [
    "/*",
    " * LAN-Drop 服务端 —— 构建产物，不要直接编辑。",
    ` * 版本 ${version}（commit ${commit}），由 scripts/package.mjs 生成。`,
    " * 源码：apps/server/src/index.ts；仓库里跑的是源码，这里只是出包形态。",
    " */",
    // ESM 产物里没有 require，而 fastify/avvio/ws 这些 CJS 依赖会 `require("node:events")` 之类。
    // esbuild 的 __require 兜底在找不到 require 时直接抛「Dynamic require of ... is not supported」，
    // 表现为启动即崩；用 createRequire 把 require 恢复到模块作用域即可（esbuild 官方推荐的写法）。
    // 必须在 banner 里定义：它要早于 esbuild 的 __require 初始化执行。
    'import { createRequire as __lanDropCreateRequire } from "node:module";',
    "const require = __lanDropCreateRequire(import.meta.url);",
  ].join("\n");

  const result = await build({
    entryPoints: [join(repoRoot, "apps/server/src/index.ts")],
    outfile: join(stageDir, "server", "server.js"),
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

  const bundlePath = join(stageDir, "server", "server.js");
  const externals = assertSelfContained(result.metafile, bundlePath);
  const bundleSize = (await stat(bundlePath)).size;
  log(
    `  服务端 bundle：${(bundleSize / 1024).toFixed(0)} KB，自包含检查通过` +
      `（外部依赖仅：${externals.join("、") || "无"}）`,
  );

  const webDist = join(repoRoot, "apps/web/dist");
  if (!existsSync(join(webDist, "index.html"))) {
    throw new Error("apps/web/dist/index.html 不存在：先跑 `pnpm web:build`（本脚本默认会代跑，除非 --skip-web）");
  }
  await cp(webDist, join(stageDir, "web", "dist"), { recursive: true });
  await cp(join(repoRoot, "apps/server/public"), join(stageDir, "public"), { recursive: true });
  log("  已拷贝 web/dist 与 public 兜底页");

  await writeFile(
    join(buildDir, "version.json"),
    `${JSON.stringify({ ...meta, builtAt: new Date().toISOString() }, null, 2)}\n`,
  );

  // 带上 node 版本：Windows 包要按它去拉便携 node.exe（漏掉过一次，表现为 vundefined 404）
  return meta;
}

async function copyCrlfTree(sourceDir, targetDir, extensions) {
  await mkdir(targetDir, { recursive: true });
  for (const entry of await readdir(sourceDir, { withFileTypes: true })) {
    const source = join(sourceDir, entry.name);
    const target = join(targetDir, entry.name);
    if (entry.isDirectory()) {
      await copyCrlfTree(source, target, extensions);
      continue;
    }
    if (extensions.some((ext) => entry.name.endsWith(ext))) {
      // BOM 必须原样保留：.ps1 去掉 BOM 后，Windows PowerShell 5.1 会按 ANSI 代码页解码，
      // 中文字符串变乱码并报出与真实原因无关的语法错误（仓库里已踩过，见 common.ps1 顶部）。
      const raw = await readFile(source);
      const hasBom = raw[0] === 0xef && raw[1] === 0xbb && raw[2] === 0xbf;
      const text = raw.toString("utf8").replace(/^\uFEFF/, "");
      await writeFile(
        target,
        `${hasBom ? "\uFEFF" : ""}${text.replace(/\r?\n/g, "\r\n")}`,
        "utf8",
      );
      if (!hasBom && entry.name.endsWith(".ps1")) {
        throw new Error(`${source} 缺少 UTF-8 BOM：PowerShell 5.1 会把中文当 ANSI 解码，必须先修好`);
      }
    } else {
      await cp(source, target);
    }
  }
}

async function sha256(file) {
  const hash = createHash("sha256");
  hash.update(await readFile(file));
  return hash.digest("hex");
}

/** 下载（或复用缓存）Windows 版 node.exe，返回其路径。 */
async function fetchWindowsNode(version) {
  const zipName = `node-v${version}-win-x64.zip`;
  const zipPath = join(cacheDir, zipName);
  await mkdir(cacheDir, { recursive: true });

  if (!existsSync(zipPath)) {
    const url = `${NODE_MIRROR}/v${version}/${zipName}`;
    log(`下载便携 node.exe：${url}`);
    const response = await fetch(url);
    if (!response.ok) throw new Error(`下载 node 失败：HTTP ${response.status} ${url}`);

    const buffer = Buffer.from(await response.arrayBuffer());
    await writeFile(zipPath, buffer);

    // 校验和尽力而为：镜像偶尔没有 SHASUMS256.txt，缺了只告警不阻断
    const digest = createHash("sha256").update(buffer).digest("hex");
    try {
      const shasums = await fetch(`${NODE_MIRROR}/v${version}/SHASUMS256.txt`);
      if (shasums.ok) {
        const line = (await shasums.text())
          .split("\n")
          .find((row) => row.trim().endsWith(zipName));
        const expected = line?.trim().split(/\s+/)[0];
        if (expected && expected !== digest) {
          throw new Error(`node 分发包校验和不匹配：期望 ${expected}，实际 ${digest}`);
        }
        log(expected ? "  node.exe 压缩包 sha256 校验通过" : "  SHASUMS256.txt 里没有该文件，跳过校验");
      } else {
        log(`  取不到 SHASUMS256.txt（HTTP ${shasums.status}），跳过校验`);
      }
    } catch (error) {
      if (error instanceof Error && error.message.includes("校验和不匹配")) throw error;
      log(`  校验步骤出错，跳过：${error instanceof Error ? error.message : String(error)}`);
    }
  } else {
    log(`复用缓存的分发包：${zipName}`);
  }

  const nodeExe = join(cacheDir, "node.exe");
  await rm(nodeExe, { force: true });
  log("  从压缩包提取 node.exe …");
  run("unzip", ["-o", "-j", zipPath, `node-v${version}-win-x64/node.exe`, "-d", cacheDir], {
    stdio: "ignore",
  });
  if (!existsSync(nodeExe)) throw new Error("从压缩包里没提取到 node.exe");
  return nodeExe;
}

async function writeVersionFile(target, { version, commit }, platform, extraLines = []) {
  const lines = [
    `LAN-Drop ${version}`,
    `commit:   ${commit}`,
    `platform: ${platform}`,
    `built:    ${new Date().toISOString()}`,
    ...extraLines,
    "",
    "升级方式：用新版本覆盖本目录（数据在用户数据目录里，不受影响）。",
  ];
  await writeFile(target, `${lines.join("\n")}\n`, "utf8");
}

async function assembleWindows(meta) {
  const name = `lan-drop-${meta.version}-win-x64`;
  const pkgDir = join(distDir, name);
  log(`组装 Windows 便携包：dist/${name}/`);
  await rm(pkgDir, { recursive: true, force: true });
  await mkdir(pkgDir, { recursive: true });

  await cp(stageDir, join(pkgDir, "app"), { recursive: true });
  await mkdir(join(pkgDir, "node"), { recursive: true });
  const nodeExe = await fetchWindowsNode(meta.node);
  await cp(nodeExe, join(pkgDir, "node", "node.exe"));
  log(`  内置 node.exe：${((await stat(join(pkgDir, "node", "node.exe"))).size / 1024 / 1024).toFixed(0)} MB`);

  await copyCrlfTree(join(repoRoot, "packaging/windows"), pkgDir, [".ps1", ".cmd", ".txt"]);
  await writeVersionFile(join(pkgDir, "VERSION"), meta, "windows-x64", [
    `node:     v${meta.node}（包内 node/node.exe）`,
    "",
    "三步部署：1) 右键 install.ps1 → 以管理员身份运行；2) 手机装 App 后点「扫描局域网」；",
    "          3) 输入终端里显示的配对码。详见 README.txt。",
  ]);

  const archiveName = `${name}.zip`;
  log(`  压缩：dist/${archiveName}`);
  run("zip", ["-r", "-q", archiveName, name], { cwd: distDir });
  return archiveName;
}

async function assembleLinux(meta) {
  const name = `lan-drop-${meta.version}-linux-x64`;
  const pkgDir = join(distDir, name);
  log(`组装 Linux 包：dist/${name}/`);
  await rm(pkgDir, { recursive: true, force: true });
  await mkdir(pkgDir, { recursive: true });

  await cp(stageDir, join(pkgDir, "app"), { recursive: true });
  await cp(join(repoRoot, "packaging/linux"), pkgDir, { recursive: true });
  for (const executable of ["install.sh", "uninstall.sh", "bin/lan-drop"]) {
    await chmod(join(pkgDir, executable), 0o755);
  }
  await writeVersionFile(join(pkgDir, "VERSION"), meta, "linux-x64", [
    "node:     优先用包内 node/node，没有则用 PATH 里的 node（需 ≥ 24）",
    "",
    "安装：./install.sh（写 systemd user unit 并启动）；卸载：./uninstall.sh。详见 README.md。",
  ]);

  const archiveName = `${name}.tar.gz`;
  log(`  打包：dist/${archiveName}`);
  run("tar", ["-czf", archiveName, name], { cwd: distDir });
  return archiveName;
}

/**
 * 汇总 dist/ 里**全部**发布包（而不是本次构建的那几个）的校验和。
 * 只列本次产物会把上一次的包从清单里抹掉——两个包分两次构建时就丢了一半。
 */
async function writeChecksums() {
  const archives = (await readdir(distDir))
    .filter((name) => name.endsWith(".zip") || name.endsWith(".tar.gz"))
    .sort();

  const lines = [];
  for (const archive of archives) {
    lines.push(`${await sha256(join(distDir, archive))}  ${basename(archive)}`);
  }
  await writeFile(join(distDir, "SHA256SUMS.txt"), `${lines.join("\n")}\n`, "utf8");
  console.log("");
  log("产物校验和（dist/SHA256SUMS.txt）：");
  for (const line of lines) console.log(`  ${line}`);
}

function parseArgs(argv) {
  const args = { target: "all", withWeb: true };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === "--target") args.target = argv[++i] ?? "all";
    else if (arg === "--skip-web") args.withWeb = false;
    else if (arg === "--help" || arg === "-h") args.help = true;
    else throw new Error(`未知参数：${arg}（用 --help 看用法）`);
  }
  return args;
}

export async function packageAll(target, { withWeb = true } = {}) {
  if (withWeb) {
    log("构建前端（pnpm --filter @lan-drop/web build）…");
    run("pnpm", ["--filter", "@lan-drop/web", "build"], { cwd: repoRoot });
  }

  const meta = await buildStage();
  if (target === "stage") {
    log(`完成：build/app（版本 ${meta.version}，commit ${meta.commit}）`);
    return [];
  }

  await mkdir(distDir, { recursive: true });
  const archives = [];
  if (target === "win" || target === "all") archives.push(await assembleWindows(meta));
  if (target === "linux" || target === "all") archives.push(await assembleLinux(meta));
  await writeChecksums();

  console.log("");
  log(`完成：${archives.map((a) => `dist/${a}`).join("、")}`);
  return archives;
}

const isMain =
  process.argv[1] !== undefined && import.meta.url === pathToFileURL(process.argv[1]).href;

if (isMain) {
  const args = parseArgs(process.argv.slice(2));
  if (args.help) {
    console.log(await readFile(fileURLToPath(import.meta.url), "utf8").then((text) =>
      text.split("\n").slice(1, 17).join("\n").replace(/^ \*\/?/gm, ""),
    ));
    process.exit(0);
  }
  await packageAll(args.target, { withWeb: args.withWeb });
}
