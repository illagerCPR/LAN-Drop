import { homedir } from "node:os";
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
  /** 在设置页/二维码里展示的名字 */
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

export function loadConfig(env: NodeJS.ProcessEnv = process.env): ServerConfig {
  const dataRoot = env["LAN_DROP_DATA_ROOT"] ?? defaultDataRoot();

  return {
    host: env["LAN_DROP_HOST"] ?? "0.0.0.0",
    port: parseIntOr(env["LAN_DROP_PORT"], 8787),
    dataRoot,
    filesRoot: env["LAN_DROP_FILES_ROOT"] ?? join(dataRoot, "files"),
    serverName: env["LAN_DROP_SERVER_NAME"] ?? "LAN-Drop 服务端",
    pairingRequired: parseBoolOr(env["LAN_DROP_PAIRING_REQUIRED"], true),
    privateNetworkOnly: parseBoolOr(env["LAN_DROP_PRIVATE_ONLY"], true),
  };
}
