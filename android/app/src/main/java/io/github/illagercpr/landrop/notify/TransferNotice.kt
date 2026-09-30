package io.github.illagercpr.landrop.notify

import io.github.illagercpr.landrop.data.local.TransferDirection
import io.github.illagercpr.landrop.data.local.TransferEntity
import io.github.illagercpr.landrop.data.local.TransferState
import io.github.illagercpr.landrop.ui.common.formatBytes

/**
 * 传输通知的文案与进度计算。
 *
 * 刻意与 Android 框架解耦（只吃 [TransferEntity] 这种纯数据类）：「多个传输怎么
 * 合并成一条通知」「哪些状态才该提醒」这两处最容易算错、也最难在真机上反复验证，
 * 放在这里就能在 JVM 单测里把边界直接钉死。
 */
object TransferNotice {

    /** 进度条满值。用千分比而不是百分比：小文件会长时间停在 0%，看起来像卡住了。 */
    const val MAX_PROGRESS = 1000

    /** 一条通知的内容。 */
    data class Content(
        val title: String,
        val text: String,
        /** 右侧小字（速率）；为 null 就不显示。 */
        val subText: String?,
        /** 进度条当前值；[max] 为 0 表示总量未知，改用不确定进度条。 */
        val progress: Int,
        val max: Int,
    )

    /** 是否属于「正在跑」——只有这些状态值得占用前台服务。 */
    fun isActive(state: String): Boolean =
        state == TransferState.QUEUED || state == TransferState.RUNNING

    /**
     * 把所有进行中的传输压成一条通知；一个都没有时返回 null。
     *
     * 传 null 是调用方收掉前台服务的信号，所以这里必须与 [isActive] 用同一个判据。
     */
    fun describe(transfers: List<TransferEntity>, speedBytesPerSec: Long?): Content? {
        val active = transfers.filter { isActive(it.state) }
        if (active.isEmpty()) return null

        val done = active.sumOf { it.transferredBytes.coerceAtLeast(0) }
        val total = active.sumOf { it.totalBytes.coerceAtLeast(0) }
        val paused = transfers.count { it.state == TransferState.PAUSED }

        val title: String
        val body: String
        if (active.size == 1) {
            val only = active.single()
            title = if (only.direction == TransferDirection.UPLOAD) "正在发送" else "正在接收"
            body = "${only.fileName} · ${formatBytes(done)} / ${formatBytes(total)}"
        } else {
            title = "正在传输 ${active.size} 个文件"
            body = "${formatBytes(done)} / ${formatBytes(total)}"
        }

        return Content(
            title = title,
            text = if (paused > 0) "$body（另有 $paused 个已暂停）" else body,
            subText = speedBytesPerSec?.takeIf { it > 0 }?.let { "${formatBytes(it)}/s" },
            progress = if (total > 0) {
                ((done * MAX_PROGRESS) / total).coerceIn(0L, MAX_PROGRESS.toLong()).toInt()
            } else {
                0
            },
            max = if (total > 0) MAX_PROGRESS else 0,
        )
    }

    /**
     * 一条传输结束时该不该提醒、提醒什么；不该提醒时返回 null。
     *
     * 判据是**用户有没有主动暂停**，而不是状态本身：
     *   - 主动暂停走 `setStateIfExists(..., PAUSED, null)`，`error` 是空的——
     *     用户自己按的按钮，他心里有数，再响一声纯属噪声；
     *   - 网络中断落到「已暂停」时带上了原因（`error` 非空），此时**必须**提醒：
     *     屏幕关着的人不会知道文件已经静悄悄地不传了。
     *
     * 取消同理不提醒——那是用户自己的动作。失败要提醒，它丢的是进度。
     */
    fun result(transfer: TransferEntity): Content? {
        val upload = transfer.direction == TransferDirection.UPLOAD

        return when (transfer.state) {
            TransferState.COMPLETED -> Content(
                title = if (upload) "文件已送达" else "文件已收到",
                text = "${transfer.fileName} · ${formatBytes(transfer.totalBytes)}",
                subText = null,
                progress = 0,
                max = 0,
            )

            TransferState.FAILED -> Content(
                title = if (upload) "发送失败" else "接收失败",
                text = "${transfer.fileName} · ${transfer.error ?: "未知原因"}",
                subText = null,
                progress = 0,
                max = 0,
            )

            TransferState.PAUSED -> transfer.error
                ?.takeIf { it.isNotBlank() }
                ?.let { reason ->
                    Content(
                        title = "传输已暂停",
                        text = "${transfer.fileName} · $reason",
                        subText = null,
                        progress = 0,
                        max = 0,
                    )
                }

            else -> null
        }
    }
}
