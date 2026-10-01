import { mkdirSync } from "node:fs";
import { dirname } from "node:path";
import { randomUUID, createHash, randomBytes } from "node:crypto";
import { DatabaseSync, type StatementSync } from "node:sqlite";

import type {
  DeviceRow,
  FileRecord,
  MessageRecord,
  UploadRecord,
  UploadState,
} from "./types.ts";
import type { MessageKind } from "@lan-drop/protocol";

/** 当前 schema 版本。改动表结构时递增，并在 migrate() 中补迁移分支。 */
const SCHEMA_VERSION = 1;

/**
 * 消息查询的公共 SELECT。
 *
 * 列表与单条回读共用同一段 SQL，是为了避免「两处映射逻辑各写一遍、
 * 改了一处忘了另一处」——文件消息的 file 字段就靠这个 LEFT JOIN 带出来。
 */
const MESSAGE_SELECT = `
  SELECT m.seq, m.id, m.kind, m.text, m.file_id, m.sender_id, m.sender_name,
         m.created_at, m.deleted_at,
         f.name AS f_name, f.size AS f_size, f.mime AS f_mime, f.sha256 AS f_sha256
  FROM messages m
  LEFT JOIN files f ON f.id = m.file_id
`;

/**
 * SQLite 存储层。
 *
 * 用 Node 24 内置的 `node:sqlite`，不引入任何原生依赖——这对「PC 端可能是 Windows，
 * 且要能打包成单文件分发」的场景很重要：better-sqlite3 之类需要预编译二进制。
 *
 * 并发假设：局域网工具，写入频率低；开启 WAL 后读不阻塞写，足够。
 */
export class Store {
  readonly #db: DatabaseSync;
  readonly #stmts = new Map<string, StatementSync>();

  constructor(dbPath: string) {
    mkdirSync(dirname(dbPath), { recursive: true });
    this.#db = new DatabaseSync(dbPath);

    // WAL：读写并发更友好；foreign_keys 需显式开启（SQLite 默认关闭）
    this.#db.exec("PRAGMA journal_mode = WAL");
    this.#db.exec("PRAGMA foreign_keys = ON");
    this.#db.exec("PRAGMA busy_timeout = 5000");
    this.#db.exec("PRAGMA synchronous = NORMAL");

    this.#migrate();
  }

  /** 预热并复用语句对象，避免每次调用重新编译 SQL。 */
  #stmt(sql: string): StatementSync {
    let cached = this.#stmts.get(sql);
    if (!cached) {
      cached = this.#db.prepare(sql);
      this.#stmts.set(sql, cached);
    }
    return cached;
  }

  #migrate(): void {
    this.#db.exec(`
      CREATE TABLE IF NOT EXISTS meta (
        key   TEXT PRIMARY KEY,
        value TEXT NOT NULL
      );

      CREATE TABLE IF NOT EXISTS devices (
        id           TEXT PRIMARY KEY,
        name         TEXT NOT NULL,
        platform     TEXT NOT NULL,
        token_hash   TEXT NOT NULL,
        created_at   INTEGER NOT NULL,
        last_seen_at INTEGER
      );

      CREATE TABLE IF NOT EXISTS files (
        id         TEXT PRIMARY KEY,
        name       TEXT NOT NULL,
        size       INTEGER NOT NULL,
        mime       TEXT,
        sha256     TEXT,
        rel_path   TEXT NOT NULL,
        created_at INTEGER NOT NULL
      );

      CREATE TABLE IF NOT EXISTS messages (
        seq         INTEGER PRIMARY KEY AUTOINCREMENT,
        id          TEXT NOT NULL UNIQUE,
        kind        TEXT NOT NULL,
        text        TEXT,
        file_id     TEXT REFERENCES files(id),
        sender_id   TEXT NOT NULL,
        sender_name TEXT NOT NULL,
        created_at  INTEGER NOT NULL,
        deleted_at  INTEGER
      );

      CREATE INDEX IF NOT EXISTS idx_messages_created_at ON messages(created_at);

      CREATE TABLE IF NOT EXISTS uploads (
        id             TEXT PRIMARY KEY,
        device_id      TEXT NOT NULL,
        name           TEXT NOT NULL,
        size           INTEGER NOT NULL,
        mime           TEXT,
        sha256         TEXT,
        temp_path      TEXT NOT NULL,
        received_bytes INTEGER NOT NULL DEFAULT 0,
        state          TEXT NOT NULL,
        created_at     INTEGER NOT NULL,
        updated_at     INTEGER NOT NULL
      );

      CREATE INDEX IF NOT EXISTS idx_uploads_state ON uploads(state);
    `);

    const row = this.#stmt("SELECT value FROM meta WHERE key = 'schema_version'").get() as
      | { value: string }
      | undefined;

    if (!row) {
      this.#db
        .prepare("INSERT INTO meta (key, value) VALUES ('schema_version', ?)")
        .run(String(SCHEMA_VERSION));
      return;
    }

    const current = Number.parseInt(row.value, 10);
    if (current < SCHEMA_VERSION) {
      // 目前只有 v1。后续版本在此按序补 ALTER TABLE / 数据搬迁。
      this.#db
        .prepare("UPDATE meta SET value = ? WHERE key = 'schema_version'")
        .run(String(SCHEMA_VERSION));
    }
  }

  // ------------------------------------------------------------ 设备与鉴权

  createDevice(name: string, platform: string, tokenHash: string): DeviceRow {
    const device: DeviceRow = {
      id: randomUUID(),
      name,
      platform,
      tokenHash,
      createdAt: Date.now(),
      lastSeenAt: null,
    };

    this.#stmt(
      `INSERT INTO devices (id, name, platform, token_hash, created_at, last_seen_at)
       VALUES (?, ?, ?, ?, ?, NULL)`,
    ).run(device.id, device.name, device.platform, device.tokenHash, device.createdAt);

    return device;
  }

  findDeviceByTokenHash(tokenHash: string): DeviceRow | null {
    const row = this.#stmt(
      `SELECT id, name, platform, token_hash, created_at, last_seen_at
       FROM devices WHERE token_hash = ?`,
    ).get(tokenHash) as Record<string, unknown> | undefined;

    return row ? mapDevice(row) : null;
  }

  findDeviceById(id: string): DeviceRow | null {
    const row = this.#stmt(
      `SELECT id, name, platform, token_hash, created_at, last_seen_at
       FROM devices WHERE id = ?`,
    ).get(id) as Record<string, unknown> | undefined;

    return row ? mapDevice(row) : null;
  }

  listDevices(): DeviceRow[] {
    const rows = this.#stmt(
      `SELECT id, name, platform, token_hash, created_at, last_seen_at
       FROM devices ORDER BY created_at ASC`,
    ).all() as Record<string, unknown>[];

    return rows.map(mapDevice);
  }

  touchDevice(id: string): void {
    this.#stmt("UPDATE devices SET last_seen_at = ? WHERE id = ?").run(Date.now(), id);
  }

  /**
   * 撤销设备（删除行）。token 的 sha256 随行而去，该设备的全部凭据立即失效。
   *
   * 注意 uploads.device_id **没有外键**，删设备行不会级联也不会报错——该设备的
   * 上传会话必须由调用方先回收（临时文件 + 会话行），否则留下指向已删除设备的
   * 孤儿会话，24 小时后才会被清理定时器捞走。
   */
  deleteDevice(id: string): boolean {
    const info = this.#stmt("DELETE FROM devices WHERE id = ?").run(id);
    return toNumber(info.changes) > 0;
  }

  // ------------------------------------------------------------ 消息

  /** 按 seq 回读单条完整消息（含文件信息）。 */
  findMessageBySeq(seq: number): MessageRecord | null {
    const row = this.#stmt(`${MESSAGE_SELECT} WHERE m.seq = ?`).get(seq) as
      | Record<string, unknown>
      | undefined;

    return row ? mapMessage(row) : null;
  }

  /** 追加一条文字/链接消息，返回带权威 seq 的完整记录。 */
  appendTextMessage(input: {
    senderId: string;
    senderName: string;
    kind: MessageKind;
    text: string;
    createdAt?: number;
  }): MessageRecord {
    const id = randomUUID();
    const createdAt = input.createdAt ?? Date.now();

    const info = this.#stmt(
      `INSERT INTO messages (id, kind, text, file_id, sender_id, sender_name, created_at, deleted_at)
       VALUES (?, ?, ?, NULL, ?, ?, ?, NULL)`,
    ).run(id, input.kind, input.text, input.senderId, input.senderName, createdAt);

    return this.#requireMessage(toNumber(info.lastInsertRowid));
  }

  /** 追加一条文件消息；调用前必须已写入 files 行（外键约束）。 */
  appendFileMessage(input: {
    senderId: string;
    senderName: string;
    fileId: string;
    createdAt?: number;
  }): MessageRecord {
    const id = randomUUID();
    const createdAt = input.createdAt ?? Date.now();

    const info = this.#stmt(
      `INSERT INTO messages (id, kind, text, file_id, sender_id, sender_name, created_at, deleted_at)
       VALUES (?, 'file', NULL, ?, ?, ?, ?, NULL)`,
    ).run(id, input.fileId, input.senderId, input.senderName, createdAt);

    return this.#requireMessage(toNumber(info.lastInsertRowid));
  }

  /**
   * 插入后统一回读，而不是就地拼一个返回对象。
   *
   * 拼对象看着更快，但会漏掉 JOIN 出来的关联信息——文件消息的 file 字段
   * 就是这么丢的（实测踩到：完成上传后返回的 DTO 里没有 file，客户端拿不到 fileId）。
   */
  #requireMessage(seq: number): MessageRecord {
    const record = this.findMessageBySeq(seq);
    if (record) return record;
    throw new Error(`消息回读失败：seq=${seq}`);
  }

  /**
   * 增量拉取：seq 严格大于游标的、未删除的消息，按 seq 升序。
   * `limit + 1` 用于判断是否还有更多，避免额外一次 count 查询。
   */
  listMessagesSince(since: number, limit: number): { items: MessageRecord[]; hasMore: boolean } {
    const rows = this.#stmt(
      `${MESSAGE_SELECT}
       WHERE m.seq > ? AND m.deleted_at IS NULL
       ORDER BY m.seq ASC
       LIMIT ?`,
    ).all(since, limit + 1) as Record<string, unknown>[];

    const hasMore = rows.length > limit;
    const items = (hasMore ? rows.slice(0, limit) : rows).map(mapMessage);
    return { items, hasMore };
  }

  latestSeq(): number {
    const row = this.#stmt("SELECT COALESCE(MAX(seq), 0) AS seq FROM messages").get() as
      | { seq: number }
      | undefined;
    return row ? toNumber(row.seq) : 0;
  }

  /** 保留策略的历史累计水位：`seq <= purgedUpto` 的消息已从服务端删除。 */
  purgedUpto(): number {
    return Number.parseInt(this.getMeta("purged_upto") ?? "0", 10) || 0;
  }

  /** 清空全部消息（保留文件实体，避免误删用户已落盘的数据）。 */
  clearMessages(): number {
    const info = this.#stmt("DELETE FROM messages").run();
    return toNumber(info.changes);
  }

  /**
   * 按保留策略清除旧消息（P4-5），返回被删文件的记录供调用方清理磁盘。
   *
   * 「天数」与「条数」两个阈值各自换算成一个 seq 边界，取较大者做**严格前缀删除**
   * （`seq <= uptoSeq`）：天数边界 = 最旧的过期消息的 seq；条数边界 = 保留最新
   * `keepCount` 条后、被删部分的最大 seq。按前缀删而不是按 `created_at` 逐行删，
   * 是为了让「服务端删了什么」与客户端能理解的「删到 seq X 为止」是同一个集合——
   * 客户端收到 `uptoSeq` 后删本地 `seq <= uptoSeq` 即可与服务端精确一致。
   * seq 按落库顺序分配，created_at 与 seq 的次序在真实流里一致；即便被显式传入的
   * createdAt 打乱，前缀语义也只是「多留几条较老的消息」，不会误删。
   *
   * 被删消息引用的文件行一并删除（多条消息引用同一文件时以留存者为准）；
   * 磁盘字节由调用方按返回的 `relPath` 清理，删除失败不影响数据库一致性。
   * 两个阈值都为空（<=0）时不产生任何写操作。
   */
  purgeExpiredMessages(options: {
    olderThanMs: number | null;
    keepCount: number | null;
  }): { purged: number; uptoSeq: number; files: FileRecord[] } {
    const olderThanMs = options.olderThanMs && options.olderThanMs > 0 ? options.olderThanMs : null;
    const keepCount = options.keepCount && options.keepCount > 0 ? options.keepCount : null;
    if (olderThanMs === null && keepCount === null) {
      return { purged: 0, uptoSeq: 0, files: [] };
    }

    let daysBoundary = 0;
    if (olderThanMs !== null) {
      const cutoff = Date.now() - olderThanMs;
      const row = this.#stmt(
        "SELECT COALESCE(MAX(seq), 0) AS seq FROM messages WHERE created_at < ?",
      ).get(cutoff) as { seq: number | bigint } | undefined;
      daysBoundary = row ? toNumber(row.seq) : 0;
    }

    let countBoundary = 0;
    if (keepCount !== null) {
      // 最新 keep 条里最旧那条之上全删；总数不足 keep 时边界落在 0，不删
      const row = this.#stmt(
        "SELECT COALESCE(MIN(seq), 0) AS seq FROM (SELECT seq FROM messages ORDER BY seq DESC LIMIT ?)",
      ).get(keepCount) as { seq: number | bigint } | undefined;
      countBoundary = row ? Math.max(0, toNumber(row.seq) - 1) : 0;
    }

    const uptoSeq = Math.max(daysBoundary, countBoundary);
    if (uptoSeq <= 0) {
      return { purged: 0, uptoSeq: 0, files: [] };
    }

    this.#db.exec("BEGIN IMMEDIATE");
    try {
      const doomedFiles = (
        this.#stmt(
          `SELECT f.id, f.name, f.size, f.mime, f.sha256, f.rel_path, f.created_at
           FROM messages m JOIN files f ON f.id = m.file_id
           WHERE m.seq <= ?`,
        ).all(uptoSeq) as Record<string, unknown>[]
      ).map(mapFile);

      const purged = toNumber(
        this.#stmt("DELETE FROM messages WHERE seq <= ?").run(uptoSeq).changes,
      );

      // 此刻留在 messages 表里的引用全部来自「留存的消息」；
      // 被删文件里只有不再被任何留存消息引用的才真删
      const doomedIds = doomedFiles.map((file) => file.id);
      const retained = new Set<string>(
        doomedIds.length === 0
          ? []
          : (
              this.#stmt(
                `SELECT DISTINCT file_id FROM messages WHERE file_id IN (${doomedIds.map(() => "?").join(", ")})`,
              ).all(...doomedIds) as Record<string, unknown>[]
            ).map((row) => String(row["file_id"])),
      );
      const removedFiles = doomedFiles.filter((file) => !retained.has(file.id));
      if (removedFiles.length > 0) {
        const ids = removedFiles.map((file) => file.id);
        this.#stmt(
          `DELETE FROM files WHERE id IN (${ids.map(() => "?").join(", ")})`,
        ).run(...ids);
      }

      this.#db.exec("COMMIT");
      // 水位只进不退：双阈值之外再来一次更小的清理也不该回拨
      const WATERMARK_KEY = "purged_upto";
      const previous = Number.parseInt(this.getMeta(WATERMARK_KEY) ?? "0", 10) || 0;
      if (uptoSeq > previous) {
        this.setMeta(WATERMARK_KEY, String(uptoSeq));
      }
      return { purged, uptoSeq, files: removedFiles };
    } catch (error) {
      this.#db.exec("ROLLBACK");
      throw error;
    }
  }

  // ------------------------------------------------------------ 文件

  createFile(input: {
    id?: string;
    name: string;
    size: number;
    mime: string | null;
    sha256: string | null;
    relPath: string;
  }): FileRecord {
    const file: FileRecord = {
      id: input.id ?? randomUUID(),
      name: input.name,
      size: input.size,
      mime: input.mime,
      sha256: input.sha256,
      relPath: input.relPath,
      createdAt: Date.now(),
    };

    this.#stmt(
      `INSERT INTO files (id, name, size, mime, sha256, rel_path, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?)`,
    ).run(file.id, file.name, file.size, file.mime, file.sha256, file.relPath, file.createdAt);

    return file;
  }

  findFile(id: string): FileRecord | null {
    const row = this.#stmt(
      `SELECT id, name, size, mime, sha256, rel_path, created_at FROM files WHERE id = ?`,
    ).get(id) as Record<string, unknown> | undefined;

    return row ? mapFile(row) : null;
  }

  // ------------------------------------------------------------ 上传会话

  createUpload(input: {
    deviceId: string;
    name: string;
    size: number;
    mime: string | null;
    sha256: string | null;
    tempPath: string;
  }): UploadRecord {
    const now = Date.now();
    const upload: UploadRecord = {
      id: randomUUID(),
      deviceId: input.deviceId,
      name: input.name,
      size: input.size,
      mime: input.mime,
      sha256: input.sha256,
      tempPath: input.tempPath,
      receivedBytes: 0,
      state: "open",
      createdAt: now,
      updatedAt: now,
    };

    this.#stmt(
      `INSERT INTO uploads (id, device_id, name, size, mime, sha256, temp_path,
                            received_bytes, state, created_at, updated_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, 0, 'open', ?, ?)`,
    ).run(
      upload.id,
      upload.deviceId,
      upload.name,
      upload.size,
      upload.mime,
      upload.sha256,
      upload.tempPath,
      upload.createdAt,
      upload.updatedAt,
    );

    return upload;
  }

  findUpload(id: string): UploadRecord | null {
    const row = this.#stmt(
      `SELECT id, device_id, name, size, mime, sha256, temp_path, received_bytes,
              state, created_at, updated_at
       FROM uploads WHERE id = ?`,
    ).get(id) as Record<string, unknown> | undefined;

    return row ? mapUpload(row) : null;
  }

  addUploadBytes(id: string, delta: number): number {
    this.#stmt(
      `UPDATE uploads SET received_bytes = received_bytes + ?, updated_at = ? WHERE id = ?`,
    ).run(delta, Date.now(), id);

    const row = this.#stmt("SELECT received_bytes FROM uploads WHERE id = ?").get(id) as
      | { received_bytes: number }
      | undefined;

    return row ? toNumber(row.received_bytes) : 0;
  }

  setUploadState(id: string, state: UploadRecord["state"]): void {
    this.#stmt("UPDATE uploads SET state = ?, updated_at = ? WHERE id = ?").run(
      state,
      Date.now(),
      id,
    );
  }

  /**
   * 列出某设备的上传会话。
   *
   * 只按设备过滤、不跨设备——上传会话里带文件名，A 设备没有理由看到 B 设备
   * 正在传什么。
   */
  listUploadsByDevice(deviceId: string, state: UploadState | null, limit: number): UploadRecord[] {
    const sql = `SELECT id, device_id, name, size, mime, sha256, temp_path, received_bytes,
                        state, created_at, updated_at
                 FROM uploads
                 WHERE device_id = ?${state ? " AND state = ?" : ""}
                 ORDER BY updated_at DESC
                 LIMIT ?`;

    const rows = this.#stmt(sql).all(
      ...(state ? [deviceId, state, limit] : [deviceId, limit]),
    ) as Record<string, unknown>[];

    return rows.map(mapUpload);
  }

  /** 清理超时未完成的上传会话，返回被清理的临时文件路径。 */
  listStaleUploads(olderThanMs: number): UploadRecord[] {
    const cutoff = Date.now() - olderThanMs;
    const rows = this.#stmt(
      `SELECT id, device_id, name, size, mime, sha256, temp_path, received_bytes,
              state, created_at, updated_at
       FROM uploads WHERE state = 'open' AND updated_at < ?`,
    ).all(cutoff) as Record<string, unknown>[];

    return rows.map(mapUpload);
  }

  deleteUpload(id: string): void {
    this.#stmt("DELETE FROM uploads WHERE id = ?").run(id);
  }

  /** 删除某设备的全部上传会话行（撤销设备时随行清理），返回删除数。 */
  deleteUploadsByDevice(deviceId: string): number {
    const info = this.#stmt("DELETE FROM uploads WHERE device_id = ?").run(deviceId);
    return toNumber(info.changes);
  }

  // ------------------------------------------------------------ 元信息

  getMeta(key: string): string | null {
    const row = this.#stmt("SELECT value FROM meta WHERE key = ?").get(key) as
      | { value: string }
      | undefined;
    return row ? row.value : null;
  }

  setMeta(key: string, value: string): void {
    this.#stmt(
      `INSERT INTO meta (key, value) VALUES (?, ?)
       ON CONFLICT(key) DO UPDATE SET value = excluded.value`,
    ).run(key, value);
  }

  close(): void {
    this.#stmts.clear();
    this.#db.close();
  }
}

// ---------------------------------------------------------------- 工具

/** node:sqlite 在数值超出安全整数范围时会返回 BigInt，统一收敛为 number。 */
function toNumber(value: number | bigint): number {
  return typeof value === "bigint" ? Number(value) : value;
}

function mapDevice(row: Record<string, unknown>): DeviceRow {
  return {
    id: String(row["id"]),
    name: String(row["name"]),
    platform: String(row["platform"]),
    tokenHash: String(row["token_hash"]),
    createdAt: toNumber(row["created_at"] as number),
    lastSeenAt: row["last_seen_at"] === null ? null : toNumber(row["last_seen_at"] as number),
  };
}

function mapMessage(row: Record<string, unknown>): MessageRecord {
  return {
    seq: toNumber(row["seq"] as number),
    id: String(row["id"]),
    kind: String(row["kind"]) as MessageKind,
    text: row["text"] === null ? null : String(row["text"]),
    fileId: row["file_id"] === null ? null : String(row["file_id"]),
    senderId: String(row["sender_id"]),
    senderName: String(row["sender_name"]),
    createdAt: toNumber(row["created_at"] as number),
    deletedAt: row["deleted_at"] === null ? null : toNumber(row["deleted_at"] as number),
    file: row["f_name"] === null || row["f_name"] === undefined
      ? null
      : {
          id: String(row["file_id"]),
          name: String(row["f_name"]),
          size: toNumber(row["f_size"] as number),
          mime: row["f_mime"] === null ? null : String(row["f_mime"]),
          sha256: row["f_sha256"] === null ? null : String(row["f_sha256"]),
        },
  };
}

function mapFile(row: Record<string, unknown>): FileRecord {
  return {
    id: String(row["id"]),
    name: String(row["name"]),
    size: toNumber(row["size"] as number),
    mime: row["mime"] === null ? null : String(row["mime"]),
    sha256: row["sha256"] === null ? null : String(row["sha256"]),
    relPath: String(row["rel_path"]),
    createdAt: toNumber(row["created_at"] as number),
  };
}

function mapUpload(row: Record<string, unknown>): UploadRecord {
  return {
    id: String(row["id"]),
    deviceId: String(row["device_id"]),
    name: String(row["name"]),
    size: toNumber(row["size"] as number),
    mime: row["mime"] === null ? null : String(row["mime"]),
    sha256: row["sha256"] === null ? null : String(row["sha256"]),
    tempPath: String(row["temp_path"]),
    receivedBytes: toNumber(row["received_bytes"] as number),
    state: String(row["state"]) as UploadRecord["state"],
    createdAt: toNumber(row["created_at"] as number),
    updatedAt: toNumber(row["updated_at"] as number),
  };
}

// ---------------------------------------------------------------- 工具导出

export function hashToken(token: string): string {
  return createHash("sha256").update(token).digest("hex");
}

export function generateToken(): string {
  return randomBytes(32).toString("base64url");
}

/** 去掉容易看错的字符（0/O、1/I/L），便于人工输入配对码。 */
const PAIRING_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

export function generatePairingCode(length = 6): string {
  const bytes = randomBytes(length);
  let code = "";
  for (let i = 0; i < length; i += 1) {
    code += PAIRING_ALPHABET[bytes[i]! % PAIRING_ALPHABET.length];
  }
  return code;
}
