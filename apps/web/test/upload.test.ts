import assert from "node:assert/strict";
import test from "node:test";

import type {
  CreateUploadRequest,
  CreateUploadResponse,
  MessageDto,
  UploadState,
  UploadStatusDto,
} from "@lan-drop/protocol";

import {
  Uploader,
  type UploadChunkResult,
  type UploadSnapshot,
  type UploadSource,
  type UploadTransport,
} from "../src/upload.ts";

/**
 * 上传控制器的单测。
 *
 * 这一层没有界面，全部依赖都是注入的，所以断言直接落在**状态机与网络调用序列**上：
 * 发了哪几片、偏移量是多少、什么时候一个请求都不发。断点续传最容易错的地方是
 * 「本地记的 offset 与服务端实际收到的字节数分叉」，因此假服务端有自己独立的
 * `serverReceived`，由它说了算——而不是复用客户端的进度。
 */

/** 一个可控的假来源：只记录被切了哪几段。 */
function fakeSource(
  name: string,
  size: number,
  type = "application/octet-stream",
): { source: UploadSource; slices: Array<[number, number]> } {
  const slices: Array<[number, number]> = [];
  const source: UploadSource = {
    name,
    size,
    type,
    slice(start: number, end: number): Blob {
      slices.push([start, end]);
      return { size: end - start } as Blob;
    },
  };
  return { source, slices };
}

/** 假服务端：每个上传会话各有一份进度，与客户端的想法无关。 */
class FakeTransport implements UploadTransport {
  readonly creates: CreateUploadRequest[] = [];
  readonly patches: Array<{ uploadId: string; offset: number; bytes: number }> = [];
  readonly aborts: string[] = [];
  completes = 0;

  readonly sessions = new Map<string, { name: string; size: number; received: number; state: UploadState }>();
  /** 为真时所有会话都返回 null（相当于 404） */
  gone = false;
  /** 这一片开始前先等这个闸门（用来制造「分片正在飞」的窗口） */
  gate: Promise<void> | null = null;
  /** 非空则抛出，模拟网络中断 */
  chunkError: Error | null = null;
  /** 自定义这一片之后服务端的进度；默认正常前进 */
  afterChunk: ((offset: number, bytes: number) => number) | null = null;
  /** 每次分片前回调（可在其中按暂停/取消） */
  onChunk: ((offset: number, bytes: number) => void) | null = null;

  #sequence = 0;

  create(request: CreateUploadRequest): Promise<CreateUploadResponse> {
    this.creates.push(request);
    this.#sequence += 1;
    const uploadId = `up-${this.#sequence}`;
    this.sessions.set(uploadId, {
      name: request.name,
      size: request.size,
      received: 0,
      state: "open",
    });
    return Promise.resolve({ uploadId, receivedBytes: 0, chunkSize: 4 });
  }

  status(uploadId: string): Promise<UploadStatusDto | null> {
    const session = this.sessions.get(uploadId);
    if (this.gone || session === undefined) return Promise.resolve(null);

    return Promise.resolve({
      uploadId,
      name: session.name,
      size: session.size,
      receivedBytes: session.received,
      state: session.state,
      resumable: session.state === "open" && session.received < session.size,
      chunkSize: 4,
      createdAt: 0,
      updatedAt: 0,
    });
  }

  async chunk(uploadId: string, offset: number, chunk: Blob): Promise<UploadChunkResult> {
    const bytes = (chunk as { size: number }).size;
    this.patches.push({ uploadId, offset, bytes });
    this.onChunk?.(offset, bytes);

    if (this.gate !== null) await this.gate;
    if (this.chunkError !== null) throw this.chunkError;

    const session = this.sessions.get(uploadId);
    if (session === undefined) throw new Error(`unknown session ${uploadId}`);
    session.received = this.afterChunk?.(offset, bytes) ?? offset + bytes;
    return { receivedBytes: session.received, realigned: false };
  }

  complete(uploadId: string): Promise<MessageDto> {
    this.completes += 1;
    return Promise.resolve({
      seq: this.completes,
      id: `msg-${uploadId}`,
      kind: "file",
      senderId: "device-web",
      senderName: "Web",
      createdAt: 0,
    });
  }

  abort(uploadId: string): Promise<void> {
    this.aborts.push(uploadId);
    const session = this.sessions.get(uploadId);
    if (session !== undefined) session.state = "aborted";
    return Promise.resolve();
  }

  /** 直接改服务端会话的进度，模拟「客户端不知道、服务端其实已经收到了」。 */
  setReceived(uploadId: string, receivedBytes: number): void {
    const session = this.sessions.get(uploadId);
    assert.ok(session !== undefined, `没有会话 ${uploadId}`);
    session.received = receivedBytes;
  }

  markAborted(uploadId: string): void {
    const session = this.sessions.get(uploadId);
    assert.ok(session !== undefined, `没有会话 ${uploadId}`);
    session.state = "aborted";
  }
}

interface Harness {
  uploader: Uploader;
  transport: FakeTransport;
  completed: MessageDto[];
  snapshots: () => UploadSnapshot[];
  phaseOf: (id: string) => string | undefined;
}

function harness(): Harness {
  const transport = new FakeTransport();
  const completed: MessageDto[] = [];
  let latest: UploadSnapshot[] = [];
  const uploader = new Uploader({
    transport,
    onChange: (snapshots) => {
      latest = snapshots;
    },
    onComplete: (message) => completed.push(message),
  });
  return {
    uploader,
    transport,
    completed,
    snapshots: () => latest,
    phaseOf: (id) => latest.find((item) => item.id === id)?.phase,
  };
}

/** 等条件成立；控制器全是微任务，setImmediate 转几圈就够。 */
async function waitFor(what: string, predicate: () => boolean): Promise<void> {
  for (let attempt = 0; attempt < 200; attempt += 1) {
    if (predicate()) return;
    await new Promise((resolve) => setImmediate(resolve));
  }
  assert.fail(`等待超时：${what}`);
}

test("按 chunkSize 顺序切片，全部送达后收尾", async () => {
  const h = harness();
  const { source, slices } = fakeSource("a.bin", 10);

  const id = h.uploader.add(source);
  await waitFor("上传完成", () => h.phaseOf(id) === "done");

  assert.deepEqual(slices, [
    [0, 4],
    [4, 8],
    [8, 10],
  ]);
  assert.deepEqual(
    h.transport.patches.map((patch) => patch.offset),
    [0, 4, 8],
  );
  assert.equal(h.transport.completes, 1);
  assert.equal(h.transport.creates.length, 1);
  assert.equal(h.completed.length, 1);
  assert.equal(h.snapshots()[0]?.offset, 10);
});

test("零字节文件不切片，直接收尾", async () => {
  const h = harness();
  const { source, slices } = fakeSource("empty.txt", 0);

  const id = h.uploader.add(source);
  await waitFor("零字节上传完成", () => h.phaseOf(id) === "done");

  assert.deepEqual(slices, []);
  assert.equal(h.transport.patches.length, 0);
  assert.equal(h.transport.completes, 1);
});

test("继续时不信任本地 offset，以服务端权威进度为准", async () => {
  const h = harness();
  const { source } = fakeSource("a.bin", 16);

  // 发到第 6 字节就暂停（本地只认到 4）
  h.transport.onChunk = (offset) => {
    if (offset === 4) h.uploader.pause(id);
  };
  const id = h.uploader.add(source);
  await waitFor("已暂停", () => h.phaseOf(id) === "paused");
  assert.equal(h.snapshots()[0]?.offset, 8);

  // 服务端其实收到了 12 —— 本地那 8 是过时的
  h.transport.setReceived("up-1", 12);
  h.transport.onChunk = null;

  h.uploader.resume(id);
  await waitFor("续传完成", () => h.phaseOf(id) === "done");

  assert.deepEqual(
    h.transport.patches.map((patch) => patch.offset),
    [0, 4, 12],
  );
  assert.equal(h.transport.creates.length, 1, "会话还在，不应重建");
});

test("会话已不在服务端时重建会话并从零重传", async () => {
  const h = harness();
  const { source } = fakeSource("a.bin", 16);

  h.transport.onChunk = (offset) => {
    if (offset === 4) h.uploader.pause(id);
  };
  const id = h.uploader.add(source);
  await waitFor("已暂停", () => h.phaseOf(id) === "paused");

  h.transport.gone = true;
  h.transport.onChunk = null;

  h.uploader.resume(id);
  await waitFor("重建后完成", () => h.phaseOf(id) === "done");

  assert.equal(h.transport.creates.length, 2);
  assert.deepEqual(
    h.transport.patches.map((patch) => patch.offset),
    [0, 4, 0, 4, 8, 12],
  );
  assert.equal(h.transport.patches.at(-1)?.uploadId, "up-2");
});

test("会话被服务端标成 aborted 时同样重建", async () => {
  const h = harness();
  const { source } = fakeSource("a.bin", 8);

  h.transport.onChunk = (offset) => {
    if (offset === 0) h.uploader.pause(id);
  };
  const id = h.uploader.add(source);
  await waitFor("已暂停", () => h.phaseOf(id) === "paused");

  h.transport.markAborted("up-1");
  h.transport.onChunk = null;

  h.uploader.resume(id);
  await waitFor("重建后完成", () => h.phaseOf(id) === "done");

  assert.equal(h.transport.creates.length, 2);
  assert.equal(h.phaseOf(id), "done");
});

test("暂停发生在分片之间：不打断飞行中的那一片", async () => {
  const h = harness();
  const { source } = fakeSource("a.bin", 24);

  let release = (): void => {};
  h.transport.gate = new Promise((resolve) => {
    release = () => {
      resolve();
    };
  });

  const id = h.uploader.add(source);
  await waitFor("第一片已发出", () => h.transport.patches.length === 1);

  h.uploader.pause(id);
  assert.equal(h.phaseOf(id), "pausing", "飞行中显示「暂停中」而不是已暂停");

  release();
  await waitFor("落成已暂停", () => h.phaseOf(id) === "paused");
  assert.equal(h.transport.patches.length, 1, "暂停后不应再有新分片");

  // 再等几圈，确认真的停了
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(h.transport.patches.length, 1);
});

test("暂停不占队列：后面的文件照常发出去", async () => {
  const h = harness();
  const first = fakeSource("big.bin", 24);
  const second = fakeSource("small.txt", 4);

  h.transport.onChunk = (offset) => {
    // 第一个文件刚发出第一片就把它挂起
    if (offset === 0 && h.transport.patches.length === 1) h.uploader.pause(firstId);
  };
  const firstId = h.uploader.add(first.source);
  const secondId = h.uploader.add(second.source);

  await waitFor("第二个文件先传完", () => h.phaseOf(secondId) === "done");
  assert.equal(h.phaseOf(firstId), "paused");
  assert.equal(h.transport.completes, 1, "完成的是后面的小文件");

  h.transport.onChunk = null;
  h.uploader.resume(firstId);
  await waitFor("挂起的文件也传完", () => h.phaseOf(firstId) === "done");
  assert.equal(h.transport.completes, 2);
});

test("任一片不前进时停下并给出可读原因，不空转", async () => {
  const h = harness();
  const { source } = fakeSource("a.bin", 32);

  // 服务端原地不动：模拟会话在别处被中止
  h.transport.afterChunk = () => 0;

  const id = h.uploader.add(source);
  await waitFor("失败", () => h.phaseOf(id) === "failed");

  assert.equal(h.transport.patches.length, 3, "三次不前进就收手");
  const snapshot = h.snapshots()[0];
  assert.match(snapshot?.error ?? "", /没有前进/);
});

test("失败后重试从服务端锚点接着传，而不是从头再来", async () => {
  const h = harness();
  const { source } = fakeSource("a.bin", 16);

  h.transport.chunkError = new Error("网络中断");
  const id = h.uploader.add(source);
  await waitFor("失败", () => h.phaseOf(id) === "failed");

  // 服务端其实收到了第一片（响应没回来而已）
  h.transport.setReceived("up-1", 4);
  h.transport.chunkError = null;

  h.uploader.retry(id);
  await waitFor("重试后完成", () => h.phaseOf(id) === "done");

  assert.deepEqual(
    h.transport.patches.map((patch) => patch.offset),
    [0, 4, 8, 12],
    "重试后从 4 接着发",
  );
  assert.equal(h.transport.creates.length, 1, "会话还能用就不重建");
});

test("取消会中止服务端会话，并从面板消失且不再发分片", async () => {
  const h = harness();
  const { source } = fakeSource("a.bin", 24);

  let release = (): void => {};
  h.transport.gate = new Promise((resolve) => {
    release = () => {
      resolve();
    };
  });

  const id = h.uploader.add(source);
  await waitFor("第一片已发出", () => h.transport.patches.length === 1);

  h.uploader.cancel(id);
  assert.deepEqual(h.snapshots(), [], "取消后立刻从面板移除");
  assert.deepEqual(h.transport.aborts, ["up-1"]);

  release();
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(h.transport.patches.length, 1, "取消后不再发新分片");
  assert.equal(h.transport.completes, 0);
});

test("忙碌时取消排队中的任务不会牵连正在跑的那个", async () => {
  const h = harness();
  const first = fakeSource("a.bin", 8);
  const second = fakeSource("b.bin", 8);

  h.transport.onChunk = (offset) => {
    if (offset === 0 && h.transport.patches.length === 1) h.uploader.cancel(secondId);
  };
  const firstId = h.uploader.add(first.source);
  const secondId = h.uploader.add(second.source);

  await waitFor("第一个传完", () => h.phaseOf(firstId) === "done");
  assert.equal(h.phaseOf(secondId), undefined, "排队中的任务被取消后直接消失");
  assert.deepEqual(h.transport.aborts, [], "还没建会话，没有可中止的东西");
  assert.equal(h.transport.completes, 1);
});
