package com.yepplayer.app

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class SportEvent(val sport: String, val title: String, val start: Long, val stop: Long, val channel: MediaItem) {
    fun isLive(now: Long = System.currentTimeMillis() / 1000) = now in start until stop
}

/** Finds sport in the provider's TV guide by keyword (several languages). */
object Sports {
    /** Checked in this order, so specific sports win over football ("Premier League Darts" is darts). */
    private val rules: List<Pair<String, List<String>>> = listOf(
        "Darts" to listOf("darts", "pdc", "fléchettes", "flechettes", "dart"),
        "Snooker" to listOf("snooker", "billard"),
        "Rugby" to listOf("rugby", "six nations", "6 nations", "top 14", "pro d2", "super rugby", "nrl", "champions cup rugby"),
        "Tennis" to listOf("tennis", "atp", "wta", "roland-garros", "roland garros", "wimbledon", "us open", "australian open", "davis cup", "billie jean king"),
        "Cycling" to listOf("cycling", "cyclisme", "ciclismo", "radsport", "wielrennen", "tour de france", "giro d'italia", "vuelta", "paris-roubaix", "uci"),
        "Cricket" to listOf("cricket", "ipl", "t20", "odi", "the ashes", "big bash", "the hundred", "test match"),
        "Formula 1" to listOf("formula 1", "formula one", "formule 1", "fórmula 1", "formel 1", "f1"),
        "MotoGP" to listOf("motogp", "moto gp", "superbike", "moto2", "moto3"),
        "Golf" to listOf("golf", "pga", "lpga", "ryder cup", "dp world tour"),
        "Basketball" to listOf("basketball", "basket-ball", "baloncesto", "pallacanestro", "nba", "euroleague", "euroligue", "wnba"),
        "Boxing & MMA" to listOf("boxing", "boxe", "boxeo", "ufc", "mma"),
        "Football" to listOf("football", "soccer", "fútbol", "futbol", "calcio", "fußball", "fussball", "voetbal", "futebol",
            "premier league", "champions league", "europa league", "conference league", "ligue 1", "ligue 2", "laliga", "la liga",
            "serie a", "bundesliga", "eredivisie", "fa cup", "carabao cup", "efl", "mls", "copa del rey", "coupe de france",
            "uefa", "fifa", "world cup", "coupe du monde", "euro 2028", "primeira liga", "super lig", "match of the day")
    )
    val names = rules.map { it.first }
    private val patterns = rules.map { (name, words) -> name to Regex("(?<![\\p{L}\\p{N}])(" + words.joinToString("|") { Regex.escape(it) } + ")(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE) }
    /** Words that mark news, quizzes and magazines rather than an event. */
    private val noise = Regex("(?<![\\p{L}])(news|journal|actualit|nachrichten|notizie|noticias|quiz|weather|météo)(?![\\p{L}])", RegexOption.IGNORE_CASE)

    fun classify(text: String): String? {
        if (noise.containsMatchIn(text)) return null
        return patterns.firstOrNull { it.second.containsMatchIn(text) }?.first
    }

    private var cached: Pair<Long, List<SportEvent>>? = null

    /** Live and upcoming (next 18 hours) sport events on [channels]. */
    fun load(p: Profile, channels: List<MediaItem>, force: Boolean = false): List<SportEvent> {
        val now = System.currentTimeMillis() / 1000
        cached?.let { if (!force && now - it.first < 20 * 60) return it.second.filter { e -> e.stop > now } }
        val events = try { fromXmltv(p, channels, now) } catch (e: Exception) { fromShortEpg(p, channels, now) }
        cached = now to events
        return events
    }

    private fun fromXmltv(p: Profile, channels: List<MediaItem>, now: Long): List<SportEvent> {
        if (p.type != "xtream") error("No guide file")
        val byEpg = channels.filter { it.epgId.isNotBlank() }.groupBy { it.epgId.lowercase() }
        if (byEpg.isEmpty()) error("No guide ids")
        val until = now + 18 * 3600
        val fmt = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
        fun time(s: String?): Long? = s?.trim()?.let { v -> try { fmt.parse(if (v.length == 14) "$v +0000" else v)!!.time / 1000 } catch (_: Exception) { null } }
        val url = "${Source.base(p)}/xmltv.php?username=${java.net.URLEncoder.encode(p.username, "UTF-8")}&password=${java.net.URLEncoder.encode(p.password, "UTF-8")}"
        val conn = Source.open(url, 15000, 60000)
        val out = mutableListOf<SportEvent>()
        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(conn.inputStream.buffered(), null)
            var start: Long? = null; var stop: Long? = null; var chans: List<MediaItem>? = null
            val text = StringBuilder(); var tag = ""
            var ev = parser.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> {
                        tag = parser.name
                        if (tag == "programme") {
                            start = time(parser.getAttributeValue(null, "start")); stop = time(parser.getAttributeValue(null, "stop"))
                            val ch = parser.getAttributeValue(null, "channel")?.lowercase()
                            chans = if (start != null && stop != null && stop!! > now && start!! < until) byEpg[ch] else null
                            text.setLength(0)
                        }
                    }
                    XmlPullParser.TEXT -> if (chans != null && (tag == "title" || tag == "category" || tag == "sub-title")) text.append(parser.text).append(" | ")
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "programme" && chans != null) {
                            val all = text.toString()
                            Sports.classify(all)?.let { sport ->
                                val title = all.substringBefore(" | ").trim()
                                chans!!.forEach { c -> out.add(SportEvent(sport, title, start!!, stop!!, c)) }
                            }
                            chans = null
                        }
                        tag = ""
                    }
                }
                ev = parser.next()
            }
        } finally { conn.disconnect() }
        return out.distinctBy { "${it.channel.id}|${it.start}" }.sortedBy { it.start }
    }

    /** Fallback: short guide of channels in sport groups (or with sport-like names). */
    private fun fromShortEpg(p: Profile, channels: List<MediaItem>, now: Long): List<SportEvent> {
        if (p.type != "xtream") return emptyList()
        val sporty = Regex("sport|deporte|calcio|football|foot|dazn|eurosport|bein|tennis|golf|f1|arena", RegexOption.IGNORE_CASE)
        val candidates = channels.filter { sporty.containsMatchIn(it.category) || sporty.containsMatchIn(it.name) }.take(150)
        val pool = Executors.newFixedThreadPool(4)
        val out = java.util.Collections.synchronizedList(mutableListOf<SportEvent>())
        candidates.forEach { c ->
            pool.execute {
                try {
                    Source.epg(p, c.id).filter { it.stop > now && it.start < now + 18 * 3600 }.forEach { e ->
                        classify("${e.title} | ${e.description.take(200)} | ${c.name}")?.let { out.add(SportEvent(it, e.title, e.start, e.stop, c)) }
                    }
                } catch (_: Exception) { }
            }
        }
        pool.shutdown(); pool.awaitTermination(60, TimeUnit.SECONDS)
        return out.toList().distinctBy { "${it.channel.id}|${it.start}" }.sortedBy { it.start }
    }
}
