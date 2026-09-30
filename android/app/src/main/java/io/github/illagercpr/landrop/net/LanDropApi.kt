package io.github.illagercpr.landrop.net

import io.github.illagercpr.landrop.data.prefs.Connection
import io.github.illagercpr.landrop.protocol.ApiErrorDto
import io.github.illagercpr.landrop.protocol.ApiPath
import io.github.illagercpr.landrop.protocol.CreateUploadRequestDto
import io.github.illagercpr.landrop.protocol.CreateUploadResponseDto
import io.github.illagercpr.landrop.protocol.MessageDto
import io.github.illagercpr.landrop.protocol.MessagePageDto
import io.github.illagercpr.landrop.protocol.PairRequestDto
import io.github.illagercpr.landrop.protocol.PairResponseDto
import io.github.illagercpr.landrop.protocol.SendMessageRequestDto
import io.github.illagercpr.landrop.protocol.ServerInfoDto
import io.github.illagercpr.landrop.protocol.UploadListDto
import io.github.illagercpr.landrop.protocol.UploadPatchResponseDto
import io.github.illagercpr.landrop.protocol.UploadStatusDto
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** 服务端返回的非 2xx 响应。 */
class ApiException(
    val statusCode: Int,
    val errorCode: String,
    val errorBody: ApiErrorDto? = null,
) : IOException("HTTP $statusCode $errorCode")

/** HTTP 层之上的一薄层：只负责拼请求、解 JSON、把错误翻译成 [ApiException]。 */
class LanDropApi(
    private val client: OkHttpClient,
    private val json: Json,
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /** 服务端元信息。未配对也可调用，是「地址填对了没」的第一道校验。 */
    suspend fun info(baseUrl: String): ServerInfoDto =
        decode(execute(Request.Builder().url("$baseUrl${ApiPath.INFO}").get().build()))

    /** 用一次性配对码换取长期凭据。 */
    suspend fun pair(
        baseUrl: String,
        code: String,
        deviceName: String,
        platform: String,
    ): PairResponseDto {
        val body = json.encodeToString(
            PairRequestDto.serializer(),
            PairRequestDto(code = code, deviceName = deviceName, platform = platform),
        )
        val request = Request.Builder()
            .url("$baseUrl${ApiPath.PAIR}")
            .post(body.toRequestBody(jsonMediaType))
            .build()
        return decode(execute(request))
    }

    /** 增量拉取消息；`since` 语义是严格大于，首次全量传 0。 */
    suspend fun listMessages(connection: Connection, since: Long, limit: Int): MessagePageDto {
        val request = authorized(connection, "${ApiPath.MESSAGES}?since=$since&limit=$limit")
            .get()
            .build()
        return decode(execute(request))
    }

    /** 发送文字或链接（文件走上传接口）。 */
    suspend fun sendText(connection: Connection, text: String, kind: String = "text"): MessageDto {
        val body = json.encodeToString(
            SendMessageRequestDto.serializer(),
            SendMessageRequestDto(kind = kind, text = text),
        )
        val request = authorized(connection, ApiPath.MESSAGES)
            .post(body.toRequestBody(jsonMediaType))
            .build()
        return decode(execute(request))
    }

    /** 创建上传会话；返回的 `receivedBytes` 是续传锚点（新会话恒为 0）。 */
    suspend fun createUpload(
        connection: Connection,
        name: String,
        size: Long,
        mime: String?,
    ): CreateUploadResponseDto {
        val body = json.encodeToString(
            CreateUploadRequestDto.serializer(),
            CreateUploadRequestDto(name = name, size = size, mime = mime, sha256 = null),
        )
        val request = authorized(connection, ApiPath.UPLOADS)
            .post(body.toRequestBody(jsonMediaType))
            .build()
        return decode(execute(request))
    }

    /**
     * 追加一个分片。
     *
     * `offset` 必须等于服务端已收字节数，否则服务端回 409 并带上真实进度，
     * 由调用方（[io.github.illagercpr.landrop.data.repo.TransferRepository]）重新对齐。
     */
    suspend fun uploadChunk(
        connection: Connection,
        uploadId: String,
        offset: Long,
        chunk: ByteArray,
        length: Int,
    ): UploadPatchResponseDto {
        val request = authorized(connection, "${ApiPath.UPLOADS}/$uploadId?offset=$offset")
            .patch(chunk.toRequestBody(OCTET_STREAM, 0, length))
            .build()
        return decode(execute(request))
    }

    /** 收尾：服务端校验 sha256、把临时文件移到正式目录并落一条文件消息。 */
    suspend fun completeUpload(connection: Connection, uploadId: String): MessageDto {
        val request = authorized(connection, "${ApiPath.UPLOADS}/$uploadId/complete")
            .post(EMPTY_JSON_BODY)
            .build()
        return decode(execute(request))
    }

    /** 中止上传并让服务端删除临时分片。 */
    suspend fun abortUpload(connection: Connection, uploadId: String) {
        val request = authorized(connection, "${ApiPath.UPLOADS}/$uploadId").delete().build()
        execute(request).close()
    }

    /**
     * 询问服务端某个上传会话收了多少字节——断点续传的锚点。
     *
     * 会话已被回收（404）或被中止时抛 [ApiException]，调用方据此改为重新建会话。
     */
    suspend fun uploadStatus(connection: Connection, uploadId: String): UploadStatusDto {
        val request = authorized(connection, "${ApiPath.UPLOADS}/$uploadId").get().build()
        return decode(execute(request))
    }

    /** 列出本设备在服务端的上传会话；`state` 为空表示不过滤。 */
    suspend fun listUploads(connection: Connection, state: String? = null): UploadListDto {
        val query = if (state != null) "?state=$state" else ""
        val request = authorized(connection, "${ApiPath.UPLOADS}$query").get().build()
        return decode(execute(request))
    }

    /**
     * 下载文件的请求（不发送，交给调用方流式消费）。
     *
     * [rangeFrom] 非空时带 `Range: bytes=from-`，用于断点续传；
     * 鉴权走 `?token=`——下载可能被交给外部组件（如系统下载器），带不了自定义头。
     */
    fun downloadRequest(connection: Connection, fileId: String, rangeFrom: Long?): Request {
        val builder = Request.Builder()
            .url("${connection.baseUrl}${ApiPath.FILES}/$fileId?token=${connection.deviceToken}")
            .get()
        if (rangeFrom != null && rangeFrom > 0) {
            builder.header("Range", "bytes=$rangeFrom-")
        }
        return builder.build()
    }

    /** 打开下载流。返回的 [Response] 由调用方关闭。 */
    suspend fun openDownload(connection: Connection, fileId: String, rangeFrom: Long?): Response {
        val response = client.newCall(downloadRequest(connection, fileId, rangeFrom)).await()
        if (!response.isSuccessful) {
            val error = response.use { readError(it) }
            throw error
        }
        return response
    }

    // ------------------------------------------------------------------ 内部

    private fun authorized(connection: Connection, pathAndQuery: String): Request.Builder =
        Request.Builder()
            .url("${connection.baseUrl}$pathAndQuery")
            .header("Authorization", "Bearer ${connection.deviceToken}")

    private suspend fun execute(request: Request): Response {
        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            throw response.use { readError(it) }
        }
        return response
    }

    private fun readError(response: Response): ApiException {
        val raw = runCatching { response.body.string() }.getOrDefault("")
        val parsed = runCatching { json.decodeFromString(ApiErrorDto.serializer(), raw) }.getOrNull()
        return ApiException(
            statusCode = response.code,
            errorCode = parsed?.error ?: "http_${response.code}",
            errorBody = parsed,
        )
    }

    private inline fun <reified T> decode(response: Response): T =
        response.use { json.decodeFromString<T>(it.body.string()) }

    companion object {
        val OCTET_STREAM = "application/octet-stream".toMediaType()

        /**
         * 收尾请求的请求体。
         *
         * 服务端该路由是「无业务入参的 POST」，但 Fastify 对 `application/json`
         * 的空请求体不友好，因此显式发一个 `{}`——与 Web 端 `api.ts` 的做法一致。
         */
        private val EMPTY_JSON_BODY: RequestBody =
            "{}".toRequestBody("application/json; charset=utf-8".toMediaType())
    }
}

/** [Call] 的挂起封装：协程取消时同步取消网络调用。 */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            // 协程已取消时不能再 resume，否则响应体泄漏（连接池占着不还）
            if (continuation.isActive) continuation.resume(response) else response.close()
        }

        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isCancelled) return
            continuation.resumeWithException(e)
        }
    })
    continuation.invokeOnCancellation { runCatching { cancel() } }
}

/** 供上传循环使用：把输入流转成固定大小的分片读取。 */
internal fun InputStream.readChunk(buffer: ByteArray): Int {
    var total = 0
    while (total < buffer.size) {
        val read = read(buffer, total, buffer.size - total)
        if (read < 0) break
        total += read
    }
    return total
}
