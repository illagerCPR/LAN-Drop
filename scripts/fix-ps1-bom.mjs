#!/usr/bin/env node
/**
 * 给 packaging/ 下的 .ps1 补 UTF-8 BOM（缺了就直接失败，`--check` 只检查不改）。
 *
 *   node scripts/fix-ps1-bom.mjs            # 补齐
 *   node scripts/fix-ps1-bom.mjs --check    # 只检查（CI / 提交前用）
 *
 * 为什么需要这个脚本：编辑器的「保存」经常把 BOM 丢掉，而 Windows PowerShell 5.1
 * 对无 BOM 的 .ps1 会按系统 ANSI 代码页（中文系统是 GBK）解码，中文字符串变乱码，
 * 其中某些字节恰好是引号或反引号，于是报出「参数列表中缺少参数」这类与真实原因
 * 毫无关系的语法错误——在 Linux 上完全看不出来，只有到 Windows 上才炸。
 * 出包脚本 scripts/package.mjs 里也有一道同样的闸门（缺 BOM 直接拒绝出包）。
 */

import { readFile, readdir, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = fileURLToPath(new URL("..", import.meta.url));
const targets = [join(repoRoot, "packaging", "windows"), join(repoRoot, "scripts")];
const BOM = Buffer.from([0xef, 0xbb, 0xbf]);
const checkOnly = process.argv.includes("--check");

let missing = 0;
let fixed = 0;

for (const directory of targets) {
  const entries = await readdir(directory).catch(() => []);
  for (const entry of entries.filter((name) => name.endsWith(".ps1"))) {
    const path = join(directory, entry);
    const buffer = await readFile(path);
    const hasBom = buffer[0] === 0xef && buffer[1] === 0xbb && buffer[2] === 0xbf;

    if (hasBom) {
      console.log(`  [OK]   ${entry}`);
      continue;
    }

    missing += 1;
    if (checkOnly) {
      console.error(`  [缺BOM] ${path}`);
      continue;
    }
    await writeFile(path, Buffer.concat([BOM, buffer]));
    fixed += 1;
    console.log(`  [已修] ${entry}`);
  }
}

if (missing > 0 && checkOnly) {
  console.error(`\n有 ${missing} 个 .ps1 缺少 UTF-8 BOM：跑 node scripts/fix-ps1-bom.mjs 修复。`);
  process.exitCode = 1;
} else if (fixed > 0) {
  console.log(`\n已为 ${fixed} 个文件补上 BOM（PowerShell 5.1 现在能正确解码中文了）。`);
} else {
  console.log("\n全部 .ps1 都带 BOM。");
}
