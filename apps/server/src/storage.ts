import { createHash } from "node:crypto";
import { createReadStream, createWriteStream } from "node:fs";
import { mkdir, rename, stat, statfs, truncate, unlink, writeFile } from "node:fs/promises";
import { extname, join } from "node:path";
import { Transform, type Readable } from "node:stream";
import { pipeline } from "node:stream/promises";

/** Windows 文件名非法字符 + 路径分隔符 + 控制字符。 */
const ILLEGAL_CHARS = /[<>:"/\\|?*\u0000-\u001F]/g;
/** Windows 保留设备名（不区分大小写、可带扩展名）。 */
const WINDOWS_RESERVED =
  /^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\..*)?$/i;

const MAX_NAME_LENGTH = 150;

/**
 * 清洗上传的文件名。
 *
 * 不能直接信任客户端传来的名字：它会被拼进磁盘路径，
 * 一旦含 `../` 或绝对路径就是目录穿越。这里只取基名并剔除危险字符，
 * 同时兼顾 Windows 的非法字符与保留设备名（PC 端可能是 Windows）。
 */
export function sanitizeFileName(raw: string): string {
  // 先按两种分隔符切，取最后一段，杜绝 ../ 与 C:\ 之类
  const base = raw.split(/[/\\]/).pop() ?? "";
  let name = base.replace(ILLEGAL_CHARS, "_").replace(/\s+/g, " ").trim();

  // Windows 不允许文件名以点或空格结尾
  name = name.replace(/[. ]+$/, "");

  if (name.length > MAX_NAME_LENGTH) {
    const ext = extname(name).slice(0, 16);
    name = name.slice(0, MAX_NAME_LENGTH - ext.length) + ext;
  }

  if (name.length === 0 || name === "." || name === "..") {
    name = "unnamed";
  }

  if (WINDOWS_RESERVED.test(name)) {
    name = `_${name}`;
  }

  return name;
}

/** 相对 filesRoot 的落盘路径：按年月分目录，避免单目录堆几万个文件。 */
export function relativeStoragePath(fileId: string, safeName: string, now = new Date()): string {
  const year = String(now.getFullYear());
  const month = String(now.getMonth() + 1).padStart(2, "0");
  // 前缀用 fileId，保证同名文件不互相覆盖
  return join(year, month, `${fileId}_${safeName}`);
}

/** 流式计算文件 sha256（一次性顺序读，不占内存）。 */
export async function sha256File(absPath: string): Promise<string> {
  const hash = createHash("sha256");
  await pipeline(createReadStream(absPath), hash);
  return hash.digest("hex");
}

/**
 * 把文件截断到指定长度；文件不存在时视为「已经是空文件」，静默通过。
 *
 * 断点续传的安全绳，见 [appendStreamToFile] 的说明。**必须在每次追加前调用**，
 * 否则一次中途断流就会永久写坏这个文件。
 */
export async function truncateTo(absPath: string, size: number): Promise<void> {
  try {
    await truncate(absPath, size);
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") return;
    throw error;
  }
}

export interface StreamToFileResult {
  bytesWritten: number;
  /** 超出上限被截断时为 true */
  overflowed: boolean;
}

/**
 * 把请求体流式追加写入文件，同时统计字节数。
 *
 * 关键点：全程不把 body 读进内存——1 GB 的文件在 512 MB 内存的机器上也要能传。
 * `limit` 是本次允许写入的最大字节数（= 文件总大小 - 已接收字节数），
 * 客户端多发了就立刻中断，避免被塞爆磁盘。
 *
 * ⚠️ 调用方必须先用 [truncateTo] 把文件截断到本次的起始 offset。
 * 本函数是纯追加（`flags: "a"`），而请求中途断流时——客户端被杀、WiFi 掉线、
 * 服务端 abort——`pipeline` 抛错返回，但**已经落盘的那部分字节留在文件里**，
 * 而数据库的 `received_bytes` 仍是这一片之前的旧值。两者一旦不一致，
 * 下次从「权威 offset」续传就会把新数据追加在残字节之后，文件从此永久错位，
 * 而且 sha256 要到全部传完才会发现不符（100 GB 的文件就是白传一遍）。
 * 截断到 offset 把这部分残字节丢掉（代价是重传不到一个分片），换来一条铁律：
 * **追加开始时，文件长度恒等于本次起始 offset**。
 */
export async function appendStreamToFile(
  source: Readable,
  absPath: string,
  limit: number,
): Promise<StreamToFileResult> {
  let bytesWritten = 0;
  let overflowed = false;

  const counter = new Transform({
    transform(chunk: Buffer, _encoding, callback) {
      bytesWritten += chunk.length;
      if (bytesWritten > limit) {
        overflowed = true;
        callback(new Error("upload_exceeds_declared_size"));
        return;
      }
      callback(null, chunk);
    },
  });

  try {
    await pipeline(source, counter, createWriteStream(absPath, { flags: "a" }));
  } catch (error) {
    if (overflowed) {
      return { bytesWritten, overflowed: true };
    }
    throw error;
  }

  return { bytesWritten, overflowed: false };
}

/**
 * 查询目录所在文件系统的「非特权用户可用」剩余字节数（`statfs` 的 `bavail × bsize`）。
 *
 * 返回 null 表示查询不可用（平台不支持、目录不存在等）——调用方必须把 null
 * 当作「检查跳过」而不是「空间为零」：磁盘满保护是尽力而为的提前拦截，
 * 不能因为某个平台缺 statfs 就拒绝一切上传。
 */
export async function freeDiskBytes(dir: string): Promise<number | null> {
  try {
    const stats = await statfs(dir);
    return stats.bavail * stats.bsize;
  } catch {
    return null;
  }
}

/** 判定一个写盘错误是不是磁盘满。ENOSPC 是唯一确定信号，其余按原错误向上抛。 */
export function isDiskFullError(error: unknown): boolean {
  return (error as NodeJS.ErrnoException | null)?.code === "ENOSPC";
}

/** 确保目录存在（递归）。 */
export async function ensureDir(dir: string): Promise<void> {
  await mkdir(dir, { recursive: true });
}

/**
 * 落一个空文件。
 *
 * 0 字节上传没有任何分片请求（追加接口对 remaining<=0 一律 409），临时文件
 * 必须在会话创建时就存在——否则 complete 在 sha256File 的 ENOENT 上炸成 500
 * （实测踩过）。空文件是合法文件，不是异常。
 */
export async function writeEmptyFile(absPath: string): Promise<void> {
  await writeFile(absPath, new Uint8Array(0));
}

/** 把临时文件搬到最终位置；跨目录同盘时 rename 是原子操作。 */
export async function moveIntoPlace(from: string, to: string): Promise<void> {
  await ensureDir(join(to, ".."));
  await rename(from, to);
}

export async function removeFileQuietly(absPath: string): Promise<void> {
  try {
    await unlink(absPath);
  } catch {
    // 文件本就可能不存在，清理失败不应影响主流程
  }
}

export async function fileExists(absPath: string): Promise<boolean> {
  try {
    await stat(absPath);
    return true;
  } catch {
    return false;
  }
}

export interface ByteRange {
  start: number;
  end: number;
}

/**
 * 解析 `Range: bytes=...` 请求头。
 *
 * 支持三种形式：`bytes=0-499`、`bytes=500-`、`bytes=-500`（最后 500 字节）。
 * 不合法或越界返回 null，调用方按 416 处理。
 */
export function parseRange(header: string | undefined, size: number): ByteRange | null {
  if (!header) return null;

  const match = /^bytes=(\d*)-(\d*)$/.exec(header.trim());
  if (!match) return null;

  const [, rawStart, rawEnd] = match;
  if (rawStart === "" && rawEnd === "") return null;

  let start: number;
  let end: number;

  if (rawStart === "") {
    // bytes=-N：最后 N 字节
    const suffixLength = Number.parseInt(rawEnd!, 10);
    if (!Number.isFinite(suffixLength) || suffixLength <= 0) return null;
    start = Math.max(0, size - suffixLength);
    end = size - 1;
  } else {
    start = Number.parseInt(rawStart!, 10);
    end = rawEnd === "" ? size - 1 : Number.parseInt(rawEnd!, 10);
  }

  if (!Number.isFinite(start) || !Number.isFinite(end)) return null;
  if (start > end || start >= size) return null;

  return { start, end: Math.min(end, size - 1) };
}

/**
 * 构造 Content-Disposition，兼顾中文/特殊字符文件名。
 *
 * 同时给出 ASCII 回退名与 RFC 5987 的 `filename*`：
 * 老客户端用前者（可能不精确），现代浏览器优先用后者（正确的 UTF-8 名）。
 */
export function contentDisposition(name: string): string {
  const asciiFallback = name.replace(/[^\x20-\x7E]/g, "_").replace(/["\\]/g, "_");
  return `attachment; filename="${asciiFallback}"; filename*=UTF-8''${encodeURIComponent(name)}`;
}
