package io.github.illagercpr.landrop.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import io.github.illagercpr.landrop.MainActivity
import io.github.illagercpr.landrop.R
import java.util.concurrent.atomic.AtomicInteger

/**
 * 通知渠道与通知构造。
 *
 * 之所以一开始就分三条渠道，是因为渠道的重要性**一旦建立就只有用户能改**，
 * 应用再也调不回来；而这三类消息的打扰等级本来就不同：
 *   - [CHANNEL_PROGRESS] 低：传输进度，常驻但不出声；
 *   - [CHANNEL_RESULT] 中：传输完成/中断，值得响一声；
 *   - [CHANNEL_MESSAGE] 中：聊天新消息。
 *
 * 合成一条「LAN-Drop 通知」也能跑，但用户想静音进度、保留消息提醒时就无路可走了。
 */
object Notifications {

    const val CHANNEL_PROGRESS = "transfer_progress"
    const val CHANNEL_RESULT = "transfer_result"
    const val CHANNEL_MESSAGE = "message"

    /** 常驻进度通知的固定 ID：它同时是前台服务的通知。 */
    const val ONGOING_ID = 1001

    /** 新消息固定用同一个 ID：后来的消息覆盖前一条，和聊天应用的行为一致。 */
    const val MESSAGE_ID = 1002

    /** 前台服务配额耗尽时的那条提醒。 */
    const val TIMEOUT_ID = 1003

    /**
     * 结果通知每来一条换一个 ID，这样才能堆叠（三个文件传完就是三条）。
     * 只在固定区间里循环，避免长时间运行把 ID 涨到无意义的数值。
     */
    private const val RESULT_ID_BASE = 2000
    private const val RESULT_ID_SPAN = 64
    private val resultSeq = AtomicInteger()

    private const val PENDING_FLAGS =
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    /** 幂等；[io.github.illagercpr.landrop.LanDropApp] 启动时调一次即可。 */
    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    CHANNEL_PROGRESS,
                    "传输进度",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "传输进行中的常驻进度，不发出声音"
                    setShowBadge(false)
                },
                NotificationChannel(
                    CHANNEL_RESULT,
                    "传输结果",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "传输完成、失败或中断的提醒"
                },
                NotificationChannel(
                    CHANNEL_MESSAGE,
                    "新消息",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "对方发来的文字或文件消息"
                },
            ),
        )
    }

    /** 结果通知的下一个 ID。 */
    fun nextResultId(): Int =
        RESULT_ID_BASE + (resultSeq.getAndIncrement() % RESULT_ID_SPAN)

    /**
     * 前台服务的占位通知。
     *
     * `startForeground` 必须在 5 秒内调用，而那一刻我们还没从数据库读到进度，
     * 只能先挂这条；收集器拿到第一帧就把它换成真实进度。
     */
    fun preparing(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("LAN-Drop")
            .setContentText("正在准备传输…")
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setProgress(0, 0, true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    /**
     * 常驻进度通知。
     *
     * `setForegroundServiceBehavior(IMMEDIATE)` 让它在 Android 12+ 上立即显示——
     * 默认行为是新前台服务的通知可能被推迟约 10 秒才露面，对「点了发送就没反应」
     * 的观感来说那 10 秒很致命。
     */
    fun ongoing(
        context: Context,
        content: TransferNotice.Content,
        actions: List<NotificationCompat.Action>,
    ): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        content.subText?.let(builder::setSubText)
        if (content.max > 0) {
            builder.setProgress(content.max, content.progress, false)
        } else {
            builder.setProgress(0, 0, true)
        }
        actions.forEach(builder::addAction)

        return builder.build()
    }

    /** 传输结果通知：可点可划走，不进前台状态。 */
    fun result(context: Context, content: TransferNotice.Content): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)

        content.subText?.let(builder::setSubText)
        return builder.build()
    }

    /** 新消息通知。 */
    fun message(context: Context, title: String, text: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_MESSAGE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()

    /** 点通知回到应用。 */
    fun openApp(context: Context): PendingIntent {
        // CLEAR_TOP + SINGLE_TOP：把已有的任务提到前台并复用同一个 Activity 实例，
        // 而不是在聊天页上面再压一个一模一样的聊天页。
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(context, 0, intent, PENDING_FLAGS)
    }

    /** 通知按钮 → [TransferService] 的动作。 */
    fun serviceAction(
        context: Context,
        action: String,
        label: String,
        transferId: String,
    ): NotificationCompat.Action {
        val intent = Intent(context, TransferService::class.java).apply {
            setAction(action)
            putExtra(TransferService.EXTRA_TRANSFER_ID, transferId)
        }

        // PendingIntent 的相等性只看 requestCode + Intent 的「过滤等价」部分，
        // 不含 extra；把传输 ID 掺进 requestCode，两条不同传输的按钮才不会互相覆盖。
        val requestCode = action.hashCode() * 31 + transferId.hashCode()
        val pending = PendingIntent.getService(context, requestCode, intent, PENDING_FLAGS)

        return NotificationCompat.Action.Builder(0, label, pending).build()
    }
}
