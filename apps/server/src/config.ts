import { homedir, hostname } from "node:os";
import { join } from "node:path";

/** 服务端运行配置。全部可由环境变量覆盖，便于打包分发与多实例调试。 */
export interface ServerConfig {
  /** 监听地址。默认 0.0.0.0 —— 局域网工具必须能被手机访问 */
  host: string;
  port: number;
  /** 数据根目录：SQLite、文件仓库、服务端身份都放这里 */
  dataRoot: string;
  /** 文件仓库目录 */
  filesRoot: string;
  /** 展示名：手机聊天页标题、Web 控制台标题都用它回答「我在跟哪台机器说话」 */
  serverName: string;
  /** 是否要求配对后才允许读写 */
  pairingRequired: boolean;
  /** 是否只接受私有网段来源（防止误暴露到公网） */
  privateNetworkOnly: boolean;
}

function defaultDataRoot(): string {
  if (process.platform === "win32") {
    const localAppData =
      process.env["LOCALAPPDATA"] ?? join(homedir(), "AppData", "Local");
    return join(localAppData, "LAN-Drop");
  }
  const xdgDataHome =
    process.env["XDG_DATA_HOME"] ?? join(homedir(), ".local", "share");
  return join(xdgDataHome, "lan-drop");
}

function parseIntOr(value: string | undefined, fallback: number): number {
  if (value === undefined) return fallback;
  const parsed = Number.parseInt(value, 10);
  return Number.isFinite(parsed) ? parsed : fallback;
}

function parseBoolOr(value: string | undefined, fallback: boolean): boolean {
  if (value === undefined) return fallback;
  return value === "1" || value.toLowerCase() === "true";
}

/**
 * 默认展示名取主机名，而不是写死的「LAN-Drop 服务端」。
 *
 * 这个名字会显示在**对方的**界面上（手机聊天页标题、Web 控制台标题），
 * 而那个位置唯一的职责就是回答「我在跟哪台机器说话」：
 *   - 「服务端」是实现术语，泄漏到了用户界面；
 *   - 多台 PC 时所有机器同名，等于没有名字；
 *   - 主机名天然唯一，用户也认得出是自己的哪台电脑。
 *
 * 想自定义就设 `LAN_DROP_SERVER_NAME`（标题、二维码、控制台一起变）。
 */
function defaultServerName(): string {
  const name = hostname().trim();
  return name.length > 0 ? name : "LAN-Drop";
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): ServerConfig {
  const dataRoot = env["LAN_DROP_DATA_ROOT"] ?? defaultDataRoot();

  return {
    host: env["LAN_DROP_HOST"] ?? "0.0.0.0",
    port: parseIntOr(env["LAN_DROP_PORT"], 8787),
    dataRoot,
    filesRoot: env["LAN_DROP_FILES_ROOT"] ?? join(dataRoot, "files"),
    serverName: env["LAN_DROP_SERVER_NAME"]?.trim() || defaultServerName(),
    pairingRequired: parseBoolOr(env["LAN_DROP_PAIRING_REQUIRED"], true),
    privateNetworkOnly: parseBoolOr(env["LAN_DROP_PRIVATE_ONLY"], true),
  };
}
