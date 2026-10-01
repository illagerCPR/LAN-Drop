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
  /** UDP 自动发现端口（客户端广播探测、服务端单播应答） */
  discoveryPort: number;
  /** 是否启用 UDP 自动发现 */
  discoveryEnabled: boolean;
  /**
   * 消息保留天数：超过该天数的最旧消息（按 seq 截断）随小时级清理定时器删除。
   * `0` 表示不按天数清理。
   */
  retentionDays: number;
  /**
   * 消息保留条数上限：只保留最新 N 条，更旧的按 seq 截断删除。
   * `0` 表示不按条数清理。
   */
  retentionMaxMessages: number;
  /** 清理定时器间隔（超时上传回收 + 保留策略都挂在这一个定时器上）。 */
  cleanupIntervalMs: number;
  /**
   * 磁盘预留空间（字节）：建上传会话时要求「剩余空间 ≥ 文件大小 + 该预留」，
   * 拒绝注定写不下的文件（507 disk_full），把失败提前到第一个请求。
   * 预留本身是给操作系统与数据库留的喘息空间，不让一次大文件把盘吃穿。
   * `LAN_DROP_RESERVE_BYTES` 覆盖；statfs 不可用时整个检查退化为跳过。
   */
  reserveBytes: number;
  /**
   * 是否启用自签 TLS（LAN 监听器 https/wss + 客户端 SPKI 指纹固定）。
   * 默认开启；`LAN_DROP_TLS=0` 退回明文（同网段抓包即得全部内容，不建议）。
   */
  tlsEnabled: boolean;
  /**
   * 回环明文监听端口（仅 127.0.0.1 可达）。TLS 开启时 LAN 端口是自签证书，
   * 本机浏览器访问要吃证书警告；控制台与配对码接口走这个明文回环端口，
   * 本机体验不受影响。`LAN_DROP_LOOPBACK_PORT` 覆盖。
   */
  loopbackPort: number;
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
    discoveryPort: parseIntOr(env["LAN_DROP_DISCOVERY_PORT"], 8788),
    discoveryEnabled: parseBoolOr(env["LAN_DROP_DISCOVERY"], true),
    retentionDays: parseIntOr(env["LAN_DROP_RETENTION_DAYS"], 0),
    retentionMaxMessages: parseIntOr(env["LAN_DROP_RETENTION_MAX"], 0),
    cleanupIntervalMs: Math.max(1000, parseIntOr(env["LAN_DROP_CLEANUP_INTERVAL_MS"], 3_600_000)),
    reserveBytes: parseIntOr(env["LAN_DROP_RESERVE_BYTES"], 64 * 1024 * 1024),
    tlsEnabled: parseBoolOr(env["LAN_DROP_TLS"], true),
    loopbackPort: parseIntOr(env["LAN_DROP_LOOPBACK_PORT"], 8789),
  };
}
