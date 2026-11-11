package com.yepplayer.app

data class MediaItem(
    val id: String,
    val name: String,
    val url: String,
    val category: String,
    val kind: String,
    val epgId: String = "",
    val catchup: Boolean = false,
    val seriesId: String = "",
    val logoUrl: String = "",
    val archiveDays: Int = 0
)
data class Profile(val type: String, val base: String, val username: String = "", val password: String = "")

/** One programme from the Xtream EPG. Times are epoch seconds (UTC). */
data class EpgEntry(
    val title: String,
    val description: String,
    val start: Long,
    val stop: Long,
    val serverStart: String,
    val hasArchive: Boolean
) {
    val durationMinutes: Int get() = (((stop - start) / 60).toInt()).coerceAtLeast(1)
    fun isNow(nowSec: Long = System.currentTimeMillis() / 1000) = nowSec in start until stop
    fun isPast(nowSec: Long = System.currentTimeMillis() / 1000) = stop <= nowSec
}

data class AccountInfo(
    val status: String,
    val expiry: Long,
    val activeConnections: String,
    val maxConnections: String,
    val isTrial: Boolean,
    val serverTimezone: String
)
