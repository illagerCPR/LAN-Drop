package io.github.illagercpr.landrop.share

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import io.github.illagercpr.landrop.MainActivity

/**
 * 系统分享的无界面中转（P4-2 已知问题的修复）。
 *
 * 为什么它必须存在：分享 Intent 若由 MainActivity 直接接收，会成为整个任务（task）的
 * **基础 Intent**。进程被强杀后系统重建任务时，会拿基础 Intent 重建根 Activity——
 * 同一次分享被解析第二遍，实测重复上传过。把 intent-filter 挪到本 Activity 后：
 *   - 分享 Intent 只成为**本** Activity 短命任务的基础 Intent；本 Activity 立即 finish，
 *     任务瞬生瞬灭（`excludeFromRecents` + `taskAffinity=""`），不会出现在最近任务里；
 *   - MainActivity 任务的 base Intent 永远干净，任务重建时没有分享可重放。
 *
 * 分享内容**只走进程级 [ShareInbox]，不走转发 Intent**：转发 Intent 是一个不带分享数据的
 * 普通启动 Intent，冷启动时就算它被系统存成任务基础 Intent，再怎么重放也解析不出分享。
 *
 * **URI 读权限必须随路转移**：`ACTION_SEND` 授予的读权限归接收它的 Activity 所有，
 * 该 Activity 销毁时授权随之吊销。中转拿到授权后直接 finish，MainActivity 就再也读不到
 * 源文件，文件分享全体失效。转移走的是系统标准机制：把 URI 装进转发 Intent 的
 * `ClipData` 并带 `FLAG_GRANT_READ_URI_PERMISSION`，系统在中转仍持有授权的前提下
 * 重新授予目标 Activity——授权寿命仍是「本进程存续期」，与改造前语义一致。
 */
class ShareTrampolineActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val payload = intent.toSharedPayload()
        if (payload != null) {
            // 先投收件箱、再拉主界面：冷启动时这一步早于 MainActivity 的首次组合，
            // 界面一就绪就能取走；应用已在运行时，重组/回到前台也会先取收件箱。
            ShareInbox.submit(payload)
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    attachReadGrants(payload.uris)
                },
            )
        }
        // 不是分享（或没有可分享的内容）：安静结束，不为垃圾输入打开主界面。
        finish()
    }
}

/**
 * 把 URI 装进转发 Intent 的 `ClipData` 并带 READ 标记，让系统把本 Activity 持有的
 * 读授权转移到目标 Activity。
 *
 * URI 按解析后的原样放（去重/合并已在 payload 构建时完成，这里不改写任何字符串形态）；
 * `newRawUri` 的 label 只是调试标识。目标 Activity 收到后授权归它所有，寿命依旧是
 * 「本进程存续期」——进程被杀授权消失，与改造前相同，是刻意保留的取舍。
 */
private fun Intent.attachReadGrants(uris: List<Uri>) {
    if (uris.isEmpty()) return
    val clip = ClipData.newRawUri("landrop-share", uris.first())
    for (uri in uris.drop(1)) clip.addItem(ClipData.Item(uri))
    clipData = clip
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
