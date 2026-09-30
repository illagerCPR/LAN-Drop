package io.github.illagercpr.landrop.notify

/**
 * 传输速率估算。
 *
 * 样本只能来自进度推进（Room 里 `transferredBytes` 的变化），不能自己定时采——
 * 上传每片 4 MiB、下载每 1 MiB 才写一次库，采样点本来就稀。
 *
 * 用指数滑动平均（新样本占 1/4）压抖动：局域网上偶尔一次 TCP 停顿不该让锁屏上的
 * 数字从 1.2 MB/s 跳到 200 KB/s 再跳回来，那样还不如不显示。
 */
class SpeedMeter(private val minIntervalMs: Long = MIN_INTERVAL_MS) {

    private var lastBytes = -1L
    private var lastAt = 0L
    private var current: Long? = null

    /** 当前速率（字节/秒）；样本不足时为 null，调用方据此不显示速率。 */
    val rate: Long? get() = current

    /**
     * 喂一个样本。
     *
     * [bytes] 是累计已传字节数，[now] 是毫秒时间戳。
     * 第一次采样、以及进度**回退**（换了任务或重新对齐）都只重置基准不产出速率：
     * 拿两次不同任务的字节数相减会得到一个凭空的巨大速率。
     */
    fun sample(bytes: Long, now: Long) {
        if (lastBytes < 0 || bytes < lastBytes) {
            lastBytes = bytes
            lastAt = now
            current = null
            return
        }

        val elapsed = now - lastAt
        if (elapsed < minIntervalMs) return

        val instant = (bytes - lastBytes) * 1000 / elapsed
        current = current?.let { (it * (SMOOTHING - 1) + instant) / SMOOTHING } ?: instant
        lastBytes = bytes
        lastAt = now
    }

    /** 清空基准与速率；服务重开或换了任务时调用，免得把上一段的速率带过来。 */
    fun reset() {
        lastBytes = -1L
        lastAt = 0L
        current = null
    }

    companion object {
        /** 采样间隔下限。太密的样本会把 TCP 突发放大成假的高速率。 */
        const val MIN_INTERVAL_MS = 700L

        /** 滑动平均的样本权重分母：越大越平滑、越迟钝。 */
        private const val SMOOTHING = 4
    }
}
