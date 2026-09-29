import {
  ApiPath,
  type CreateUploadRequest,
  type CreateUploadResponse,
  type MessageDto,
  type MessagePageDto,
  type PairRequest,
  type PairResponse,
  type ServerInfoDto,
} from "@lan-drop/protocol";

const TOKEN_KEY = "landrop.deviceToken";
const DEVICE_ID_KEY = "landrop.deviceId";
const SERVER_ID_KEY = "landrop.serverId";

/**
 * 本设备的配对凭据。
 *
 * Web 端存 localStorage 即可（浏览器环境没有 Keystore 可言）；
 * Android 客户端才需要 EncryptedSharedPreferences。
 */
export const credentials = {
  get token(): string | null {
    return localStorage.getItem(TOKEN_KEY);
  },
  get deviceId(): string | null {
    return localStorage.getItem(DEVICE_ID_KEY);
  },
  get serverId(): string | null {
    return localStorage.getItem(SERVER_ID_KEY);
  },
  save(pairing: PairResponse): void {
    localStorage.setItem(TOKEN_KEY, pairing.deviceToken);
    localStorage.setItem(DEVICE_ID_KEY, pairing.deviceId);
    localStorage.setItem(SERVER_ID_KEY, pairing.serverId);
  },
  clear(): void {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(DEVICE_ID_KEY);
    localStorage.removeItem(SERVER_ID_KEY);
  },
};

/** 带状态码与错误码的 API 异常，调用方据此区分 401（重配对）与网络故障。 */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;

  constructor(status: number, code: string, message: string) {
    super(message.length > 0 ? message : code);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const headers = new Headers(init?.headers);
  const token = credentials.token;
  if (token !== null && !headers.has("Authorization")) {
    headers.set("Authorization", `Bearer ${token}`);
  }

  let response: Response;
  try {
    response = await fetch(path, { ...init, headers });
  } catch (cause) {
    throw new ApiError(0, "network_error", `无法连接服务器：${String(cause)}`);
  }

  if (!response.ok) {
    let code = "request_failed";
    let message = "";
    try {
      const body = (await response.json()) as { error?: string; message?: string };
      if (typeof body.error === "string") code = body.error;
      if (typeof body.message === "string") message = body.message;
    } catch {
      // 非 JSON 错误体，保留默认错误码
    }
    throw new ApiError(response.status, code, message);
  }

  return (await response.json()) as T;
}

// ---------------------------------------------------------------- 元信息与配对

export function getInfo(): Promise<ServerInfoDto> {
  return request<ServerInfoDto>(ApiPath.info);
}

/** GET /pair/code 的响应（仅回环地址可读）。 */
export interface PairingCodeInfo {
  code: string;
  /** Unix 毫秒，过期后服务端自动轮换 */
  expiresAt: number;
  port: number;
  /** 每个 LAN 地址一条，二维码内容即其中带 hash 的 URL */
  urls: string[];
}

export function getPairingCode(): Promise<PairingCodeInfo> {
  return request<PairingCodeInfo>(`${ApiPath.pair}/code`);
}

export function pairWithCode(code: string, deviceName: string): Promise<PairResponse> {
  const body: PairRequest = { code, deviceName, platform: "web" };
  return request<PairResponse>(ApiPath.pair, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
  });
}

/** 本机浏览器免配对（仅回环地址可用）。 */
export function pairLocal(deviceName: string): Promise<PairResponse> {
  return request<PairResponse>(`${ApiPath.pair}/local`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ deviceName }),
  });
}

// ---------------------------------------------------------------- 消息

export function listMessages(since: number, limit: number): Promise<MessagePageDto> {
  return request<MessagePageDto>(`${ApiPath.messages}?since=${since}&limit=${limit}`);
}

export function sendText(kind: "text" | "link", text: string): Promise<MessageDto> {
  return request<MessageDto>(ApiPath.messages, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ kind, text }),
  });
}

/** 清空会话（仅本机可操作，服务端校验回环地址）。 */
export function clearMessages(): Promise<{ removed: number }> {
  return request<{ removed: number }>(ApiPath.messages, { method: "DELETE" });
}

// ---------------------------------------------------------------- 上传

export function createUpload(request_: CreateUploadRequest): Promise<CreateUploadResponse> {
  return request<CreateUploadResponse>(ApiPath.uploads, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(request_),
  });
}

export interface UploadChunkResult {
  receivedBytes: number;
  /** true 表示服务端回传了真实 offset（409 对齐），调用方应跳转到该位置续传 */
  realigned: boolean;
}

/**
 * 追加一个分片。
 *
 * 注意 content-type 必须显式写成 application/octet-stream：
 * `File.slice()` 得到的 Blob 会继承原文件的 MIME（如 image/png），
 * 服务端只注册了 octet-stream 的解析器，其他类型会直接 415。
 */
export async function uploadChunk(
  uploadId: string,
  offset: number,
  chunk: Blob,
): Promise<UploadChunkResult> {
  const headers: Record<string, string> = { "content-type": "application/octet-stream" };
  const token = credentials.token;
  if (token !== null) headers["authorization"] = `Bearer ${token}`;

  let response: Response;
  try {
    response = await fetch(`${ApiPath.uploads}/${uploadId}?offset=${offset}`, {
      method: "PATCH",
      headers,
      body: chunk,
    });
  } catch (cause) {
    throw new ApiError(0, "network_error", `分片上传失败：${String(cause)}`);
  }

  // 409 = offset 不对齐，响应体里带真实 offset，调用方据此对齐后续传
  if (response.status === 409) {
    const body = (await response.json().catch(() => ({}))) as { receivedBytes?: number };
    return {
      receivedBytes: typeof body.receivedBytes === "number" ? body.receivedBytes : 0,
      realigned: true,
    };
  }

  if (!response.ok) {
    throw new ApiError(response.status, "upload_chunk_failed", `分片上传失败（HTTP ${response.status}）`);
  }

  const body = (await response.json()) as { receivedBytes: number };
  return { receivedBytes: body.receivedBytes, realigned: false };
}

export function completeUpload(uploadId: string): Promise<MessageDto> {
  return request<MessageDto>(`${ApiPath.uploads}/${uploadId}/complete`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: "{}",
  });
}

export function abortUpload(uploadId: string): Promise<void> {
  return request<{ aborted: boolean }>(`${ApiPath.uploads}/${uploadId}`, { method: "DELETE" }).then(
    () => undefined,
  );
}

// ---------------------------------------------------------------- 下载

/**
 * 生成文件下载地址。
 *
 * `<img src>` 与 `<a download>` 没法带 Authorization 头，
 * 服务端允许用 ?token= 查询参数携带凭据。
 */
export function fileUrl(fileId: string, options?: { download?: boolean }): string {
  const params = new URLSearchParams({ token: credentials.token ?? "" });
  if (options?.download === true) params.set("download", "1");
  return `${ApiPath.files}/${fileId}?${params.toString()}`;
}
