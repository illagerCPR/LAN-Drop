package io.github.illagercpr.landrop.ui.common

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val DATE_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

/** 人类可读的文件大小（1024 进制，保留一位小数）。 */
fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "未知大小"
    if (bytes < 1024) return "$bytes B"

    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var unitIndex = 0

    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }

    return if (value >= 100) {
        String.format(Locale.US, "%.0f %s", value, units[unitIndex])
    } else {
        String.format(Locale.US, "%.1f %s", value, units[unitIndex])
    }
}

/** 当天只显示时分，跨天补上日期——聊天里最常见的两种需求。 */
fun formatTimestamp(epochMillis: Long): String {
    val zone = ZoneId.systemDefault()
    val dateTime = Instant.ofEpochMilli(epochMillis).atZone(zone)
    val formatter = if (dateTime.toLocalDate() == LocalDate.now(zone)) {
        TIME_FORMATTER
    } else {
        DATE_TIME_FORMATTER
    }
    return dateTime.format(formatter)
}

/** 传输进度百分比，0..1；总大小未知时返回 null（界面改用不确定进度条）。 */
fun progressFraction(transferred: Long, total: Long): Float? {
    if (total <= 0) return null
    return (transferred.toDouble() / total).coerceIn(0.0, 1.0).toFloat()
}
