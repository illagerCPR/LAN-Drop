package io.github.illagercpr.landrop.net

import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.serialization.SerializationException

/**
 * 把底层异常翻译成能直接展示给用户的中文提示。
 *
 * 局域网场景下用户最可能踩的就那几种（服务端没开、IP 填错、不在同一网段、
 * 凭据失效），逐个给出可操作的下一步，比抛 `java.net.ConnectException` 有用得多。
 */
fun Throwable.toUserMessage(): String = when (this) {
    is ApiException -> when (errorCode) {
        "unauthorized" -> "登录凭据已失效，请重新配对"
        "invalid_pairing_code" -> "配对码不正确或已过期，请在 PC 上重新查看"
        "loopback_only" -> "该操作只能在服务端本机执行"
        "invalid_body", "missing_name", "invalid_size" -> "请求参数不合法"
        "file_too_large" -> "文件超出服务端允许的大小上限"
        "exceeds_declared_size" -> "发送的数据超出声明大小，服务端已中止该传输"
        "file_missing_on_disk" -> "服务端上的文件已被删除"
        "file_not_found" -> "服务端上找不到该文件"
        "not_upload_owner" -> "该上传会话不属于本设备"
        "upload_not_found" -> "上传会话已失效，请重新发送"
        "sha256_mismatch" -> "文件校验失败（内容不一致），请重新发送"
        "only_private_network_allowed" -> "服务端只接受局域网内的请求"
        "text_too_long" -> "消息过长"
        else -> "服务端返回错误：$errorCode（HTTP $statusCode）"
    }

    is UnknownHostException -> "找不到该地址，请检查 IP 是否填写正确"

    is ConnectException, is SocketTimeoutException ->
        "无法连接到服务端，请确认 PC 上服务端已启动且与手机在同一局域网"

    is SocketException -> "网络连接中断"

    is SerializationException -> "协议不兼容，请更新客户端与服务端版本"

    else -> message?.takeIf { it.isNotBlank() } ?: "未知错误"
}
