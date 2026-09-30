/**
 * 服务端内部类型（数据库行 / 领域记录）。
 *
 * 与 `@lan-drop/protocol` 的区别：
 *   - protocol 定义的是**线上格式**（DTO），前后端与 Android 共用；
 *   - 本文件定义的是**服务端内部表示**，可以包含不该外泄的字段（如 token 哈希、临时路径）。
 *   路由层负责在两者之间做映射，绝不把内部记录直接当响应体返回。
 */

import type { MessageKind, UploadState } from "@lan-drop/protocol";

/** 上传会话状态就是协议里那个（这里转出去，内部代码不必再引协议包）。 */
export type { UploadState };

export interface DeviceRow {
  id: string;
  name: string;
  platform: string;
  /** 只存 token 的 sha256，原文仅在配对响应中出现一次 */
  tokenHash: string;
  createdAt: number;
  lastSeenAt: number | null;
}

export interface FileRecord {
  id: string;
  name: string;
  size: number;
  mime: string | null;
  sha256: string | null;
  /** 相对 filesRoot 的路径，避免把绝对路径写死进库（换机器/换目录后仍可用） */
  relPath: string;
  createdAt: number;
}

/** 消息 + 其关联文件信息（LEFT JOIN 后的扁平结果）。 */
export interface MessageRecord {
  seq: number;
  id: string;
  kind: MessageKind;
  text: string | null;
  fileId: string | null;
  senderId: string;
  senderName: string;
  createdAt: number;
  deletedAt: number | null;
  file: {
    id: string;
    name: string;
    size: number;
    mime: string | null;
    sha256: string | null;
  } | null;
}

export interface UploadRecord {
  id: string;
  deviceId: string;
  name: string;
  size: number;
  mime: string | null;
  sha256: string | null;
  /** 分片写入的临时文件位置 */
  tempPath: string;
  receivedBytes: number;
  state: UploadState;
  createdAt: number;
  updatedAt: number;
}
