#!/usr/bin/env node
/**
 * LAN-Drop 服务端 API 端到端冒烟测试。
 *
 * 用法：
 *   node scripts/smoke-api.mjs [baseUrl]
 * 默认 baseUrl = http://127.0.0.1:8787
 *
 * 覆盖：元信息 → 配对 → 文字消息 → 增量拉取 → 分片上传（多片）→ 完整性校验
 *       → 完整下载 → Range 下载 → WebSocket 实时通道 → 鉴权拒绝路径。
 * 脚本必须在本机运行（配对码接口只对回环地址开放）。
 */

import { createHash, randomBytes } from "node:crypto";

const baseUrl = process.argv[2] ?? "http://127.0.0.1:8787";

let passed = 0;
let failed = 0;

function ok(name, detail = "") {
  passed += 1;
  console.log(`  \u2713 ${name}${detail ? `  ${detail}` : ""}`);
}

function fail(name, detail = "") {
  failed += 1;
  console.error(`  \u2717 ${name}${detail ? `  ${detail}` : ""}`);
}

function check(condition, name, detail = "") {
  if (condition) ok(name, detail);
  else fail(name, detail);
  return condition;
}

async function json(method, path, { token, body, headers = {} } = {}) {
  const response = await fetch(`${baseUrl}${path}`, {
    method,
    headers: {
      ...(body !== undefined ? { "content-type": "application/json" } : {}),
      ...(token ? { authorization: `Bearer ${token}` } : {}),
      ...headers,
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });

  const text = await response.text();
  let parsed;
  try {
    parsed = text.length > 0 ? JSON.parse(text) : null;
  } catch {
    parsed = text;
  }
  return { status: response.status, body: parsed, headers: response.headers };
}

function sha256(buffer) {
  return createHash("sha256").update(buffer).digest("hex");
}

/**
 * 极简 WebSocket 测试客户端（Node 24 全局 WebSocket，undici 实现）。
 * 缓存所有事件帧，waitFor(type) 可等到「未来到达」或「已到达」的事件。
 */
function wsOpen(url) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(url);
    const events = [];
    const waiters = [];
    let opened = false;

    ws.addEventListener("open", () => {
      opened = true;
      resolve(api);
    });

    ws.addEventListener("message", (event) => {
      try {
        const parsed = JSON.parse(String(event.data));
        events.push(parsed);
        const index = waiters.findIndex((w) => w.type === parsed.type);
        if (index >= 0) {
          const [entry] = waiters.splice(index, 1);
          clearTimeout(entry.timer);
          entry.resolve(parsed);
        }
      } catch {
        // 非 JSON 帧忽略
      }
    });

    ws.addEventListener("close", (event) => {
      for (const entry of waiters.splice(0)) {
        clearTimeout(entry.timer);
        entry.reject(new Error(`连接已关闭（code=${event.code}）`));
      }
    });

    ws.addEventListener("error", () => {
      if (!opened) reject(new Error("WebSocket 连接失败"));
    });

    const api = {
      close: () => ws.close(),
      send: (obj) => ws.send(JSON.stringify(obj)),
      events: () => events,
      waitFor(type, timeoutMs = 5000) {
        const found = events.find((e) => e.type === type);
        if (found) return Promise.resolve(found);
        return new Promise((res, rej) => {
          const entry = {
            type,
            resolve: res,
            reject: rej,
            timer: setTimeout(() => {
              const index = waiters.indexOf(entry);
              if (index >= 0) waiters.splice(index, 1);
              rej(new Error(`等待事件 ${type} 超时（${timeoutMs}ms）`));
            }, timeoutMs),
          };
          waiters.push(entry);
        });
      },
    };
  });
}

async function main() {
  console.log(`\nLAN-Drop API 冒烟测试 → ${baseUrl}\n`);

  // ---------------------------------------------------------------- 1. 元信息
  console.log("[1] 服务端元信息");
  const info = await json("GET", "/api/v1/info");
  check(info.status === 200, "GET /api/v1/info 返回 200", `status=${info.status}`);
  check(info.body?.protocolVersion === 1, "协议版本为 1", `v${info.body?.protocolVersion}`);
  check(typeof info.body?.serverId === "string", "返回 serverId");

  // ---------------------------------------------------------------- 2. 配对
  console.log("\n[2] 配对与鉴权");
  const codeRes = await json("GET", "/api/v1/pair/code");
  check(codeRes.status === 200, "本机可读取配对码", `code=${codeRes.body?.code}`);
  const code = codeRes.body?.code;
  check(/^[A-Z0-9]{6}$/.test(String(code)), "配对码格式为 6 位大写字母数字");

  const badPair = await json("POST", "/api/v1/pair", {
    body: { code: "WRONG1", deviceName: "冒烟测试-错误码", platform: "test" },
  });
  check(badPair.status === 403, "错误配对码被拒绝", `status=${badPair.status}`);

  const pairRes = await json("POST", "/api/v1/pair", {
    body: { code, deviceName: "冒烟测试设备", platform: "test" },
  });
  check(pairRes.status === 200, "正确配对码配对成功", `status=${pairRes.status}`);
  const token = pairRes.body?.deviceToken;
  check(typeof token === "string" && token.length > 20, "拿到 deviceToken");

  const reuse = await json("POST", "/api/v1/pair", {
    body: { code, deviceName: "复用旧码", platform: "test" },
  });
  check(reuse.status === 403, "配对码一次性：复用旧码被拒绝", `status=${reuse.status}`);

  const noAuth = await json("GET", "/api/v1/messages");
  check(noAuth.status === 401, "未带 token 访问消息接口被拒绝", `status=${noAuth.status}`);

  const badAuth = await json("GET", "/api/v1/messages", { token: "not-a-real-token" });
  check(badAuth.status === 401, "伪造 token 被拒绝", `status=${badAuth.status}`);

  // ---------------------------------------------------------------- 3. 文字消息
  console.log("\n[3] 文字消息与增量同步");
  const before = await json("GET", "/api/v1/messages?since=0&limit=1", { token });
  const cursor = before.body?.latestSeq ?? 0;

  const marker = `冒烟测试 ${new Date().toISOString()}`;
  const sent = await json("POST", "/api/v1/messages", {
    token,
    body: { kind: "text", text: marker },
  });
  check(sent.status === 200, "发送文字消息", `status=${sent.status}`);
  check(sent.body?.seq > cursor, "新消息 seq 严格递增", `${cursor} → ${sent.body?.seq}`);
  check(sent.body?.senderName === "冒烟测试设备", "发送者名称正确");

  const page = await json("GET", `/api/v1/messages?since=${cursor}`, { token });
  check(page.status === 200, "增量拉取返回 200");
  check(
    Array.isArray(page.body?.items) && page.body.items.some((m) => m.text === marker),
    "增量拉取命中刚发送的消息",
    `共 ${page.body?.items?.length ?? 0} 条`,
  );

  const empty = await json("POST", "/api/v1/messages", { token, body: { text: "   " } });
  check(empty.status === 400, "空白文本被拒绝", `status=${empty.status}`);

  // ---------------------------------------------------------------- 4. 分片上传
  console.log("\n[4] 分片上传 / 完整性 / 下载");
  // 10 MiB + 1234 字节：跨 3 个 4 MiB 分片，且末片非整，能覆盖边界
  const payload = randomBytes(10 * 1024 * 1024 + 1234);
  const digest = sha256(payload);
  const fileName = "冒烟测试-中文文件名.bin";

  const create = await json("POST", "/api/v1/uploads", {
    token,
    body: { name: fileName, size: payload.length, mime: "application/octet-stream", sha256: digest },
  });
  check(create.status === 200, "创建上传会话", `status=${create.status}`);
  const uploadId = create.body?.uploadId;
  const chunkSize = create.body?.chunkSize ?? 4 * 1024 * 1024;
  check(typeof uploadId === "string", "拿到 uploadId", `chunkSize=${chunkSize}`);

  let offset = 0;
  let chunks = 0;
  while (offset < payload.length) {
    const end = Math.min(offset + chunkSize, payload.length);
    const slice = payload.subarray(offset, end);

    const res = await fetch(`${baseUrl}/api/v1/uploads/${uploadId}?offset=${offset}`, {
      method: "PATCH",
      headers: {
        authorization: `Bearer ${token}`,
        "content-type": "application/octet-stream",
      },
      body: slice,
    });

    if (res.status !== 200) {
      fail(`分片上传 offset=${offset}`, `status=${res.status} ${await res.text()}`);
      return;
    }
    const body = await res.json();
    if (body.receivedBytes !== end) {
      fail("分片进度回执正确", `期望 ${end}，实际 ${body.receivedBytes}`);
      return;
    }
    offset = end;
    chunks += 1;
  }
  ok("分片上传完成", `${chunks} 片，共 ${offset} 字节`);

  // 故意用错 offset 再发一次，应被拒绝（断点续传的对齐语义）
  const misaligned = await fetch(`${baseUrl}/api/v1/uploads/${uploadId}?offset=0`, {
    method: "PATCH",
    headers: { authorization: `Bearer ${token}`, "content-type": "application/octet-stream" },
    body: Buffer.from("x"),
  });
  check(misaligned.status === 409, "offset 不对齐被拒绝（断点续传语义）", `status=${misaligned.status}`);
  const misBody = await misaligned.json();
  check(misBody.receivedBytes === payload.length, "409 响应回传真实 offset 供客户端对齐",
    `receivedBytes=${misBody.receivedBytes}`);

  const complete = await json("POST", `/api/v1/uploads/${uploadId}/complete`, { token });
  check(complete.status === 200, "完成上传", `status=${complete.status}`);
  check(complete.body?.kind === "file", "生成 file 类型消息");
  check(complete.body?.file?.name === fileName, "文件名（含中文）正确保留", complete.body?.file?.name);
  check(complete.body?.file?.size === payload.length, "文件大小正确");
  const fileId = complete.body?.file?.id;

  // ---------------------------------------------------------------- 5. 下载
  const full = await fetch(`${baseUrl}/api/v1/files/${fileId}?download=1&token=${encodeURIComponent(token)}`);
  check(full.status === 200, "完整下载返回 200", `status=${full.status}`);
  const downloaded = Buffer.from(await full.arrayBuffer());
  check(downloaded.length === payload.length, "下载字节数一致",
    `${downloaded.length} vs ${payload.length}`);
  check(sha256(downloaded) === digest, "下载内容 sha256 一致");

  const disposition = full.headers.get("content-disposition") ?? "";
  check(disposition.includes("filename*=UTF-8''"), "Content-Disposition 使用 RFC 5987 编码（中文名不乱码）");

  const partial = await fetch(`${baseUrl}/api/v1/files/${fileId}?token=${encodeURIComponent(token)}`, {
    headers: { range: "bytes=100-199" },
  });
  check(partial.status === 206, "Range 请求返回 206", `status=${partial.status}`);
  const partialBody = Buffer.from(await partial.arrayBuffer());
  check(partialBody.length === 100, "Range 长度正确", `${partialBody.length} 字节`);
  check(partialBody.equals(payload.subarray(100, 200)), "Range 内容与源一致");
  check(
    partial.headers.get("content-range") === `bytes 100-199/${payload.length}`,
    "Content-Range 头正确",
    partial.headers.get("content-range") ?? "",
  );

  const suffix = await fetch(`${baseUrl}/api/v1/files/${fileId}?token=${encodeURIComponent(token)}`, {
    headers: { range: "bytes=-50" },
  });
  const suffixBody = Buffer.from(await suffix.arrayBuffer());
  check(
    suffix.status === 206 && suffixBody.equals(payload.subarray(payload.length - 50)),
    "后缀 Range（bytes=-50）正确",
  );

  const badRange = await fetch(`${baseUrl}/api/v1/files/${fileId}?token=${encodeURIComponent(token)}`, {
    headers: { range: `bytes=${payload.length + 10}-` },
  });
  check(badRange.status === 416, "越界 Range 返回 416", `status=${badRange.status}`);

  const fileNoAuth = await fetch(`${baseUrl}/api/v1/files/${fileId}`);
  check(fileNoAuth.status === 401, "未授权下载文件被拒绝", `status=${fileNoAuth.status}`);

  // ---------------------------------------------------------------- 6. WebSocket
  console.log("\n[6] WebSocket 实时通道");
  const wsBase = baseUrl.replace(/^http/, "ws");

  // 未带 token：服务端完成升级后以 4401 关闭
  const rejected = await new Promise((resolve) => {
    const ws = new WebSocket(`${wsBase}/api/v1/ws`);
    const timer = setTimeout(() => resolve({ code: "timeout" }), 5000);
    ws.addEventListener("close", (event) => {
      clearTimeout(timer);
      resolve({ code: event.code });
    });
    ws.addEventListener("error", () => undefined);
  });
  check(rejected.code === 4401, "WS 未带 token 被拒绝（4401）", `code=${rejected.code}`);

  // 有效 token：应收到 hello 握手，且 ping/pong 可用
  const socket = await wsOpen(`${wsBase}/api/v1/ws?token=${encodeURIComponent(token)}`);
  const hello = await socket.waitFor("hello");
  check(hello?.payload?.protocolVersion === 1, "WS hello 携带协议版本");
  check(
    Number.isInteger(hello?.payload?.latestSeq) && Number.isInteger(hello?.payload?.onlineCount),
    "WS hello 携带消息水位与在线数",
    `latestSeq=${hello?.payload?.latestSeq} online=${hello?.payload?.onlineCount}`,
  );

  socket.send({ type: "ping" });
  const pong = await socket.waitFor("pong");
  check(typeof pong?.payload?.at === "number", "WS ping/pong 心跳正常");

  // 实时广播：HTTP 发送一条消息，同一连接上应收到 message.new
  const wsMarker = `WS 广播 ${Date.now()}`;
  const wsSend = await json("POST", "/api/v1/messages", {
    token,
    body: { kind: "text", text: wsMarker },
  });
  check(wsSend.status === 200, "WS 阶段发送文字消息", `status=${wsSend.status}`);
  const broadcast = await socket.waitFor("message.new");
  check(broadcast?.payload?.text === wsMarker, "WS 收到 message.new 实时广播");
  socket.close();

  // ---------------------------------------------------------------- 汇总
  console.log(`\n${"─".repeat(52)}`);
  if (failed === 0) {
    console.log(`\u2705 全部通过：${passed} 项`);
  } else {
    console.log(`\u274c 失败 ${failed} 项，通过 ${passed} 项`);
  }
  console.log(`${"─".repeat(52)}\n`);

  process.exitCode = failed === 0 ? 0 : 1;
}

main().catch((error) => {
  console.error("\n冒烟测试异常终止：", error);
  process.exitCode = 1;
});
