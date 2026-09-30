import assert from "node:assert/strict";
import test from "node:test";

import { isLinkText } from "../src/index.ts";

/**
 * `isLinkText` 是两端共用的「什么算链接」规则，因此这里钉的是**边界**而不是实现：
 * 少了任何一条，手机与网页就会对同一句话给出不同渲染。
 */
const cases: Array<[text: string, expected: boolean, why: string]> = [
  ["https://example.com", true, "最常见的形态"],
  ["http://192.168.1.5:8787/#pair=ABC", true, "局域网地址带端口与 hash 也算"],
  ["  https://example.com/a?b=1#c  ", true, "首尾空白先剪掉"],
  ["HTTPS://EXAMPLE.COM", true, "scheme 大小写不敏感"],
  ["https://例子.中国/路径", true, "非 ASCII 域名/路径照收"],
  ["https://", false, "只有 scheme 与分隔符，没有主机"],
  ["example.com", false, "没有 scheme，不当链接"],
  ["看这个 https://example.com", false, "夹在句子里的是 text，不是 link kind"],
  ["https://exa mple.com", false, "含空格就不可能是一个完整 URL"],
  ["javascript:alert(1)", false, "伪协议——渲染成 href 就是注入"],
  ["data:text/html,<script>alert(1)</script>", false, "同样是伪协议"],
  ["file:///etc/passwd", false, "只允许 http/https"],
  ["mailto:someone@example.com", false, "只允许 http/https"],
  ["", false, "空串永远不是链接"],
  ["   ", false, "只有空白同样不是"],
];

for (const [text, expected, why] of cases) {
  test(`isLinkText(${JSON.stringify(text)}) === ${String(expected)} —— ${why}`, () => {
    assert.equal(isLinkText(text), expected);
  });
}
