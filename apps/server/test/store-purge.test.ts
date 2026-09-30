/**
 * Store 保留策略（purgeExpiredMessages）的单测。
 *
 * 保留策略的核心承诺是「前缀删除」：服务端删掉的集合必须与客户端理解的
 * 「删到 seq X 为止」是同一个集合，否则两端会对不上。这里用内存库把
 * 天数边界、条数边界、关闭、文件引用清理四个面都钉住。
 */
import assert from "node:assert/strict";
import { test } from "node:test";

import { Store } from "../src/store.ts";

const DAY_MS = 24 * 60 * 60 * 1000;

/** 内存库 + 固定发送者，时间以相对 now 的毫秒偏移表达，避免用例里出现绝对时间戳。 */
function newStore(): Store {
  return new Store(":memory:");
}

function daysAgoMs(days: number): number {
  return Date.now() - days * DAY_MS;
}

test("天数阈值只删过期消息，边界落在最旧过期消息的 seq", () => {
  const store = newStore();
  const s1 = store.appendTextMessage({ senderId: "d1", senderName: "D1", kind: "text", text: "old", createdAt: daysAgoMs(40) });
  const s2 = store.appendTextMessage({ senderId: "d1", senderName: "D1", kind: "text", text: "mid", createdAt: daysAgoMs(20) });
  const s3 = store.appendTextMessage({ senderId: "d1", senderName: "D1", kind: "text", text: "new", createdAt: daysAgoMs(1) });

  const result = store.purgeExpiredMessages({ olderThanMs: 30 * DAY_MS, keepCount: null });

  assert.deepEqual(result, { purged: 1, uptoSeq: s1.seq, files: [] });
  assert.equal(store.findMessageBySeq(s1.seq), null);
  assert.ok(store.findMessageBySeq(s2.seq));
  assert.ok(store.findMessageBySeq(s3.seq));
  store.close();
});

test("条数阈值保留最新 N 条，删除部分是严格前缀", () => {
  const store = newStore();
  for (let i = 0; i < 5; i += 1) {
    store.appendTextMessage({ senderId: "d1", senderName: "D1", kind: "text", text: `m${i}` });
  }

  const result = store.purgeExpiredMessages({ olderThanMs: null, keepCount: 3 });

  assert.equal(result.purged, 2);
  assert.equal(result.uptoSeq, 2);
  // AUTOINCREMENT 有洞时也不能误删，边界按实际 seq 算
  assert.ok(store.findMessageBySeq(3));
  assert.ok(store.findMessageBySeq(4));
  assert.ok(store.findMessageBySeq(5));
  store.close();
});

test("双阈值同时生效时取更大的边界（删得更多的一方主导）", () => {
  const store = newStore();
  const s1 = store.appendTextMessage({ senderId: "d1", senderName: "D1", kind: "text", text: "a", createdAt: daysAgoMs(40) });
  const s2 = store.appendTextMessage({ senderId: "d1", senderName: "D1", kind: "text", text: "b", createdAt: daysAgoMs(40) });
  store.appendTextMessage({ senderId: "d1", senderName: "D1", kind: "text", text: "c", createdAt: Date.now() });

  // 天数边界 = 2（两条都过期），条数 keep=10 总数不足 → 边界 0；应删 2 条
  const result = store.purgeExpiredMessages({ olderThanMs: 30 * DAY_MS, keepCount: 10 });

  assert.equal(result.uptoSeq, s2.seq);
  assert.equal(result.purged, 2);
  assert.equal(store.findMessageBySeq(s1.seq), null);
  store.close();
});

test("阈值为 0 表示关闭，不产生任何写操作", () => {
  const store = newStore();
  const s1 = store.appendTextMessage({ senderId: "d1", senderName: "D1", kind: "text", text: "keep", createdAt: daysAgoMs(400) });

  const bothOff = store.purgeExpiredMessages({ olderThanMs: null, keepCount: null });
  const zeroDays = store.purgeExpiredMessages({ olderThanMs: 0, keepCount: null });
  const zeroCount = store.purgeExpiredMessages({ olderThanMs: null, keepCount: 0 });

  for (const result of [bothOff, zeroDays, zeroCount]) {
    assert.deepEqual(result, { purged: 0, uptoSeq: 0, files: [] });
  }
  assert.ok(store.findMessageBySeq(s1.seq));
  store.close();
});

test("空库执行保留清理是安全的空操作", () => {
  const store = newStore();

  const result = store.purgeExpiredMessages({ olderThanMs: DAY_MS, keepCount: 5 });

  assert.deepEqual(result, { purged: 0, uptoSeq: 0, files: [] });
  store.close();
});

test("purgedUpto 水位随清理推进且不回退", () => {
  const store = newStore();
  for (let i = 0; i < 5; i += 1) {
    store.appendTextMessage({ senderId: "d1", senderName: "D1", kind: "text", text: `m${i}` });
  }

  store.purgeExpiredMessages({ olderThanMs: null, keepCount: 3 });
  assert.equal(store.purgedUpto(), 2);

  // 更大边界的清理把水位继续推高
  store.purgeExpiredMessages({ olderThanMs: null, keepCount: 1 });
  assert.equal(store.purgedUpto(), 4);

  // 小边界的清理不产生回拨（无消息可删，水位保持）
  const noop = store.purgeExpiredMessages({ olderThanMs: null, keepCount: 100 });
  assert.equal(noop.purged, 0);
  assert.equal(store.purgedUpto(), 4);
  store.close();
});

test("被删文件消息引用的文件行一并删除，仍被留存消息引用的保留", () => {
  const store = newStore();
  // f1 只被将删消息引用 → 应删；f2 同时被留存消息引用 → 应留
  const f1 = store.createFile({ name: "doomed.bin", size: 10, mime: null, sha256: null, relPath: "2026/09/doomed.bin" });
  const f2 = store.createFile({ name: "shared.bin", size: 20, mime: null, sha256: null, relPath: "2026/09/shared.bin" });
  store.appendFileMessage({ senderId: "d1", senderName: "D1", fileId: f1.id, createdAt: daysAgoMs(40) });
  store.appendFileMessage({ senderId: "d1", senderName: "D1", fileId: f2.id, createdAt: daysAgoMs(40) });
  store.appendFileMessage({ senderId: "d1", senderName: "D1", fileId: f2.id, createdAt: Date.now() });

  const result = store.purgeExpiredMessages({ olderThanMs: 30 * DAY_MS, keepCount: null });

  assert.equal(result.files.length, 1);
  assert.equal(result.files[0]?.id, f1.id);
  assert.equal(result.files[0]?.relPath, "2026/09/doomed.bin");
  assert.equal(store.findFile(f1.id), null);
  assert.ok(store.findFile(f2.id), "仍被留存消息引用的文件行必须保留");
  store.close();
});

test("条数边界按实际 seq 计算，AUTOINCREMENT 的空洞不会导致误删", () => {
  const store = newStore();
  store.appendTextMessage({ senderId: "d1", senderName: "D1", kind: "text", text: "a" });
  store.appendTextMessage({ senderId: "d1", senderName: "D1", kind: "text", text: "b" });
  store.appendTextMessage({ senderId: "d1", senderName: "D1", kind: "text", text: "c" });
  // 清空后 seq 不回退（AUTOINCREMENT），再补三条 → seq 4/5/6，模拟运行过一段时间的库
  store.clearMessages();
  const doomed = store.appendTextMessage({ senderId: "d1", senderName: "D1", kind: "text", text: "d" });
  const keep1 = store.appendTextMessage({ senderId: "d1", senderName: "D1", kind: "text", text: "e" });
  const keep2 = store.appendTextMessage({ senderId: "d1", senderName: "D1", kind: "text", text: "f" });

  const result = store.purgeExpiredMessages({ olderThanMs: null, keepCount: 2 });

  // top2 = seq5/6，边界 = 4：按「条数偏移」算会错删，按实际 seq 删恰好一条
  assert.equal(result.uptoSeq, doomed.seq);
  assert.equal(result.purged, 1);
  assert.ok(store.findMessageBySeq(keep1.seq));
  assert.ok(store.findMessageBySeq(keep2.seq));
  store.close();
});
