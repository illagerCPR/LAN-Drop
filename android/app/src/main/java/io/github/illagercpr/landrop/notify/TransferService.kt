package io.github.illagercpr.landrop.notify

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.illagercpr.landrop.LanDropApp
import io.github.illagercpr.landrop.data.local.TransferEntity
import io.github.illagercpr.landrop.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 传输期间的前台服务：让大文件在息屏 / 切后台时继续传。
 *
 * ## 为什么必须有它
 *
 * 传输协程挂在应用级作用域上，进程活着它就跑。问题在于进程本身：应用退到后台后
 * 系统会冻结它（cached app freezer），息屏久了更会直接回收。以本项目实测的
 * 0.5~1.3 MB/s，1 GB 要传十几分钟到半小时——用户必然中途锁屏。没有前台服务，
 * 传输会被冻在半路；有了它，进程处于「用户可感知」状态，不受后台执行限制。
 *
 * ## 生命周期跟着「有没有活在跑」，而不是一直常驻
 *
 * 服务由 [TransferRepository] 在开始传输时拉起，自己订阅 Room 决定何时退出：
 * 所有传输都离开 [TransferNotice.isActive] 后，等一小段宽限期（连续发多个文件时
 * 不必反复起停）再 `stopSelf()`。
 *
 * **刻意不做常驻接收**：本应用的收文件是「对方发来消息 → 用户点下载」，
 * 没有一个需要 7×24 运行的后台任务；常驻只会白耗电、白占前台服务配额，
 * 还会在状态栏挂一条永远去不掉的通知。
 */
class TransferService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var observeJob: Job? = null
    private var stopJob: Job? = null

    /** 本次服务存活期间「见过它处于进行中」的传输。只有这些才在结束时提醒。 */
    private val tracked = mutableSetOf<String>()

    /** 已经提醒过的传输，避免同一件事重复弹。 */
    private val reported = mutableSetOf<String>()

    private val speed = SpeedMeter()

    @Volatile
    private var activeCount = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // 渠道要在第一条通知之前建好，否则通知会被系统直接丢掉
        Notifications.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!promoteToForeground()) {
            // 起不了前台服务（后台启动被拒等）就安静退出：传输本身照跑，
            // 用户点的那次发送不该因为「通知没挂上」而失败。
            stopSelf()
            return START_NOT_STICKY
        }

        intent?.let(::handleAction)

        if (observeJob == null) {
            val container = (application as LanDropApp).container
            observeJob = scope.launch { observe(container) }
        }

        return START_NOT_STICKY
    }

    /**
     * Android 15 起 `dataSync` 型前台服务有「24 小时内累计 6 小时」的配额，
     * 用尽时系统调用这里，而不是直接把进程杀掉。必须主动收尾：
     * 把进行中的传输落成「已暂停」（两端现场都还在，能续），并明确告诉用户一声——
     * 否则站在用户视角就是「文件传到一半，通知没了，也没任何解释」。
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "前台服务配额耗尽，暂停全部传输")
        val container = (application as LanDropApp).container
        container.transfers.pauseAll()

        getSystemService(NotificationManager::class.java)?.notify(
            Notifications.TIMEOUT_ID,
            Notifications.result(
                this,
                TransferNotice.Content(
                    title = "传输已暂停",
                    text = "后台传输已达系统时长上限，打开应用可继续",
                    subText = null,
                    progress = 0,
                    max = 0,
                ),
            ),
        )

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        speed.reset()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 内部

    private fun promoteToForeground(): Boolean = try {
        ServiceCompat.startForeground(
            this,
            Notifications.ONGOING_ID,
            Notifications.preparing(this),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        true
    } catch (e: RuntimeException) {
        // 后台启动被拒（ForegroundServiceStartNotAllowedException）或类型不被允许，
        // 两者都是 IllegalStateException / SecurityException 的子类
        Log.w(TAG, "无法进入前台：${e.message}")
        false
    }

    private fun handleAction(intent: Intent) {
        val transferId = intent.getStringExtra(EXTRA_TRANSFER_ID) ?: return
        val container = (application as LanDropApp).container

        when (intent.action) {
            ACTION_PAUSE -> container.transfers.pause(transferId)
            ACTION_CANCEL -> container.transfers.cancel(transferId)
            ACTION_RESUME -> container.transfers.resume(transferId)
            else -> Unit // ACTION_START：只是把服务拉起来，没有额外动作
        }
    }

    private suspend fun observe(container: AppContainer) {
        val manager = getSystemService(NotificationManager::class.java) ?: return

        container.transfers.transfers.collect { transfers ->
            val active = transfers.filter { TransferNotice.isActive(it.state) }
            activeCount = active.size
            active.forEach { tracked += it.id }

            speed.sample(active.sumOf { it.transferredBytes.coerceAtLeast(0) }, System.currentTimeMillis())

            val content = TransferNotice.describe(transfers, speed.rate)
            if (content != null) {
                stopJob?.cancel()
                stopJob = null
                manager.notify(
                    Notifications.ONGOING_ID,
                    Notifications.ongoing(this, content, actionsFor(active)),
                )
            }

            reportResults(transfers, manager)

            if (active.isEmpty()) scheduleStop()
        }
    }

    /** 点了「暂停」的场景下，这条传输会先落到 PAUSED，随后可能又被点「继续」失败成 FAILED。 */
    private fun reportResults(transfers: List<TransferEntity>, manager: NotificationManager) {
        for (transfer in transfers) {
            if (transfer.id !in tracked || transfer.id in reported) continue
            val content = TransferNotice.result(transfer) ?: continue
            reported += transfer.id
            manager.notify(Notifications.nextResultId(), Notifications.result(this, content))
        }
    }

    /**
     * 只有一个传输在跑时才给按钮。
     *
     * 多个并发时不给：一个「暂停」按钮指的到底是哪一个？通知里没有位置再解释，
     * 与其让用户猜，不如让他回应用里操作。
     */
    private fun actionsFor(active: List<TransferEntity>): List<NotificationCompat.Action> {
        val only = active.singleOrNull() ?: return emptyList()
        return listOf(
            Notifications.serviceAction(this, ACTION_PAUSE, "暂停", only.id),
            Notifications.serviceAction(this, ACTION_CANCEL, "取消", only.id),
        )
    }

    private fun scheduleStop() {
        if (stopJob != null) return

        val job = scope.launch {
            delay(IDLE_GRACE_MS)
            if (activeCount == 0) {
                Log.i(TAG, "没有进行中的传输，停止前台服务")
                stopSelf()
            }
        }
        stopJob = job
        job.invokeOnCompletion { if (stopJob === job) stopJob = null }
    }

    companion object {
        private const val TAG = "LAN-Drop/TransferService"

        private const val ACTION_START = "io.github.illagercpr.landrop.START"
        private const val ACTION_PAUSE = "io.github.illagercpr.landrop.PAUSE"
        private const val ACTION_CANCEL = "io.github.illagercpr.landrop.CANCEL"
        private const val ACTION_RESUME = "io.github.illagercpr.landrop.RESUME"

        const val EXTRA_TRANSFER_ID = "transferId"

        /**
         * 全部传完之后的收尾宽限。
         *
         * 用户连续发几个文件时，前一个传完后一个可能马上开始；没有这个窗口，
         * 服务会「停-起-停-起」，通知也跟着闪。
         */
        private const val IDLE_GRACE_MS = 1_500L

        /**
         * 拉起前台服务。
         *
         * 调用点都在用户动作的路径上（点发送、点下载、点通知按钮），属于
         * Android 12+ 允许后台启动前台服务的豁免情形；但豁免清单随版本变动，
         * 这里兜住异常——通知挂不上只是少了提示，不能让用户的发送直接失败。
         */
        fun start(context: Context) {
            val intent = Intent(context, TransferService::class.java).setAction(ACTION_START)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: RuntimeException) {
                Log.w(TAG, "启动前台服务被拒绝：${e.message}")
            }
        }
    }
}
