package com.yepplayer.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONObject
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val bg = Color.rgb(7, 12, 22)
    private val panel = Color.rgb(19, 29, 46)
    private val panel2 = Color.rgb(27, 42, 64)
    private val blue = Color.rgb(27, 143, 255)
    private val white = Color.WHITE
    private val muted = Color.rgb(164, 180, 202)
    private val pool = Executors.newSingleThreadExecutor()
    private lateinit var root: LinearLayout
    private var profile: Profile? = null
    private var items = listOf<MediaItem>()
    private var currentKind = "live"
    private val prefs by lazy { getSharedPreferences("yepplayer", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        profile = readProfile()
        home()
    }

    private fun rounded(color: Int, radius: Float = 18f): GradientDrawable = GradientDrawable().apply {
        setColor(color); cornerRadius = radius; setStroke(1, Color.rgb(42, 59, 82))
    }

    private fun label(value: String, size: Float = 15f, color: Int = white, bold: Boolean = false): TextView = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); setPadding(14, 10, 14, 10)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun action(value: String, onClick: () -> Unit, accent: Boolean = false): Button = Button(this).apply {
        text = value; textSize = 14f; isAllCaps = false; setTextColor(white)
        background = rounded(if (accent) Color.rgb(13, 104, 205) else panel2, 14f)
        setPadding(8, 8, 8, 8); setOnClickListener { onClick() }
    }

    private fun screen(title: String, showBack: Boolean = false) {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg); setPadding(12, 12, 12, 8) }
        setContentView(root)
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (showBack) bar.addView(action("‹", { home() }), LinearLayout.LayoutParams(48, 48))
        bar.addView(label("Yep", 24f, blue, true))
        bar.addView(label("Player", 24f, white, true))
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(action("⚙", { settings() }))
        root.addView(bar)
        root.addView(label(title, 19f, white, true))
    }

    private fun scroll(): LinearLayout {
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { isFillViewport = true; addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        return content
    }

    private fun readProfile(): Profile? = try {
        val j = JSONObject(prefs.getString("profile", "")!!)
        Profile(j.getString("type"), j.getString("base"), j.optString("username"), j.optString("password"))
    } catch (_: Exception) { null }

    private fun saveProfile(p: Profile) {
        prefs.edit().putString("profile", JSONObject().put("type", p.type).put("base", p.base).put("username", p.username).put("password", p.password).toString()).apply()
        profile = p
    }

    private fun home() {
        screen("Your television, organised")
        val c = scroll()
        val hero = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = rounded(panel); setPadding(10, 8, 10, 12) }
        hero.addView(label("WELCOME TO YEPPLAYER", 12f, blue, true))
        hero.addView(label(if (profile == null) "Connect your IPTV service to get started" else "Connected • ${profile!!.type.uppercase()}", 16f, white, true))
        hero.addView(label("Live channels · Movies · Series · TV guide", 12f, muted))
        hero.addView(action(if (profile == null) "＋  Add your first playlist" else "⚙  Manage playlists", { configure() }, true))
        c.addView(hero)
        c.addView(label("BROWSE", 12f, muted, true))
        val grid = arrayOf(
            arrayOf("📺", "Live TV", "live"), arrayOf("🎬", "Movies", "vod"), arrayOf("▣", "Series", "series"),
            arrayOf("▦", "TV Guide", "epg"), arrayOf("●", "Recordings", "recordings"), arrayOf("★", "Favourites", "favourites")
        )
        for (r in grid.chunked(2)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            r.forEach { entry ->
                val tile = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = rounded(panel2); setPadding(8, 12, 8, 12); gravity = Gravity.CENTER; setOnClickListener { when (entry[2]) { "favourites" -> showItems(items.filter { isFavourite(it) }, "Favourites"); "epg" -> load("live"); "recordings" -> recordings(); else -> load(entry[2]) } } }
                tile.addView(label(entry[0], 25f, blue, true)); tile.addView(label(entry[1], 15f, white, true))
                row.addView(tile, LinearLayout.LayoutParams(0, 104, 1f).apply { setMargins(4, 4, 4, 4) })
            }
            c.addView(row)
        }
        c.addView(label("QUICK TOOLS", 12f, muted, true))
        c.addView(action("Manage channels and groups", { manageGroups() }))
        c.addView(action("Parental controls and PIN", { changePin() }))
        c.addView(action("Settings", { settings() }))
        c.addView(label("Only use streams and playlists you are authorised to access.", 11f, muted))
    }

    private fun configure() {
        screen("Connect a playlist", true)
        val c = scroll()
        c.addView(label("XTREAM CODES", 13f, blue, true))
        val server = EditText(this).apply { hint = "Server URL, e.g. https://provider:port"; setSingleLine(true); setText(profile?.takeIf { it.type == "xtream" }?.base ?: "") }
        val user = EditText(this).apply { hint = "Username"; setSingleLine(true); setText(profile?.takeIf { it.type == "xtream" }?.username ?: "") }
        val pass = EditText(this).apply { hint = "Password"; setSingleLine(true); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        c.addView(server); c.addView(user); c.addView(pass)
        c.addView(action("Save Xtream account", { if (server.text.isBlank() || user.text.isBlank() || pass.text.isBlank()) toast("Fill in all fields") else { saveProfile(Profile("xtream", server.text.toString(), user.text.toString(), pass.text.toString())); load("live") } }, true))
        c.addView(label("M3U PLAYLIST", 13f, blue, true))
        val m3u = EditText(this).apply { hint = "https://example.com/playlist.m3u"; setSingleLine(true); setText(profile?.takeIf { it.type == "m3u" }?.base ?: "") }
        c.addView(m3u)
        c.addView(action("Save M3U URL", { if (!m3u.text.startsWith("http")) toast("Enter an HTTP(S) URL") else { saveProfile(Profile("m3u", m3u.text.toString())); load("live") } }, true))
    }

    private fun load(type: String) {
        val p = profile ?: run { configure(); return }
        currentKind = type
        screen("Loading ${type.uppercase()}…", true)
        pool.execute {
            try {
                val loaded = Source.load(p, type)
                runOnUiThread { items = loaded; showItems(loaded, if (type == "live") "Live TV" else type.uppercase()) }
            } catch (e: Exception) {
                runOnUiThread { screen("Connection problem", true); scroll().addView(label(e.message ?: "Unable to load source", 14f, muted)); root.addView(action("Try again", { load(type) }, true)) }
            }
        }
    }

    private fun showItems(data: List<MediaItem>, title: String) {
        screen(title, true)
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        controls.addView(action("⌕ Search", { searchDialog(data, title) }), LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(action("Groups", { manageGroups(data) }), LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(action("★", { showItems(items.filter { isFavourite(it) }, "Favourites") }))
        root.addView(controls)
        val categories = listOf("All") + data.map { it.category }.distinct().sorted()
        val filter = Spinner(this)
        filter.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, categories)
        root.addView(filter)
        val list = scroll()
        fun render() {
            list.removeAllViews()
            val selected = categories[filter.selectedItemPosition.coerceAtLeast(0)]
            val hidden = prefs.getStringSet("hidden_groups", emptySet())?.toSet() ?: emptySet()
            val visible = data.filter { (selected == "All" || it.category == selected) && it.category !in hidden }.take(600)
            list.addView(label("${visible.size} channels / ${data.size} entries", 11f, muted))
            visible.forEach { item ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = rounded(panel); setPadding(5, 4, 5, 4) }
                if (item.logoUrl.isNotBlank()) {
                    val logo = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(4, 4, 4, 4); setImageResource(android.R.drawable.ic_menu_gallery) }
                    row.addView(logo, LinearLayout.LayoutParams(54, 54))
                    loadLogo(item.logoUrl, logo)
                } else {
                    val logo = TextView(this).apply { text = "▶"; textSize = 20f; gravity = Gravity.CENTER; setTextColor(blue); background = rounded(Color.rgb(10, 45, 82), 12f) }
                    row.addView(logo, LinearLayout.LayoutParams(48, 48))
                }
                val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(8, 2, 2, 2) }
                info.addView(label(item.name, 14f, white, true)); info.addView(label(item.category, 11f, muted))
                info.setOnClickListener { open(item) }
                row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(action(if (isFavourite(item)) "★" else "☆", { toggleFavourite(item); showItems(data, title) }))
                list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 3, 0, 3) })
            }
        }
        filter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = render()
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        render()
    }

    private fun loadLogo(url: String, target: ImageView) {
        Thread {
            try {
                val conn = URL(url).openConnection(); conn.connectTimeout = 5000; conn.readTimeout = 5000
                val bitmap = conn.getInputStream().use { BitmapFactory.decodeStream(it) }
                if (bitmap != null) runOnUiThread { target.setImageBitmap(bitmap) }
            } catch (_: Exception) { }
        }.start()
    }

    private fun searchDialog(data: List<MediaItem>, title: String) {
        val input = EditText(this).apply { hint = "Channel or programme name" }
        AlertDialog.Builder(this).setTitle("Search $title").setView(input).setPositiveButton("Search") { _, _ ->
            val q = input.text.toString().trim()
            showItems(data.filter { it.name.contains(q, true) || it.category.contains(q, true) }, "Search results")
        }.setNegativeButton("Cancel", null).show()
    }

    private fun manageGroups(source: List<MediaItem> = items) {
        val groups = source.map { it.category }.distinct().sorted()
        if (groups.isEmpty()) { toast("Load a playlist first"); return }
        val hidden = prefs.getStringSet("hidden_groups", emptySet())?.toMutableSet() ?: mutableSetOf()
        val checked = BooleanArray(groups.size) { groups[it] !in hidden }
        AlertDialog.Builder(this).setTitle("Show or hide channel groups")
            .setMultiChoiceItems(groups.toTypedArray(), checked) { _, which, isChecked -> if (isChecked) hidden.remove(groups[which]) else hidden.add(groups[which]) }
            .setPositiveButton("Save") { _, _ -> prefs.edit().putStringSet("hidden_groups", hidden).apply(); showItems(source, "Live TV") }
            .setNeutralButton("Show all") { _, _ -> prefs.edit().remove("hidden_groups").apply(); showItems(source, "Live TV") }
            .setNegativeButton("Cancel", null).show()
    }

    private fun favKey(item: MediaItem) = "${profile?.base}|${item.kind}|${item.id}"
    private fun isFavourite(item: MediaItem) = prefs.getStringSet("favs", emptySet())!!.contains(favKey(item))
    private fun toggleFavourite(item: MediaItem) {
        val set = prefs.getStringSet("favs", emptySet())!!.toMutableSet()
        if (!set.add(favKey(item))) set.remove(favKey(item))
        prefs.edit().putStringSet("favs", set).apply()
    }
    private fun isRestricted(item: MediaItem) = Regex("adult|xxx|18\\+|porn", RegexOption.IGNORE_CASE).containsMatchIn(item.category + " " + item.name)

    private fun open(item: MediaItem) {
        val pin = prefs.getString("pin", null)
        if (pin != null && isRestricted(item)) {
            val entry = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD }
            AlertDialog.Builder(this).setTitle("Parental PIN").setView(entry).setPositiveButton("Unlock") { _, _ -> if (entry.text.toString() == pin) openUnlocked(item) else toast("Incorrect PIN") }.setNegativeButton("Cancel", null).show()
        } else openUnlocked(item)
    }

    private fun openUnlocked(item: MediaItem) {
        if (item.kind == "series") {
            val p = profile ?: return
            screen("Loading episodes…", true)
            pool.execute { try { val eps = Source.episodes(p, item.seriesId); runOnUiThread { showItems(eps, item.name) } } catch (e: Exception) { runOnUiThread { toast(e.message ?: "Episodes unavailable"); home() } } }
            return
        }
        val actions = mutableListOf("Play")
        if (item.kind == "live" && profile?.type == "xtream") { actions.add("Programme guide"); if (item.catchup) actions.add("Catch-up") }
        AlertDialog.Builder(this).setTitle(item.name).setItems(actions.toTypedArray()) { _, which ->
            when (actions[which]) { "Play" -> startPlayer(item.url, item.name); "Programme guide" -> showEpg(item); "Catch-up" -> catchup(item) }
        }.show()
    }

    private fun showEpg(item: MediaItem) {
        val p = profile ?: return
        pool.execute { try { val entries = Source.epg(p, item.id); runOnUiThread { AlertDialog.Builder(this).setTitle(item.name + " • TV Guide").setMessage(entries.joinToString("\n\n").ifBlank { "No guide data available" }).setPositiveButton("Close", null).show() } } catch (e: Exception) { runOnUiThread { toast("Guide unavailable: ${e.message}") } } }
    }

    private fun catchup(item: MediaItem) {
        val p = profile ?: return
        val input = EditText(this).apply { hint = "YYYY-MM-DD HH:MM (provider time)"; setSingleLine(true) }
        AlertDialog.Builder(this).setTitle("Catch-up / Replay").setMessage("Enter the archive start time. Provider support and archive duration vary.").setView(input).setPositiveButton("Play") { _, _ ->
            val start = input.text.toString().trim()
            if (!Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}").matches(start)) { toast("Use YYYY-MM-DD HH:MM"); return@setPositiveButton }
            val url = "${Source.base(p)}/timeshift/${java.net.URLEncoder.encode(p.username, "UTF-8")}/${java.net.URLEncoder.encode(p.password, "UTF-8")}/60/${java.net.URLEncoder.encode(start, "UTF-8")}/${item.id}.ts"
            startPlayer(url, item.name + " • Replay")
        }.setNegativeButton("Cancel", null).show()
    }

    private fun startPlayer(url: String, name: String) { startActivity(Intent(this, PlayerActivity::class.java).putExtra("url", url).putExtra("name", name)) }

    private fun changePin() {
        val entry = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD; hint = "4–8 digits" }
        AlertDialog.Builder(this).setTitle("Parental PIN").setMessage("Basic category keyword protection. This is not a certified parental-control system.").setView(entry).setPositiveButton("Save") { _, _ ->
            val value = entry.text.toString(); if (value.matches(Regex("\\d{4,8}"))) { prefs.edit().putString("pin", value).apply(); toast("PIN saved") } else toast("Use 4–8 digits")
        }.setNegativeButton("Cancel", null).show()
    }

    private fun settings() {
        AlertDialog.Builder(this).setTitle("YepPlayer settings").setItems(arrayOf("Manage playlists", "Manage / hide channel groups", "Parental PIN", "About YepPlayer")) { _, which ->
            when (which) { 0 -> configure(); 1 -> manageGroups(); 2 -> changePin(); 3 -> AlertDialog.Builder(this).setTitle("YepPlayer 0.2").setMessage("A personal IPTV player for authorised playlists. Recording and a full multi-channel EPG grid are planned for a later release.").setPositiveButton("OK", null).show() }
        }.show()
    }

    private fun recordings() {
        AlertDialog.Builder(this).setTitle("Recordings").setMessage("Live recording is not included in this build yet. It needs a compatible stream type, storage permission handling and a background recording service. Provider catch-up is available where supported.").setPositiveButton("OK", null).show()
    }

    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()
    override fun onDestroy() { pool.shutdownNow(); super.onDestroy() }
}
