package io.github.illagercpr.landrop

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.illagercpr.landrop.notify.AppVisibility
import io.github.illagercpr.landrop.ui.AppRoot
import io.github.illagercpr.landrop.ui.theme.LanDropTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            LanDropTheme {
                AppRoot()
            }
        }
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
