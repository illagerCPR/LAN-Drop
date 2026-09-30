package io.github.illagercpr.landrop.notify

import android.app.NotificationManager
import android.content.Context
import io.github.illagercpr.landrop.data.local.MessageEntity
import io.github.illagercpr.landrop.data.repo.NewMessageNotifier
import io.github.illagercpr.landrop.protocol.MessageKind

/**
 * 新消息的系统通知。
 *
 * 只在应用不在前台时发：用户正看着聊天页，消息本来就会自己出现在时间线上。
 * （[AppVisibility] 由 Activity 的 onStart/onStop 维护，这里只读。）
 *
 * 收文件只提醒「对方发来文件」，不自动下载——落盘位置、是否要这个文件都该由
 * 用户决定，静默占用手机存储不是这个工具该做的事。
 */
class MessageNotifier(private val context: Context) : NewMessageNotifier {

    override fun onMessage(message: MessageEntity) {
        if (AppVisibility.foreground) return

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val title = message.senderName.ifBlank { "对方" }
        val text = if (message.kind == FILE_KIND) {
            "发来文件：${message.fileName ?: "未命名文件"}"
        } else {
            message.text.orEmpty().ifBlank { "（空消息）" }
        }

        // 固定 ID：后来的消息覆盖前一条，和聊天应用的行为一致
        manager.notify(Notifications.MESSAGE_ID, Notifications.message(context, title, text))
    }

    private companion object {
        /** 协议里 kind 是小写字符串（见 `Mappers.toEntity`）。 */
        val FILE_KIND = MessageKind.FILE.name.lowercase()
    }
}
