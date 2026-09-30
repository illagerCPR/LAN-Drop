#!/usr/bin/env node
/**
 * 生成桌面端图标源图 icon-src.png（1024×1024 RGBA）。
 *
 * 刻意不引图像库：桌面端唯一的图像处理需求就是这一张源图，用「SDF 内外判定 + 4 倍亚采样
 * 抗锯齿 + 手写 PNG 编码器」一段纯 Node 就够了（zlib 来自 node:zlib）。改形状/配色直接改
 * 下面的常量，重跑 `pnpm --filter @lan-drop/desktop icon` 再用 tauri icon 重新生成全套图标。
 */

import { deflateSync } from "node:zlib";
import { writeFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { join } from "node:path";

const SIZE = 1024;
const OUT = fileURLToPath(new URL("../icon-src.png", import.meta.url));

/** 圆角矩形：内边距与圆角半径（相对 1024）。 */
const RECT_INSET = 32;
const RECT_RADIUS = 208;

/** 纸飞机（Material「send」字形）：大三角减去缺口三角，单位坐标 24×24。 */
const PLANE_SCALE = 34;
const PLANE_OFFSET = 96;
const PLANE_MAIN = [
  [2, 3],
  [23, 12],
  [2, 21],
];
const PLANE_NOTCH = [
  [2, 10],
  [17, 12],
  [2, 14],
];

/** 渐变对角线两端（左上 → 右下）。 */
const GRADIENT_FROM = [0x5b, 0x9b, 0xff];
const GRADIENT_TO = [0x1e, 0x56, 0xc8];

/** 每个最终像素的亚采样数（2×2）。抗锯齿够用，开销是 4 次内外判定。 */
const SUBSAMPLES = [
  [0.25, 0.25],
  [0.75, 0.25],
  [0.25, 0.75],
  [0.75, 0.75],
];

function scalePoint([x, y]) {
  return [PLANE_OFFSET + x * PLANE_SCALE, PLANE_OFFSET + y * PLANE_SCALE];
}

const MAIN_TRIANGLE = PLANE_MAIN.map(scalePoint);
const NOTCH_TRIANGLE = PLANE_NOTCH.map(scalePoint);

/** 点是否在三角形内（三个叉积同号；凸三角形专用）。 */
function pointInTriangle([px, py], [[ax, ay], [bx, by], [cx, cy]]) {
  const d1 = (px - bx) * (ay - by) - (ax - bx) * (py - by);
  const d2 = (px - cx) * (by - cy) - (bx - cx) * (py - cy);
  const d3 = (px - ax) * (cy - ay) - (cx - ax) * (py - ay);
  const hasNegative = d1 < 0 || d2 < 0 || d3 < 0;
  const hasPositive = d1 > 0 || d2 > 0 || d3 > 0;
  return !(hasNegative && hasPositive);
}

/** 点是否在圆角矩形内。 */
function pointInRoundedRect([px, py]) {
  const left = RECT_INSET;
  const top = RECT_INSET;
  const right = SIZE - RECT_INSET;
  const bottom = SIZE - RECT_INSET;
  if (px < left || px > right || py < top || py > bottom) return false;
  const centerX = Math.min(Math.max(px, left + RECT_RADIUS), right - RECT_RADIUS);
  const centerY = Math.min(Math.max(py, top + RECT_RADIUS), bottom - RECT_RADIUS);
  const dx = px - centerX;
  const dy = py - centerY;
  return dx * dx + dy * dy <= RECT_RADIUS * RECT_RADIUS;
}

function sample(x, y) {
  let red = 0;
  let green = 0;
  let blue = 0;
  let alpha = 0;
  for (const [sx, sy] of SUBSAMPLES) {
    const point = [x + sx, y + sy];
    if (!pointInRoundedRect(point)) continue;
    const t = (point[0] + point[1]) / (2 * SIZE);
    const base = GRADIENT_FROM.map((from, index) => Math.round(from + (GRADIENT_TO[index] - from) * t));
    const plane =
      pointInTriangle(point, MAIN_TRIANGLE) && !pointInTriangle(point, NOTCH_TRIANGLE);
    const color = plane ? [255, 255, 255] : base;
    red += color[0];
    green += color[1];
    blue += color[2];
    alpha += 255;
  }
  const count = SUBSAMPLES.length;
  return [Math.round(red / count), Math.round(green / count), Math.round(blue / count), Math.round(alpha / count)];
}

/** 手写 PNG 编码：8 字节签名 + IHDR + IDAT（filter 0 逐行）+ IEND，CRC32 自算。 */
const CRC_TABLE = (() => {
  const table = new Int32Array(256);
  for (let n = 0; n < 256; n += 1) {
    let c = n;
    for (let k = 0; k < 8; k += 1) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    table[n] = c;
  }
  return table;
})();

function crc32(buffer) {
  let crc = -1;
  for (const byte of buffer) crc = CRC_TABLE[(crc ^ byte) & 0xff] ^ (crc >>> 8);
  return (crc ^ -1) >>> 0;
}

function chunk(type, data) {
  const length = Buffer.alloc(4);
  length.writeUInt32BE(data.length);
  const body = Buffer.concat([Buffer.from(type, "ascii"), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body));
  return Buffer.concat([length, body, crc]);
}

function encodePng(pixels) {
  const signature = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(SIZE, 0);
  ihdr.writeUInt32BE(SIZE, 4);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 6; // color type: RGBA
  const raw = Buffer.alloc(SIZE * (SIZE * 4 + 1));
  for (let y = 0; y < SIZE; y += 1) {
    const rowStart = y * (SIZE * 4 + 1);
    raw[rowStart] = 0; // filter: none
    for (let x = 0; x < SIZE; x += 1) {
      const [r, g, b, a] = sample(x, y);
      const offset = rowStart + 1 + x * 4;
      raw[offset] = r;
      raw[offset + 1] = g;
      raw[offset + 2] = b;
      raw[offset + 3] = a;
    }
  }
  return Buffer.concat([
    signature,
    chunk("IHDR", ihdr),
    chunk("IDAT", deflateSync(raw, { level: 9 })),
    chunk("IEND", Buffer.alloc(0)),
  ]);
}

const png = encodePng();
await writeFile(OUT, png);
console.log(`[make-icon] 已生成 ${OUT}（${(png.length / 1024).toFixed(0)} KB）`);
