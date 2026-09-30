package io.github.illagercpr.landrop.data.prefs

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 应用级开关偏好。
 *
 * 与 [ConnectionStore] 分开存：解除配对会清空连接凭据，
 * 但「自动接收文件」是用户对这台手机的偏好，不该跟着凭据一起消失。
 */
class SettingsStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _autoReceiveFiles = MutableStateFlow(prefs.getBoolean(KEY_AUTO_RECEIVE, false))

    /**
     * 收到对方发来的文件时是否自动开始下载。
     *
     * **默认关闭**是刻意的产品取舍：自动落盘意味着存储被静默占用
     * （对方发多大就下多大），以及未确认内容的自动写入，两者都应该由用户显式同意。
     */
    val autoReceiveFiles: StateFlow<Boolean> = _autoReceiveFiles.asStateFlow()

    /**
     * 开启自动接收的时刻；只自动下载**晚于该时刻**到达的文件消息。
     *
     * 没有这道闸，开启后第一次全量同步会把服务端历史里的所有文件全部拉下来——
     * 那不是用户开启开关时想要的事。重新开启会重置基准点。
     */
    val autoReceiveSince: Long
        get() = prefs.getLong(KEY_AUTO_RECEIVE_SINCE, 0L)

    fun setAutoReceiveFiles(enabled: Boolean) {
        val wasEnabled = _autoReceiveFiles.value
        prefs.edit {
            putBoolean(KEY_AUTO_RECEIVE, enabled)
            if (enabled && !wasEnabled) {
                putLong(KEY_AUTO_RECEIVE_SINCE, System.currentTimeMillis())
            }
        }
        _autoReceiveFiles.value = enabled
    }

    private companion object {
        private const val PREFS_NAME = "lan-drop.settings"
        private const val KEY_AUTO_RECEIVE = "auto_receive_files"
        private const val KEY_AUTO_RECEIVE_SINCE = "auto_receive_since"
    }
}
