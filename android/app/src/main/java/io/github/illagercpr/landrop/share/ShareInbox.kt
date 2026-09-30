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
 * 系统分享的「收件箱」：无界面中转（[ShareTrampolineActivity]）投递，界面消费。
 *
 * 为什么不让界面直接调 ViewModel：中转是**先投递、再拉起 MainActivity**，冷启动分享时
 * 内容早于 Compose 首次组合就位，「先到的分享」等界面准备好再被取走；而且配置变更
 * （旋转）会重建 Activity，收件箱在进程里，内容不会丢。
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
 * 一条 URI 所指「媒体条目」的身份串，用来判断两条 URI 是不是同一个东西。
 *
 * **为什么不能比 URI 字符串**：相册分享单张照片时，`EXTRA_STREAM` 与 `ClipData` 里装的是
 * 同一张照片的**两种形态**。实测 vivo 相册发来的两条是：
 *   - `content://media/external/images/media/1000102703`（在 `EXTRA_STREAM`）
 *   - `content://media/external/file/1000102703`（在 `ClipData`）
 * 字符串不相等 → 字符串去重直接失效 → 同一张照片被排进队列两次，用户看到「上传了两份」。
 *
 * 这两种形态指向同一行是有依据的，不是巧合：MediaProvider 里 `images`/`video`/`audio`
 * 都是 `files` 表之上的**视图**（`CREATE VIEW images AS SELECT … FROM files WHERE media_type=1`），
 * 因此同一卷内「行 id」唯一标识一个文件，形态里的集合名只是过滤条件不同。
 * 设备上也核对过：两种形态查出来的 `_id` / `_display_name` / `_size` 完全一致。
 * 所以 `content://media/<卷>/<集合>[/<子集合>]/<id>` 这种单项 URI，身份取 `<卷>:<id>`。
 *
 * 两个刻意的保守点：
 *   - **只认白名单里的集合形态**（`file`、`downloads` 是 `files` 表本身或它的视图，
 *     `images`/`video`/`audio` 的 `media` 子集合是它的视图）。典型反例：
 *     `content://media/external/images/thumbnails/<id>` 是**独立**的缩略图表，
 *     它的 id 与 `file` 表毫无关系——只按「最后一段数字」合并就会把缩略图和别的文件并成一个。
 *   - **带 `?` 或 `#` 的一律不合并**：`?width=`/`?height=` 这类参数会改变实际取到的内容
 *     （MediaStore 支持按尺寸取变体），那就不是同一个东西了。
 *
 * 拿不准的一律原样返回，即退回字符串比较：**宁可重复上传，也不误合并**。
 * 重复上传是看得见、忍得住的；把两个不同文件并成一个，用户会静默少收到一个文件。
 */
internal fun mediaIdentityOf(uri: String): String {
    val prefix = "content://media/"
    if (!uri.startsWith(prefix)) return uri
    if (uri.contains('?') || uri.contains('#')) return uri

    val segments = uri.removePrefix(prefix).split('/')
    val collection = when (segments.size) {
        // content://media/<卷>/file/<id>、…/downloads/<id>：files 表本身或它的视图
        3 -> segments[1]
        // content://media/<卷>/images|video|audio/media/<id>：files 表之上的视图
        4 -> if (segments[2] == "media") "${segments[1]}/media" else return uri
        else -> return uri
    }
    if (collection !in MEDIA_COLLECTIONS) return uri

    val id = segments.last().toLongOrNull() ?: return uri
    if (id < 0) return uri

    return "media:${segments[0]}:$id"
}

/** [mediaIdentityOf] 认的集合形态；每一个都必须是 `files` 表本身或它在 MediaProvider 里的视图。 */
private val MEDIA_COLLECTIONS = setOf("file", "downloads", "images/media", "video/media", "audio/media")

/**
 * 按条目身份去重，保留首次出现的那个 URI（`EXTRA_STREAM` 在 `ClipData` 之前）。
 *
 * 非媒体 URI 的身份就是它自身，于是这一步同时也完成了原先的字符串去重。
 */
internal fun dedupeUris(uris: List<String>): List<String> {
    val seen = HashSet<String>(uris.size)
    val deduped = ArrayList<String>(uris.size)
    for (uri in uris) {
        if (!seen.add(mediaIdentityOf(uri))) continue
        deduped += uri
    }
    return deduped
}

/**
 * 归一化分享内容。
 *
 * 几个必须处理的现实情况：
 *   - **同一批 URI 会同时出现在 `EXTRA_STREAM` 与 `ClipData` 里**（系统相册、文件管理器
 *     都这么发），而且**两边可能写成同一张照片的两种 URI 形态**，去重必须按条目身份做
 *     （见 [mediaIdentityOf]），否则同一张照片会发两遍；
 *   - 有些应用只填 `ClipData`（`EXTRA_STREAM` 为空），有些只填 `EXTRA_STREAM`，两者都要读；
 *   - `Intent.data` 作为**兜底**：少数发送方把文件放在 data 里而不填 EXTRA_STREAM。
 *     只在另两处都没有文件时采用，避免把「data 里放的是别的语义」误当文件；
 *   - 文字可能是空白串，空白不算内容；
 *   - 什么都没有时返回 null，让调用方安静忽略。
 */
internal fun normalizeShare(raw: RawShare): NormalizedShare? {
    if (raw.action != Intent.ACTION_SEND && raw.action != Intent.ACTION_SEND_MULTIPLE) return null

    val text = raw.text?.takeIf { it.isNotBlank() }

    val uris = dedupeUris((raw.streamUris + raw.clipUris).filter { it.isNotBlank() })
    val files =
        if (uris.isNotEmpty()) uris
        else listOfNotNull(raw.dataUri?.takeIf { it.isNotBlank() })

    if (text == null && files.isEmpty()) return null
    return NormalizedShare(text = text, uris = files)
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
