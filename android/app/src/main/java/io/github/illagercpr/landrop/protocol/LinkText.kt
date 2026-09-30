package io.github.illagercpr.landrop.protocol

/**
 * `link` 消息的判定规则 —— 事实源 `packages/protocol/src/index.ts` 里 `isLinkText` 的镜像。
 *
 * 两端必须用同一条规则：发送端据此决定 `kind` 填 `link` 还是 `text`，渲染端据此决定
 * 要不要把整条消息做成可点链接。任何一边单独改，都会出现「手机上是链接、网页上是纯文本」
 * 这类分叉；改这里就同时改那边（TS 侧有 15 项单测钉住同样的边界）。
 *
 * 只认 http/https：`kind` 由发送方自填、服务端不做语义校验，而 `javascript:` 之类的
 * 伪协议一旦进了 `href` 或交给系统 `ACTION_VIEW` 就是注入。
 */
fun isHttpUrl(text: String): Boolean = HTTP_URL.matches(text.trim())

/** 等价于 TS 的 `/^https?:\/\/\S+$/i`；`Regex.matches` 本身就要求整串匹配，无需 `$`。 */
private val HTTP_URL = Regex("^https?://\\S+", RegexOption.IGNORE_CASE)

/** 消息类型字符串，与 [MessageKind] 的序列化值一致（`kind` 落库与上行都是小写）。 */
const val MESSAGE_KIND_TEXT = "text"

/** 见 [isHttpUrl]：整段就是一个链接时用这个 kind。 */
const val MESSAGE_KIND_LINK = "link"
