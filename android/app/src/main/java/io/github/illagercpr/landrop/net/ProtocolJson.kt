package io.github.illagercpr.landrop.net

import kotlinx.serialization.json.Json

/**
 * 协议 JSON 的统一配置。
 *
 * 单独抽出来是为了让单元测试用与运行时**完全相同**的配置去解析真实服务端样本：
 * 配置差异导致的解码行为不同（例如少了 `ignoreUnknownKeys`）是最难在真机上定位的一类问题。
 */
val ProtocolJson: Json = Json {
    /** 服务端加字段不至于把老客户端打挂 */
    ignoreUnknownKeys = true

    /**
     * 可空字段为 null 时直接省略键，与 Web 端 `JSON.stringify` 行为一致——
     * 服务端多处按「有没有这个键」判断（如 `typeof body?.mime === "string"`）。
     */
    explicitNulls = false

    encodeDefaults = true
}
