package com.amolpurohit.tesla.ui

import java.util.Locale
import kotlin.math.roundToInt

fun formatRange(rangeKm: Double): String {
    val miles = (rangeKm / 1.609344).roundToInt()
    return "$miles mi"
}

fun formatTemp(celsius: Double): String {
    return String.format(Locale.US, "%.1f°C", celsius)
}

fun formatUpdatedAt(nowMs: Long, updatedAtMs: Long): String {
    val deltaMs = nowMs - updatedAtMs

    return when {
        deltaMs < 60_000 -> "just now"
        deltaMs < 3_600_000 -> {
            val minutes = (deltaMs / 60_000).toInt()
            "$minutes min ago"
        }
        deltaMs < 86_400_000 -> {
            val hours = (deltaMs / 3_600_000).toInt()
            "$hours h ago"
        }
        else -> {
            val days = (deltaMs / 86_400_000).toInt()
            "$days d ago"
        }
    }
}
