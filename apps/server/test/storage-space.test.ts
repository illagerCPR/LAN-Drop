/**
 * 磁盘空间保护的单测。
 *
 * 磁盘满有两条防线：建会话时的剩余空间预检（freeDiskBytes + config.reserveBytes）
 * 与写分片时的 ENOSPC 识别（isDiskFullError）。真实 statfs 数值与真实 ENOSPC
 * 都依赖具体文件系统/真实满盘，跨进程行为由冒烟与 verify-all 的独立实例覆盖；
 * 这里钉住的是判定语义本身，尤其是「查询失败必须退化为跳过（返回 null）」的
 * fail-open 约定——磁盘满保护是尽力而为的提前拦截，绝不能因平台差异拒绝一切上传。
 */
import assert from "node:assert/strict";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";

import { freeDiskBytes, isDiskFullError } from "../src/storage.ts";

test("freeDiskBytes 对真实目录返回正数", async () => {
  const dir = mkdtempSync(join(tmpdir(), "lan-drop-space-"));
  try {
    const free = await freeDiskBytes(dir);
    assert.ok(free !== null && free > 0, `free=${free}`);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("freeDiskBytes 对不存在的目录返回 null（是「跳过」，不是 0）", async () => {
  const free = await freeDiskBytes(join(tmpdir(), "lan-drop-no-such-dir-xyz"));
  assert.equal(free, null);
});

test("isDiskFullError 只认 ENOSPC，其余一律放行为原错误", () => {
  const enospc = Object.assign(new Error("no space left on device"), { code: "ENOSPC" });
  assert.equal(isDiskFullError(enospc), true);
  assert.equal(isDiskFullError(Object.assign(new Error("missing"), { code: "ENOENT" })), false);
  assert.equal(isDiskFullError(new Error("plain")), false);
  assert.equal(isDiskFullError(null), false);
  assert.equal(isDiskFullError("ENOSPC"), false);
});
