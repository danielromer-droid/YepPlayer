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
    val archiveDays: Int = 0,
    val rating: String = "",
    val added: Long = 0L,
    val num: Int = 0
)

/** Extra details for a movie or series, loaded when it is selected. */
data class Details(val plot: String, val year: String, val duration: String, val genre: String, val rating: String, val cover: String, val backdrop: String = "")
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

/** Channel name without a country tag in front, e.g. "US ★ Tennis Channel" -> "Tennis Channel". */
private val countryTag = Regex("^\\s*[A-Z]{2,3}\\s*[^\\p{L}\\p{N}\\s(\\[+&'.!-]+\\s*")
fun displayName(name: String): String = name.replace(countryTag, "").ifBlank { name }

/** Country code at the start of a group name ("UK ❖ SPORTS", "FR| CINEMA", "DE KINO"), or null. */
private val countryLead = Regex("^\\s*([A-Z]{2,3})\\s*[★☆❖⭐|:•►▶\\-–]+\\s*\\S|^\\s*([A-Z]{2})\\s+\\S")
fun countryOf(name: String): String? = countryLead.find(name)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
