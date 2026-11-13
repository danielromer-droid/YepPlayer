package com.yepplayer.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object Source {
    const val USER_AGENT = "YepPlayer/0.5"

    fun resumeKey(url: String) = "resume|" + url.substringAfterLast('/')
    fun durationKey(url: String) = "resume_dur|" + url.substringAfterLast('/')

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /**
     * Downloads [url], following up to 5 redirects by hand so that every hop is checked by [NetGuard]
     * (a public server must not be able to bounce the app onto the home network).
     */
    fun open(url: String, connectTimeout: Int = 12000, readTimeout: Int = 30000): HttpURLConnection {
        var current = url
        repeat(6) {
            NetGuard.check(current)
            val connection = URL(current).openConnection() as HttpURLConnection
            connection.connectTimeout = connectTimeout
            connection.readTimeout = readTimeout
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", USER_AGENT)
            val code = try { connection.responseCode } catch (e: Exception) { connection.disconnect(); throw e }
            if (code in 300..399) {
                val loc = connection.getHeaderField("Location")
                connection.disconnect()
                current = URL(URL(current), loc ?: error("Server redirect without a destination")).toString()
                return@repeat
            }
            if (code !in 200..299) {
                connection.disconnect()
                if (code == 401 || code == 403) error("Access refused by the server (HTTP $code). Check your username and password, or your connection limit.")
                error("Server answered HTTP $code")
            }
            return connection
        }
        error("Too many redirects")
    }

    fun fetch(url: String, connectTimeout: Int = 12000, readTimeout: Int = 30000): ByteArray {
        val c = open(url, connectTimeout, readTimeout)
        try { return c.inputStream.use { it.readBytes() } } finally { c.disconnect() }
    }

    fun get(url: String): String = String(fetch(url), Charsets.UTF_8)

    /** Wraps a Media3 data source so every stream address (and each HLS segment) is checked by [NetGuard] first. */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun guarded(upstream: androidx.media3.datasource.DataSource.Factory): androidx.media3.datasource.DataSource.Factory =
        androidx.media3.datasource.ResolvingDataSource.Factory(upstream) { spec -> NetGuard.check(spec.uri.toString()); spec }

    /** Normalises the server URL: adds http:// if missing, strips trailing slashes and a pasted player_api.php path. */
    fun base(p: Profile): String {
        var b = p.base.trim()
        if (!b.startsWith("http://", true) && !b.startsWith("https://", true)) b = "http://$b"
        b = b.trimEnd('/')
        if (b.endsWith("/player_api.php", true)) b = b.dropLast("/player_api.php".length)
        if (b.endsWith("/get.php", true)) b = b.dropLast("/get.php".length)
        return b
    }

    fun api(p: Profile, action: String, extra: String = ""): String =
        "${base(p)}/player_api.php?username=${enc(p.username)}&password=${enc(p.password)}" +
            (if (action.isNotEmpty()) "&action=$action" else "") + extra

    private fun parseArray(body: String): JSONArray {
        val t = body.trim()
        if (t.startsWith("[")) return JSONArray(t)
        if (t.startsWith("{")) {
            // Some panels return an object keyed by index instead of an array.
            val o = JSONObject(t)
            val arr = JSONArray()
            o.keys().forEach { k -> o.optJSONObject(k)?.let { arr.put(it) } }
            return arr
        }
        error("Unexpected answer from server. Is this an Xtream Codes URL?")
    }

    /** Checks the login and returns account details. Throws with a readable message on failure. */
    fun account(p: Profile): AccountInfo {
        val root = try { JSONObject(get(api(p, ""))) } catch (e: org.json.JSONException) { error("The server did not return Xtream account data. Check the server URL.") }
        val u = root.optJSONObject("user_info") ?: error("No account information returned. Check the server URL.")
        if (u.optInt("auth", 1) == 0) error("Login refused. Check your username and password.")
        val s = root.optJSONObject("server_info")
        return AccountInfo(
            status = u.optString("status", "Unknown"),
            expiry = u.optString("exp_date").toLongOrNull() ?: 0L,
            activeConnections = u.optString("active_cons", "?"),
            maxConnections = u.optString("max_connections", "?"),
            isTrial = u.optString("is_trial") == "1",
            serverTimezone = s?.optString("timezone").orEmpty()
        )
    }

    fun categories(p: Profile, kind: String): Map<String, String> {
        if (p.type != "xtream") return emptyMap()
        val action = when (kind) { "live" -> "get_live_categories"; "vod" -> "get_vod_categories"; else -> "get_series_categories" }
        val arr = try { parseArray(get(api(p, action))) } catch (_: Exception) { return emptyMap() }
        return (0 until arr.length()).associate { val o = arr.getJSONObject(it); o.optString("category_id") to o.optString("category_name", "Other") }
    }

    fun liveUrl(p: Profile, id: String, ext: String = "m3u8") = "${base(p)}/live/${enc(p.username)}/${enc(p.password)}/$id.$ext"

    fun load(p: Profile, kind: String): List<MediaItem> {
        if (p.type == "m3u") return if (kind == "live") parseM3u(get(p.base.trim())) else emptyList()
        val cats = categories(p, kind)
        val action = when (kind) { "live" -> "get_live_streams"; "vod" -> "get_vod_streams"; else -> "get_series" }
        val arr = parseArray(get(api(p, action)))
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = if (kind == "series") o.optString("series_id") else o.optString("stream_id")
            if (id.isBlank()) return@mapNotNull null
            val extension = o.optString("container_extension", "mp4").ifBlank { "mp4" }
            val url = when (kind) {
                "live" -> liveUrl(p, id)
                "vod" -> "${base(p)}/movie/${enc(p.username)}/${enc(p.password)}/$id.$extension"
                else -> ""
            }
            val logo = if (kind == "series") o.optString("cover") else o.optString("stream_icon")
            MediaItem(
                id, o.optString("name", "Untitled").trim(), url,
                cats[o.optString("category_id")] ?: "Other", kind,
                o.optString("epg_channel_id"), o.optInt("tv_archive") == 1,
                if (kind == "series") id else "", logo, o.optInt("tv_archive_duration"),
                o.optString("rating").takeIf { it != "0" && it != "null" }.orEmpty(),
                (o.optString("added").ifBlank { o.optString("last_modified") }).toLongOrNull() ?: 0L,
                o.optInt("num", i + 1)
            )
        }
    }

    /** Plot, year, etc. for a movie (get_vod_info) or series (get_series_info). */
    fun details(p: Profile, item: MediaItem): Details? {
        if (p.type != "xtream") return null
        val root = if (item.kind == "series") JSONObject(get(api(p, "get_series_info", "&series_id=${enc(item.seriesId.ifBlank { item.id })}")))
                   else JSONObject(get(api(p, "get_vod_info", "&vod_id=${enc(item.id)}")))
        val info = root.optJSONObject("info") ?: return null
        fun s(vararg keys: String) = keys.map { info.optString(it) }.firstOrNull { it.isNotBlank() && it != "null" }.orEmpty()
        return Details(
            s("plot", "description"), s("releasedate", "releaseDate", "year").take(4), s("duration"),
            s("genre"), s("rating"), s("movie_image", "cover", "cover_big")
        )
    }

    /** The saved profile, with the password decrypted. */
    fun readProfile(prefs: android.content.SharedPreferences): Profile? = try {
        val j = JSONObject(prefs.getString("profile", "")!!)
        val pass = j.optString("password_enc").takeIf { it.isNotBlank() }?.let { Secure.decrypt(it) } ?: j.optString("password")
        Profile(j.getString("type"), j.getString("base"), j.optString("username"), pass)
    } catch (_: Exception) { null }

    fun saveProfile(prefs: android.content.SharedPreferences, p: Profile) {
        val j = JSONObject().put("type", p.type).put("base", p.base).put("username", p.username)
        val enc = try { Secure.encrypt(p.password) } catch (_: Exception) { null }
        if (enc != null) j.put("password_enc", enc) else j.put("password", p.password)
        prefs.edit().putString("profile", j.toString()).apply()
    }

    fun episodes(p: Profile, seriesId: String): List<MediaItem> {
        val obj = JSONObject(get(api(p, "get_series_info", "&series_id=${enc(seriesId)}")))
        val out = mutableListOf<MediaItem>()
        fun addEpisode(season: String, ep: JSONObject, index: Int) {
            val id = ep.optString("id")
            val ext = ep.optString("container_extension").ifBlank { ep.optJSONObject("info")?.optString("container_extension").orEmpty() }.ifBlank { "mp4" }
            val img = ep.optJSONObject("info")?.optString("movie_image").orEmpty()
            out.add(MediaItem(id, "S$season E${ep.optString("episode_num", "${index + 1}")} • ${ep.optString("title", "Episode")}",
                "${base(p)}/series/${enc(p.username)}/${enc(p.password)}/$id.$ext", "Season $season", "episode", logoUrl = img))
        }
        when (val episodes = obj.opt("episodes")) {
            is JSONObject -> episodes.keys().asSequence().sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }.forEach { season ->
                val list = episodes.optJSONArray(season) ?: return@forEach
                for (i in 0 until list.length()) list.optJSONObject(i)?.let { addEpisode(season, it, i) }
            }
            is JSONArray -> for (s in 0 until episodes.length()) {
                val list = episodes.optJSONArray(s) ?: continue
                for (i in 0 until list.length()) list.optJSONObject(i)?.let { addEpisode(it.optString("season", "${s + 1}"), it, i) }
            }
        }
        return out
    }

    private fun b64(s: String): String = try {
        String(android.util.Base64.decode(s, android.util.Base64.DEFAULT), Charsets.UTF_8)
    } catch (_: Exception) { s }

    /**
     * Programme guide for one channel. With [full] the whole archive/day table is requested
     * (needed for catch-up), otherwise the next few programmes.
     */
    fun epg(p: Profile, streamId: String, full: Boolean = false): List<EpgEntry> {
        if (p.type != "xtream") return emptyList()
        val url = if (full) api(p, "get_simple_data_table", "&stream_id=${enc(streamId)}")
                  else api(p, "get_short_epg", "&stream_id=${enc(streamId)}&limit=12")
        val root = JSONObject(get(url))
        val arr = root.optJSONArray("epg_listings") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val start = o.optString("start_timestamp").toLongOrNull() ?: return@mapNotNull null
            val stop = o.optString("stop_timestamp").toLongOrNull() ?: (start + 3600)
            EpgEntry(b64(o.optString("title")).trim(), b64(o.optString("description")).trim(), start, stop,
                o.optString("start"), o.optInt("has_archive") == 1)
        }.sortedBy { it.start }
    }

    /**
     * Xtream timeshift URL. [serverStart] is the programme start in the provider's own time zone
     * ("YYYY-MM-DD HH:MM[:SS]"), which is what the timeshift endpoint expects as "YYYY-MM-DD:HH-MM".
     */
    fun timeshiftUrl(p: Profile, streamId: String, serverStart: String, durationMinutes: Int): String? {
        val m = Regex("(\\d{4}-\\d{2}-\\d{2})[ T](\\d{2}):(\\d{2})").find(serverStart) ?: return null
        val (date, h, min) = m.destructured
        return "${base(p)}/timeshift/${enc(p.username)}/${enc(p.password)}/$durationMinutes/$date:$h-$min/$streamId.ts"
    }

    fun parseM3u(data: String): List<MediaItem> {
        val result = mutableListOf<MediaItem>()
        var name = ""; var group = "Other"; var epg = ""; var logo = ""; var catchup = false
        for (line in data.lineSequence().map { it.trim() }) {
            if (line.startsWith("#EXTINF", true)) {
                name = line.substringAfterLast(',', "Channel").trim()
                group = Regex("group-title=\"([^\"]*)\"").find(line)?.groupValues?.get(1)?.ifBlank { null } ?: "Other"
                epg = Regex("tvg-id=\"([^\"]*)\"").find(line)?.groupValues?.get(1) ?: ""
                logo = Regex("tvg-logo=\"([^\"]*)\"").find(line)?.groupValues?.get(1) ?: ""
                catchup = line.contains("catchup=", true)
            } else if (line.startsWith("#EXTGRP:", true)) {
                group = line.substringAfter(':').trim().ifBlank { group }
            } else if (line.isNotEmpty() && !line.startsWith('#') && (line.startsWith("https://", true) || line.startsWith("http://", true))) {
                result.add(MediaItem(result.size.toString(), name.ifBlank { "Channel" }, line, group, "live", epg, catchup, "", logo))
                name = ""; group = "Other"; epg = ""; logo = ""; catchup = false
            }
        }
        return result
    }
}
