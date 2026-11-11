package com.yepplayer.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object Source {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 12000
        connection.readTimeout = 20000
        connection.setRequestProperty("User-Agent", "YepPlayer/0.1")
        try {
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally { connection.disconnect() }
    }
    fun base(p: Profile): String = p.base.trim().trimEnd('/')
    fun api(p: Profile, action: String, extra: String = ""): String = "${base(p)}/player_api.php?username=${enc(p.username)}&password=${enc(p.password)}&action=$action$extra"
    fun categories(p: Profile, kind: String): Map<String,String> {
        if (p.type != "xtream") return emptyMap()
        val action = when(kind) { "live" -> "get_live_categories"; "vod" -> "get_vod_categories"; else -> "get_series_categories" }
        val arr = JSONArray(get(api(p, action)))
        return (0 until arr.length()).associate { val o=arr.getJSONObject(it); o.optString("category_id") to o.optString("category_name", "Other") }
    }
    fun load(p: Profile, kind: String): List<MediaItem> {
        if (p.type == "m3u") return if(kind == "live") parseM3u(get(p.base)) else emptyList()
        val cats = categories(p, kind)
        val action = when(kind) { "live" -> "get_live_streams"; "vod" -> "get_vod_streams"; else -> "get_series" }
        val arr = JSONArray(get(api(p, action)))
        return (0 until arr.length()).map { i ->
            val o=arr.getJSONObject(i)
            val id=if(kind=="series") o.optString("series_id") else o.optString("stream_id")
            val extension=o.optString("container_extension", "mp4").ifBlank { "mp4" }
            val url=when(kind) {
                "live" -> "${base(p)}/live/${enc(p.username)}/${enc(p.password)}/$id.m3u8"
                "vod" -> "${base(p)}/movie/${enc(p.username)}/${enc(p.password)}/$id.$extension"
                else -> ""
            }
            MediaItem(id, o.optString("name", "Untitled"), url, cats[o.optString("category_id")] ?: "Other", kind, o.optString("epg_channel_id"), o.optInt("tv_archive") == 1, if(kind=="series") id else "")
        }
    }
    fun episodes(p: Profile, seriesId: String): List<MediaItem> {
        val obj=JSONObject(get(api(p,"get_series_info","&series_id=${enc(seriesId)}")))
        val episodes=obj.optJSONObject("episodes") ?: return emptyList()
        val out=mutableListOf<MediaItem>()
        for (season in episodes.keys()) {
            val list=episodes.optJSONArray(season) ?: continue
            for (i in 0 until list.length()) {
                val ep=list.optJSONObject(i) ?: continue
                val id=ep.optString("id")
                val ext=ep.optJSONObject("info")?.optString("container_extension", "mp4")?.ifBlank { "mp4" } ?: "mp4"
                out.add(MediaItem(id, "S$season E${ep.optString("episode_num", "${i+1}")} • ${ep.optString("title", "Episode")}", "${base(p)}/series/${enc(p.username)}/${enc(p.password)}/$id.$ext", "Season $season", "episode"))
            }
        }
        return out
    }
    fun epg(p: Profile, id: String): List<String> {
        if(p.type != "xtream") return emptyList()
        val root=JSONObject(get(api(p,"get_short_epg","&stream_id=${enc(id)}&limit=8")))
        val arr=root.optJSONArray("epg_listings") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o=arr.getJSONObject(i)
            val title=try { String(android.util.Base64.decode(o.optString("title"), android.util.Base64.DEFAULT)) } catch (_: Exception) { o.optString("title") }
            "${o.optString("start")}  $title"
        }
    }
    fun parseM3u(data: String): List<MediaItem> {
        val result=mutableListOf<MediaItem>()
        var name=""; var group="Other"; var epg=""; var catchup=false
        for(line in data.lineSequence().map { it.trim() }) {
            if(line.startsWith("#EXTINF",true)) {
                name=line.substringAfterLast(',',"Channel").trim()
                group=Regex("group-title=\"([^\"]*)\"").find(line)?.groupValues?.get(1) ?: "Other"
                epg=Regex("tvg-id=\"([^\"]*)\"").find(line)?.groupValues?.get(1) ?: ""
                catchup=line.contains("catchup=",true)
            } else if(line.isNotEmpty() && !line.startsWith('#') && (line.startsWith("https://") || line.startsWith("http://"))) {
                result.add(MediaItem(result.size.toString(),name.ifBlank { "Channel" },line,group,"live",epg,catchup))
                name=""; group="Other"; epg=""; catchup=false
            }
        }
        return result
    }
}
