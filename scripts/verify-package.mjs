#!/usr/bin/env node
/**
 * 便携包验收：解压发布包 → 用包内产物启动 → 对**包内服务端**跑完整 smoke。
 *
 *   node scripts/verify-package.mjs --target linux
 *   node scripts/verify-package.mjs --target win
 *   node scripts/verify-package.mjs --target linux --skip-build   # 复用已有 dist/
 *
 * 为什么必须单独有这一步：`pnpm dev` 跑的是源码（原生类型剥离、node_modules 齐全），
 * 而发布包跑的是 esbuild 单文件产物 + 自带 node。两者不等价的地方（动态 require、
 * 静态资源路径、缺少 node_modules）只有真正启动这个包才会暴露。
 *
 * 验证范围与不覆盖的部分：
 *   覆盖：包内 server.js 能启动、web/dist 被正确托管（不是退回冒泡页）、
 *         UDP 发现、完整 API/WS/断点续传（67 项 smoke）、Linux 安装脚本语法与 unit 生成、
 *         Windows 脚本的 PowerShell 语法解析、Win 包内 node.exe 在真实 Windows 上启动。
 *   不覆盖：防火墙规则、登录自启任务的真实注册（会改动本机系统，留给人工在目标机验收）。
 */

import { spawn, spawnSync } from "node:child_process";
import { cp, mkdir, mkdtemp, readFile, readdir, rm, stat } from "node:fs/promises";
import { existsSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const repoRoot = fileURLToPath(new URL("..", import.meta.url));
const distDir = join(repoRoot, "dist");
const verifyDir = join(repoRoot, "build", "verify");

/** 验收用端口：避开默认 8787/8788，免得踩到正在开发的服务端 */
const VERIFY_PORT = 18787;
const VERIFY_DISCOVERY_PORT = 18788;

function log(message) {
  console.log(`[verify] ${message}`);
}

function run(command, args, options = {}) {
  const result = spawnSync(command, args, { stdio: "inherit", ...options });
  if (result.error) throw result.error;
  return result.status ?? 1;
}

async function pathExists(path) {
  try {
    await stat(path);
    return true;
  } catch {
    return false;
  }
}

/** 等 /api/v1/info 起来；返回 info 对象，超时抛错。 */
async function waitForServer(baseUrl, timeoutMs = 25_000) {
  const deadline = Date.now() + timeoutMs;
  let lastError = "未知错误";
  while (Date.now() < deadline) {
    try {
      const response = await fetch(`${baseUrl}/api/v1/info`, { signal: AbortSignal.timeout(2000) });
      if (response.ok) return await response.json();
      lastError = `HTTP ${response.status}`;
    } catch (error) {
      lastError = error instanceof Error ? error.message : String(error);
    }
    await new Promise((resolve) => setTimeout(resolve, 400));
  }
  throw new Error(`服务端 ${timeoutMs} ms 内没有就绪：${lastError}`);
}

/**
 * 确认托管的是**真正的前端产物**而不是 P0 兜底冒泡页。
 *
 * 两者都是「打开有内容」的 HTML，光看状态码发现不了：静态目录解析一旦写错，
 * 包里的 web/dist 会被静默忽略，用户看到的是冒泡页而不是聊天界面。
 */
async function assertWebAssets(baseUrl) {
  const page = await fetch(`${baseUrl}/`, { signal: AbortSignal.timeout(5000) });
  const html = await page.text();
  if (!page.ok) throw new Error(`GET / 返回 ${page.status}`);
  if (!html.includes('<div id="root">')) throw new Error("GET / 不是前端产物（缺 <div id=\"root\">）");
  const assetMatch = html.match(/\/assets\/[^"']+\.js/);
  if (!assetMatch) throw new Error("GET / 里没有 /assets/*.js 引用，疑似退回了兜底冒泡页");
  const asset = await fetch(`${baseUrl}${assetMatch[0]}`, { signal: AbortSignal.timeout(5000) });
  if (!asset.ok) throw new Error(`静态资源 ${assetMatch[0]} 返回 ${asset.status}`);
  const size = (await asset.arrayBuffer()).byteLength;
  if (size < 1000) throw new Error(`静态资源 ${assetMatch[0]} 只有 ${size} 字节，内容不对`);
  log(`  前端产物已托管：${assetMatch[0]}（${(size / 1024).toFixed(0)} KB）`);
}

function runSmoke(baseUrl) {
  log("  对包内服务端跑 67 项 smoke …");
  return run("node", [join(repoRoot, "scripts", "smoke-api.mjs"), baseUrl], {
    cwd: repoRoot,
    env: { ...process.env, LAN_DROP_DISCOVERY_PORT: String(VERIFY_DISCOVERY_PORT) },
  });
}

async function findArchive(pattern) {
  const entries = await readdir(distDir).catch(() => []);
  const match = entries.filter((name) => name.includes(pattern)).sort().at(-1);
  if (!match) throw new Error(`dist/ 里没有 ${pattern} 产物：先跑 node scripts/package.mjs --target all`);
  return join(distDir, match);
}

/** 解压 tar.gz / zip 到 build/verify/<name>，返回包根目录（压缩包里那一层）。 */
async function extract(archive, name) {
  const target = join(verifyDir, name);
  await rm(target, { recursive: true, force: true });
  await mkdir(target, { recursive: true });
  const status = archive.endsWith(".zip")
    ? run("unzip", ["-q", archive, "-d", target])
    : run("tar", ["-xzf", archive, "-C", target]);
  if (status !== 0) throw new Error(`解压失败：${archive}`);

  const inner = (await readdir(target)).find((entry) => entry.startsWith("lan-drop-"));
  if (inner === undefined) throw new Error(`压缩包里没有 lan-drop-* 目录：${archive}`);
  return join(target, inner);
}

async function verifyLinux({ skipBuild }) {
  if (!skipBuild) {
    log("构建 Linux 包（esbuild bundle + web/dist + tar.gz）…");
    const { packageAll } = await import("./package.mjs");
    await packageAll("linux");
  }

  const archive = await findArchive("linux-x64");
  log(`解压 ${archive}`);
  const pkgDir = await extract(archive, "linux");

  // 可执行位：tar 没保住权限位是发布包最常见的低级事故
  for (const relative of ["bin/lan-drop", "install.sh", "uninstall.sh"]) {
    const mode = (await stat(join(pkgDir, relative))).mode;
    if ((mode & 0o111) === 0) throw new Error(`${relative} 没有可执行权限（tar 打包时丢了）`);
  }
  log("  可执行位正常");

  // Shell 语法检查（不执行）：安装/卸载脚本只在目标机跑，语法错要在出包时就发现
  for (const script of ["install.sh", "uninstall.sh"]) {
    if (run("sh", ["-n", join(pkgDir, script)]) !== 0) throw new Error(`${script} 语法检查失败`);
  }
  const dryRun = spawnSync("sh", [join(pkgDir, "install.sh"), "--dry-run"], {
    encoding: "utf8",
    env: { ...process.env, LAN_DROP_UNIT_DIR: join(tmpdir(), "lan-drop-dryrun"), LAN_DROP_CONFIG_DIR: join(tmpdir(), "lan-drop-dryrun") },
  });
  if (dryRun.status !== 0) throw new Error(`install.sh --dry-run 失败：${dryRun.stderr}`);
  if (!dryRun.stdout.includes(`ExecStart=${pkgDir}/bin/lan-drop`)) {
    throw new Error("install.sh --dry-run 生成的 unit 里 ExecStart 路径不对");
  }
  log("  install.sh 语法与 unit 生成正常（--dry-run，未触碰 systemd）");

  // 真的启动这个包：数据目录与配置都放临时目录，绝不碰 ~/.local/share 里的真实数据
  const dataRoot = await mkdtemp(join(tmpdir(), "lan-drop-verify-data-"));
  const baseUrl = `http://127.0.0.1:${VERIFY_PORT}`;
  log(`启动包内服务端（数据目录 ${dataRoot}）…`);
  const server = spawn(join(pkgDir, "bin", "lan-drop"), [], {
    env: {
      ...process.env,
      LAN_DROP_DATA_ROOT: dataRoot,
      LAN_DROP_HOST: "127.0.0.1",
      LAN_DROP_PORT: String(VERIFY_PORT),
      LAN_DROP_DISCOVERY_PORT: String(VERIFY_DISCOVERY_PORT),
      LAN_DROP_SERVER_NAME: "verify-linux",
      // 绝不要读用户真实的 ~/.config/lan-drop/env（会带上真实端口与名字）
      LAN_DROP_ENV_FILE: join(dataRoot, "nonexistent.env"),
    },
    stdio: ["ignore", "pipe", "pipe"],
  });
  let output = "";
  server.stdout.on("data", (chunk) => { output += chunk.toString(); });
  server.stderr.on("data", (chunk) => { output += chunk.toString(); });

  let failed = false;
  try {
    const info = await waitForServer(baseUrl);
    log(`  服务端就绪：${info.serverName}`);
    await assertWebAssets(baseUrl);
    failed = runSmoke(baseUrl) !== 0;
  } catch (error) {
    failed = true;
    console.error(`[verify] linux 包验收失败：${error instanceof Error ? error.message : error}`);
    console.error(output.split("\n").slice(-15).join("\n"));
  } finally {
    server.kill("SIGTERM");
    await new Promise((resolve) => setTimeout(resolve, 800));
    await rm(dataRoot, { recursive: true, force: true });
  }
  return !failed;
}

function windowsPath(wslPath) {
  return wslPath.replace(/^\/mnt\/([a-z])\//, (_, drive) => `${drive.toUpperCase()}:\\`).replace(/\//g, "\\");
}

function powershell(script, options = {}) {
  // 中文系统上 PowerShell 默认按 GBK 写管道，Node 按 UTF-8 解码会得到乱码，
  // 报错信息会变得完全没法读；先把控制台输出编码钉成 UTF-8。
  const preamble = "[Console]::OutputEncoding = [Text.Encoding]::UTF8; ";
  return spawnSync("powershell.exe", ["-NoProfile", "-Command", preamble + script], {
    encoding: "utf8",
    ...options,
  });
}

/** 用 Windows 自带的 PowerShell 解析器静态检查脚本：只在真机上跑才发现的语法错，这里先拦住。 */
function assertPowerShellSyntax(directory) {
  const script = `
$root = '${windowsPath(directory)}'
$errors = 0
Get-ChildItem -LiteralPath $root -Filter *.ps1 | ForEach-Object {
  $parseErrors = @()
  [void][System.Management.Automation.Language.Parser]::ParseFile($_.FullName, [ref]$null, [ref]$parseErrors)
  if ($parseErrors.Count -gt 0) {
    Write-Output ("{0}: {1}" -f $_.Name, ($parseErrors[0].Message))
    $errors += $parseErrors.Count
  }
}
if ($errors -eq 0) { Write-Output 'OK' }
exit $errors
`;
  const result = powershell(script);
  if (result.status !== 0) throw new Error(`PowerShell 语法检查失败：\n${result.stdout}`);
  log("  Windows 脚本通过 PowerShell 解析器检查");
}

/**
 * 检查 install/uninstall 用到的 cmdlet 参数在真实 Windows 上都存在。
 *
 * 防火墙与计划任务只会在用户机器上真正执行，参数名写错（-LocalPorts、-RestartTimes 之类）
 * 在 Linux 上完全看不出来，装到最后一步才炸。这里只查参数表，不执行任何改动。
 */
function assertWindowsCmdletSurface() {
  const script = `
$ErrorActionPreference = 'Stop'
$checks = @(
  @{ Cmd = 'New-NetFirewallRule';      Params = @('Name','DisplayName','Direction','Action','Protocol','LocalPort','RemoteAddress','Profile','Enabled') },
  @{ Cmd = 'Remove-NetFirewallRule';   Params = @('Name') },
  @{ Cmd = 'Get-NetFirewallRule';      Params = @('Name','DisplayName') },
  @{ Cmd = 'Get-NetTCPConnection';     Params = @('LocalPort','State') },
  @{ Cmd = 'Get-NetRoute';             Params = @('DestinationPrefix') },
  @{ Cmd = 'Get-NetIPAddress';         Params = @('InterfaceIndex','AddressFamily') },
  @{ Cmd = 'New-ScheduledTaskAction';  Params = @('Execute','Argument') },
  @{ Cmd = 'New-ScheduledTaskTrigger'; Params = @('AtLogOn','User') },
  @{ Cmd = 'New-ScheduledTaskSettingsSet'; Params = @('AllowStartIfOnBatteries','DontStopIfGoingOnBatteries','StartWhenAvailable','MultipleInstances','RestartCount','RestartInterval','ExecutionTimeLimit') },
  @{ Cmd = 'New-ScheduledTaskPrincipal';   Params = @('UserId','LogonType','RunLevel') },
  @{ Cmd = 'Register-ScheduledTask';   Params = @('TaskName','Action','Trigger','Settings','Principal','Force') },
  @{ Cmd = 'Unregister-ScheduledTask'; Params = @('TaskName','Confirm') },
  @{ Cmd = 'Get-ScheduledTask';        Params = @('TaskName') },
  @{ Cmd = 'Get-ScheduledTaskInfo';    Params = @('TaskName') },
  @{ Cmd = 'Start-ScheduledTask';      Params = @('TaskName') },
  @{ Cmd = 'Wait-Process';             Params = @('Id') },
  @{ Cmd = 'Start-Process';            Params = @('FilePath','ArgumentList','WindowStyle','PassThru','RedirectStandardOutput','RedirectStandardError') }
)
$missing = @()
foreach ($check in $checks) {
  $command = Get-Command $check.Cmd -ErrorAction SilentlyContinue
  if (-not $command) { $missing += "$($check.Cmd)（命令不存在）"; continue }
  foreach ($name in $check.Params) {
    if (-not $command.Parameters.ContainsKey($name)) { $missing += "$($check.Cmd) -$name" }
  }
}
if ($missing.Count -gt 0) { Write-Output ('缺少参数：' + ($missing -join '、')); exit 1 }
Write-Output 'OK'
`;
  const result = powershell(script);
  if (result.status !== 0) throw new Error(`Windows 命令面检查失败：${result.stdout}`);
  log("  防火墙/计划任务 cmdlet 参数齐备");
}

async function verifyWindows({ skipBuild }) {
  if (!skipBuild) {
    log("构建 Windows 便携包（含下载便携 node.exe）…");
    const { packageAll } = await import("./package.mjs");
    await packageAll("win");
  }

  const archive = await findArchive("win-x64");
  log(`解压 ${archive}`);
  const pkgDir = await extract(archive, "win");

  const nodeExe = join(pkgDir, "node", "node.exe");
  if (!existsSync(nodeExe)) throw new Error("包里没有 node/node.exe");
  if ((await stat(nodeExe)).size < 10 * 1024 * 1024) throw new Error("node.exe 体积异常");

  // 复制到 Windows 临时目录再跑：真实 NTFS 上验收，避免 \\wsl$ 网络路径带来的假象
  const tempQuery = powershell("$env:TEMP");
  if (tempQuery.status !== 0) throw new Error("取不到 Windows TEMP 路径（WSL 互操作不可用？）");
  const winTemp = tempQuery.stdout.trim();
  const hostDirWsl = `${winTemp.replace(/^([A-Za-z]):\\/, (_, drive) => `/mnt/${drive.toLowerCase()}/`).replace(/\\/g, "/")}/lan-drop-verify`;
  const hostDataWsl = `${hostDirWsl}-data`;
  await rm(hostDirWsl, { recursive: true, force: true });
  await rm(hostDataWsl, { recursive: true, force: true });
  await cp(pkgDir, hostDirWsl, { recursive: true });
  // 数据目录必须先建：PowerShell 的 `*>` 重定向不会替你创建目录，
  // 目录不存在时整条启动命令会立刻失败（表现为「服务端没有任何日志」）。
  await mkdir(hostDataWsl, { recursive: true });
  log(`  已复制到 Windows：${windowsPath(hostDirWsl)}`);

  assertPowerShellSyntax(hostDirWsl);
  assertWindowsCmdletSurface();

  // 真正跑一遍 install.ps1 的「不碰系统」路径：语法解析过了不等于能跑（点源、StrictMode、
  // 中文输出编码、参数绑定都要真跑一次才知道）。防火墙/自启/启动全部关掉，数据目录也指向临时目录。
  const installDataDir = `${hostDirWsl}-install`;
  await rm(installDataDir, { recursive: true, force: true });
  const installRun = powershell(
    `& '${windowsPath(join(hostDirWsl, "install.ps1"))}' -NoFirewall -NoAutostart -NoStart ` +
      `-DataRoot '${windowsPath(installDataDir)}'`,
  );
  const installOutput = `${installRun.stdout}${installRun.stderr}`;
  const finished = installOutput.includes("安装完成");
  const needsAdmin = installOutput.includes("需要管理员权限");
  if (!finished && !needsAdmin) {
    throw new Error(`install.ps1 试跑既没走完也没给出提权提示（疑似崩溃）：\n${installOutput}`);
  }
  if (![0, 1].includes(installRun.status ?? -1)) {
    throw new Error(`install.ps1 试跑退出码异常：${installRun.status}\n${installOutput}`);
  }
  log(
    finished
      ? "  install.ps1 试跑走通（未触碰防火墙/自启/启动）"
      : "  install.ps1 试跑命中提权闸门并给出中文提示（当前 shell 非管理员）",
  );

  const baseUrl = `http://127.0.0.1:${VERIFY_PORT}`;
  const logFile = `${windowsPath(hostDataWsl)}\\verify.log`;
  const launchScript = `
$env:LAN_DROP_DATA_ROOT = '${windowsPath(hostDataWsl)}'
$env:LAN_DROP_HOST = '127.0.0.1'
$env:LAN_DROP_PORT = '${VERIFY_PORT}'
$env:LAN_DROP_DISCOVERY_PORT = '${VERIFY_DISCOVERY_PORT}'
$env:LAN_DROP_SERVER_NAME = 'verify-windows'
& '${windowsPath(join(hostDirWsl, "node", "node.exe"))}' '${windowsPath(join(hostDirWsl, "app", "server", "server.js"))}' *> '${logFile}'
`;
  log("启动 Windows 原生 node.exe + server.js（真实 Windows 进程）…");
  const server = spawn("powershell.exe", ["-NoProfile", "-Command", launchScript], {
    stdio: ["ignore", "ignore", "pipe"],
    detached: false,
  });
  let launcherError = "";
  server.stderr.on("data", (chunk) => { launcherError += chunk.toString("utf8"); });

  let failed = false;
  try {
    const info = await waitForServer(baseUrl);
    log(`  服务端就绪：${info.serverName}（WSL → Windows 回环可达）`);
    await assertWebAssets(baseUrl);
    failed = runSmoke(baseUrl) !== 0;
  } catch (error) {
    failed = true;
    console.error(`[verify] win 包验收失败：${error instanceof Error ? error.message : error}`);
    const captured = await readFile(`${hostDataWsl}/verify.log`, "utf8").catch(() => "(没有日志文件)");
    console.error(captured.split("\n").slice(-15).join("\n"));
    if (launcherError.trim().length > 0) {
      console.error(`--- 启动器输出 ---\n${launcherError.split("\n").slice(-10).join("\n")}`);
    }
  } finally {
    // 按命令行里的 server.js 路径精确结束（不要去动机器上别的 node 进程）
    const cleanup = powershell(`
Get-CimInstance Win32_Process -Filter "Name = 'node.exe'" |
  Where-Object { $_.CommandLine -like '*lan-drop-verify*server.js*' } |
  ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
`);
    if (cleanup.status !== 0) console.error("[verify] 清理 Windows 进程时出错（请手动检查 node.exe）");
    server.kill();
    await new Promise((resolve) => setTimeout(resolve, 500));
    await rm(hostDirWsl, { recursive: true, force: true });
    await rm(hostDataWsl, { recursive: true, force: true });
  }
  return !failed;
}

function parseArgs(argv) {
  const args = { target: "linux", skipBuild: false };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === "--target") args.target = argv[++i] ?? "linux";
    else if (arg === "--skip-build") args.skipBuild = true;
    else if (arg === "--help" || arg === "-h") args.help = true;
    else throw new Error(`未知参数：${arg}`);
  }
  return args;
}

const isMain =
  process.argv[1] !== undefined && import.meta.url === pathToFileURL(process.argv[1]).href;

if (isMain) {
  const args = parseArgs(process.argv.slice(2));
  if (args.help) {
    console.log("用法：node scripts/verify-package.mjs --target linux|win [--skip-build]");
    process.exit(0);
  }

  await rm(verifyDir, { recursive: true, force: true });

  const ok = args.target === "win" ? await verifyWindows(args) : await verifyLinux(args);
  console.log("");
  if (ok) {
    console.log(`[verify] ✅ ${args.target} 便携包验收通过（含 67 项 smoke）`);
  } else {
    console.error(`[verify] ❌ ${args.target} 便携包验收失败`);
    process.exitCode = 1;
  }
}
