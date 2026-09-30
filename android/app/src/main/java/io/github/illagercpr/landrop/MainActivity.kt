package io.github.illagercpr.landrop

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.illagercpr.landrop.notify.AppVisibility
import io.github.illagercpr.landrop.share.ShareInbox
import io.github.illagercpr.landrop.share.toSharedPayload
import io.github.illagercpr.landrop.ui.AppRoot
import io.github.illagercpr.landrop.ui.theme.LanDropTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // 冷启动分享（应用没在跑时从别的应用分享过来）走这里。
        // 必须在 setContent 之前投递：Compose 首次组合就会去收件箱取，投晚了要等下一次重组。
        //
        // 这里**不要**用 `savedInstanceState == null` 之类的判断去防重复投递：
        // 分享 Intent 会成为本任务（task）的基础 Intent，任务带着保存状态重建时
        // 该判断为假，结果是「用户分享过来什么都没发生」——实测踩过，比偶尔重复更糟。
        // 重复投递的正确解法是让分享 Intent 不成为任务基础 Intent（独立的中转 Activity），
        // 见 docs/技术选型与开发计划.md 的 P4-2 已知问题。
        publishShare(intent)

        setContent {
            LanDropTheme {
                AppRoot()
            }
        }
    }

    /**
     * 应用已在栈顶时（manifest 里 MainActivity 是 `singleTop`）系统走这里，
     * 不会再建一个 Activity 实例。
     *
     * `setIntent` 是必须的：Activity 重建（旋转屏幕）时框架会重放 `getIntent()`，
     * 不更新它就会把上一次的分享再发一遍。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        publishShare(intent)
    }

    private fun publishShare(intent: Intent?) {
        val payload = intent?.toSharedPayload() ?: return
        ShareInbox.submit(payload)
    }

    /**
     * 界面可见性只用于「该不该发系统通知」这一个判断（见 [AppVisibility]）。
     *
     * onStop 的语义正好是「用户看不见了」：息屏、切后台、被别的应用盖住都会触发，
     * 而单 Activity 应用不需要额外处理配置变更——配置变更会走 onStop→onStart，
     * 中间那一瞬的「不可见」最多让一条通知多发一次，不影响正确性。
     */
    override fun onStart() {
        super.onStart()
        AppVisibility.foreground = true
    }

    override fun onStop() {
        AppVisibility.foreground = false
        super.onStop()
    }
}
