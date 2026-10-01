#!/usr/bin/env node
/**
 * 文档相对链接检查：扫描仓库内所有 .md 的 `[文本](路径)` 链接，
 * 验证指向仓库内文件的目标真的存在（跳过 http/mailto/纯锚点）。
 *
 * 背景：文档里的路径引用脱节（改了文件名没改文档）靠肉眼抓不住；
 * CI 的 hygiene job 用它当门禁。锚点（`#xxx`、`#L24`）一律剥掉后只查文件部分。
 */

import { readdirSync, readFileSync, statSync } from "node:fs";
import { dirname, extname, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = fileURLToPath(new URL("..", import.meta.url));

/** 跳过的目录：依赖、构建产物、VCS。 */
const SKIP_DIRS = new Set([
  "node_modules",
  ".git",
  "dist",
  "build",
  ".gradle",
  "target",
  "src-tauri",
]);

function listMarkdownFiles(dir) {
  const found = [];

  for (const entry of readdirSync(dir)) {
    if (entry.startsWith(".") && entry !== ".github") continue;
    const abs = join(dir, entry);
    const stat = statSync(abs);
    if (stat.isDirectory()) {
      if (SKIP_DIRS.has(entry)) continue;
      found.push(...listMarkdownFiles(abs));
    } else if (extname(entry) === ".md") {
      found.push(abs);
    }
  }

  return found;
}

/** 提取 markdown 链接目标；代码块里的假链接也可能被扫到，查不到时按上下文人工排除。 */
function extractLinks(text) {
  const links = [];
  const re = /\[[^\]]*\]\(([^)\s]+)\)/g;
  let match;

  while ((match = re.exec(text)) !== null) {
    links.push(match[1]);
  }

  return links;
}

function isInternalLink(target) {
  return (
    !/^https?:/i.test(target) &&
    !/^mailto:/i.test(target) &&
    !target.startsWith("#") &&
    !target.startsWith("/")
  );
}

const markdownFiles = listMarkdownFiles(ROOT);
const broken = [];

for (const file of markdownFiles) {
  const text = readFileSync(file, "utf8");
  const relFile = relative(ROOT, file);

  for (const rawTarget of extractLinks(text)) {
    const target = decodeURIComponent(rawTarget.split("#")[0]);
    if (target.length === 0 || !isInternalLink(target)) continue;

    // 反引号包裹的占位（如 `<文件>`）与通配符不是真实路径，跳过
    if (target.includes("*") || target.includes("<")) continue;

    const resolved = resolve(dirname(file), target);
    try {
      statSync(resolved);
    } catch {
      broken.push(`${relFile}  →  ${rawTarget}`);
    }
  }
}

if (broken.length > 0) {
  console.error(`✗ ${broken.length} 条文档链接指向不存在的文件：`);
  for (const entry of broken) console.error(`  ${entry}`);
  process.exit(1);
}

console.log(`✅ 文档链接检查通过（${markdownFiles.length} 个 .md 文件）`);
