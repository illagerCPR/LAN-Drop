package io.github.illagercpr.landrop.share

import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/** 一条从系统分享面板进来的内容：文字、若干文件，或两者都有。 */
data class SharedPayload(
    val text: String?,
    val uris: List<Uri>,
)

/**
 * 系统分享的「收件箱」：Activity 投递，界面消费。
 *
 * 为什么不让 Activity 直接调 ViewModel：冷启动分享时 `onCreate` 里的 Intent 早于
 * Compose 首次组合与 ViewModel 建立，投进 StateFlow 才能让「先到的分享」等界面准备好
 * 再被取走；而且配置变更（旋转）会重建 Activity，收件箱在进程里，内容不会丢。
 *
 * 消费是**取出即清空**（[consume]），否则重组会重复发送同一批文件。
 */
object ShareInbox {

    private val _payload = MutableStateFlow<SharedPayload?>(null)
    val payload: StateFlow<SharedPayload?> = _payload.asStateFlow()

    fun submit(payload: SharedPayload) {
        _payload.value = payload
    }

    /** 取出并清空；没有内容时返回 null。 */
    fun consume(): SharedPayload? = _payload.getAndUpdate { null }
}

/**
 * Intent 里与分享有关的原始字段。
 *
 * 抽出来是为了**可单测**：`Intent` 与 `Uri` 都来自 Android 框架，纯 JVM 单测里拿不到，
 * 而「合并 EXTRA_STREAM 与 ClipData、去重、判定空分享」恰恰是最容易写错、也最值得测的一段。
 */
internal data class RawShare(
    val action: String?,
    val text: String?,
    val streamUris: List<String>,
    val clipUris: List<String>,
    /** `Intent.data`：兜底来源，见 [normalizeShare]。 */
    val dataUri: String? = null,
)

/** 归一化后的分享内容；[RawShare] 里没有任何可用内容时返回 null。 */
internal data class NormalizedShare(
    val text: String?,
    val uris: List<String>,
)

/**
 * 归一化分享内容。
 *
 * 几个必须处理的现实情况：
 *   - **同一批 URI 会同时出现在 `EXTRA_STREAM` 与 `ClipData` 里**（系统相册、文件管理器
 *     都这么发），不去重就会把同一张照片发两遍；
 *   - 有些应用只填 `ClipData`（`EXTRA_STREAM` 为空），有些只填 `EXTRA_STREAM`，两者都要读；
 *   - `Intent.data` 作为**兜底**：少数发送方把文件放在 data 里而不填 EXTRA_STREAM。
 *     只在另两处都没有文件时采用，避免把「data 里放的是别的语义」误当文件；
 *   - 文字可能是空白串，空白不算内容；
 *   - 什么都没有时返回 null，让调用方安静忽略。
 */
internal fun normalizeShare(raw: RawShare): NormalizedShare? {
    if (raw.action != Intent.ACTION_SEND && raw.action != Intent.ACTION_SEND_MULTIPLE) return null

    val text = raw.text?.takeIf { it.isNotBlank() }
    val uris = LinkedHashSet<String>()
    raw.streamUris.forEach { if (it.isNotBlank()) uris += it }
    raw.clipUris.forEach { if (it.isNotBlank()) uris += it }
    if (uris.isEmpty()) raw.dataUri?.takeIf { it.isNotBlank() }?.let { uris += it }

    if (text == null && uris.isEmpty()) return null
    return NormalizedShare(text = text, uris = uris.toList())
}

/**
 * 把系统分享 Intent 解析成 [SharedPayload]；不是分享、或没有内容时返回 null。
 *
 * **URI 授权是临时的**：`ACTION_SEND` 给的是本进程存续期内的读权限，不能
 * `takePersistableUriPermission`（会抛 SecurityException，传输层已用 runCatching 兜住）。
 * 因此分享进来的文件必须**当场传完**；进程被系统杀掉后重新开始上传会打不开源文件，
 * 那条传输会落成「源文件不可读」的永久失败——这是刻意的取舍，界面上如实提示。
 */
fun Intent.toSharedPayload(): SharedPayload? {
    val action = this.action

    val streamUris = readStreamExtra()

    val clipUris = mutableListOf<String>()
    clipData?.let { clip ->
        for (index in 0 until clip.itemCount) {
            clip.getItemAt(index).uri?.let { clipUris += it.toString() }
        }
    }

    // data 只在 content/file 协议下当文件用；http(s) 之类交给别的应用更合适
    val dataString = data?.toString()?.takeIf {
        it.startsWith("content://") || it.startsWith("file://")
    }

    val normalized = normalizeShare(
        RawShare(
            action = action,
            text = getStringExtra(Intent.EXTRA_TEXT),
            streamUris = streamUris,
            clipUris = clipUris,
            dataUri = dataString,
        ),
    ) ?: return null

    return SharedPayload(
        text = normalized.text,
        uris = normalized.uris.map(Uri::parse),
    )
}

/**
 * 读 `EXTRA_STREAM`，宽容对待不规范的发信方。
 *
 * 规范里这里是 `Uri`（ACTION_SEND）或 `ArrayList<Uri>`（ACTION_SEND_MULTIPLE），
 * 但现实中有应用往里塞 `String` / `String[]` / `ArrayList<String>`（老应用与部分厂商 ROM）。
 * 直接按 Uri 读会在遍历元素时抛 `ClassCastException`——**分享一下就把应用崩掉**是不可接受的，
 * 所以先按规范读，失败或为空再按字符串读一遍。
 */
private fun Intent.readStreamExtra(): List<String> {
    val uris = mutableListOf<String>()

    runCatching {
        if (action == Intent.ACTION_SEND_MULTIPLE) {
            getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                ?.forEach { uris += it.toString() }
        } else {
            getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let { uris += it.toString() }
        }
    }
    if (uris.isNotEmpty()) return uris

    runCatching {
        when (val raw = extras?.get(Intent.EXTRA_STREAM)) {
            is String -> if (raw.isNotBlank()) uris += raw
            is Array<*> -> raw.filterIsInstance<String>().forEach { if (it.isNotBlank()) uris += it }
            is Iterable<*> -> raw.filterIsInstance<String>().forEach { if (it.isNotBlank()) uris += it }
        }
    }
    return uris
}
