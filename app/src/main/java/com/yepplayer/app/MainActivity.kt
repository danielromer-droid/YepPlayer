package com.yepplayer.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import androidx.media3.common.MediaItem as ExoItem

/** Colours of the TV-style design. */
private object C {
    val bg = Color.rgb(11, 15, 23)
    val rail = Color.rgb(15, 21, 32)
    val panel = Color.rgb(20, 27, 38)
    val panel2 = Color.rgb(28, 39, 56)
    val border = Color.rgb(37, 48, 66)
    val line = Color.rgb(30, 39, 53)
    val accent = Color.rgb(47, 123, 255)
    val accentSoft = Color.rgb(230, 240, 255)
    val orange = Color.rgb(255, 159, 67)
    val gold = Color.rgb(255, 194, 61)
    val red = Color.rgb(229, 72, 77)
    val text = Color.rgb(242, 245, 250)
    val text2 = Color.rgb(201, 211, 225)
    val muted = Color.rgb(154, 168, 188)
    val faint = Color.rgb(111, 126, 148)
}

@OptIn(UnstableApi::class)
class MainActivity : Activity() {
    private val pool = Executors.newSingleThreadExecutor()
    private val logoPool = Executors.newFixedThreadPool(4)
    private val epgPool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val logoCache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val failedLogos = HashSet<String>()
    private val nowCache = HashMap<String, EpgEntry?>()
    private val nowLoading = HashSet<String>()
    private val detailsCache = HashMap<String, Details?>()
    private var profile: Profile? = null
    private val cache = mutableMapOf<String, List<MediaItem>>()
    private var back: (() -> Unit)? = null
    private var refreshList: (() -> Unit)? = null
    private var clockTick: Runnable? = null
    private var preview: ExoPlayer? = null
    private val prefs by lazy { getSharedPreferences("yepplayer", MODE_PRIVATE) }
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dayFmt = SimpleDateFormat("EEE d MMM", Locale.getDefault())
    private val longDayFmt = SimpleDateFormat("EEEE d MMMM", Locale.getDefault())
    private val expFmt = SimpleDateFormat("d MMM yyyy", Locale.getDefault())

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
    private val widthDp get() = resources.configuration.screenWidthDp
    private val medium get() = widthDp >= 600
    private val wide get() = widthDp >= 900

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = C.rail
        window.navigationBarColor = C.rail
        profile = Source.readProfile(prefs)
        // Re-save once so an old plain-text password gets encrypted.
        profile?.let { if (prefs.getString("profile", "")?.contains("\"password\"") == true) Source.saveProfile(prefs, it) }
        home()
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        val b = back
        if (b != null) b() else super.onBackPressed()
    }

    override fun onStop() { stopPreview(); super.onStop() }

    // ---------------------------------------------------------------- building blocks

    private fun shape(color: Int, radiusDp: Int, stroke: Int? = null, strokeDp: Int = 1) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radiusDp).toFloat(); if (stroke != null) setStroke(dp(strokeDp).coerceAtLeast(1), stroke)
    }

    /** Background that shows a blue focus ring for TV remotes and a lighter fill when pressed. */
    private fun focusable(color: Int, radiusDp: Int, stroke: Int? = C.border, selected: Int? = null): Drawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), shape(if (selected != null) selected else C.panel2, radiusDp, C.accent, 2))
        addState(intArrayOf(android.R.attr.state_pressed), shape(C.panel2, radiusDp, stroke))
        if (selected != null) addState(intArrayOf(android.R.attr.state_selected), shape(selected, radiusDp, null))
        addState(intArrayOf(), shape(color, radiusDp, stroke))
    }

    private fun text(value: CharSequence, size: Float, color: Int = C.text, weight: Int = 400, lines: Int = 1): TextView = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        typeface = if (android.os.Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, weight, false) else if (weight >= 600) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        if (lines > 0) { maxLines = lines; ellipsize = TextUtils.TruncateAt.END }
        includeFontPadding = false
    }

    private fun icon(res: Int, tint: Int, sizeDp: Int = 22): ImageView = ImageView(this).apply {
        setImageResource(res); imageTintList = ColorStateList.valueOf(tint)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    private fun clickable(v: View, onClick: () -> Unit) = v.apply { isClickable = true; isFocusable = true; setOnClickListener { onClick() } }

    private fun iconButton(res: Int, desc: String, sizeDp: Int = 44, bg: Int = C.panel, onClick: () -> Unit): FrameLayout = FrameLayout(this).apply {
        contentDescription = desc
        background = focusable(bg, sizeDp / 2, C.border)
        addView(icon(res, C.text, 20), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        clickable(this, onClick)
    }

    private fun pill(label: String, primary: Boolean = false, iconRes: Int? = null, onClick: () -> Unit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        setPadding(dp(18), 0, dp(18), 0)
        background = if (primary) focusable(C.accent, 22, null, null).also { } else focusable(C.panel, 22, C.border)
        if (iconRes != null) addView(icon(iconRes, Color.WHITE, 16).apply { (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(8) })
        addView(text(label, 14f, if (primary) Color.WHITE else C.text, if (primary) 700 else 600))
        layoutParams = LinearLayout.LayoutParams(-2, dp(44))
        clickable(this, onClick)
    }

    /** Layout params: [w]/[h] in px (or -1/-2), margins in dp. */
    private fun lp(w: Int, h: Int, weight: Float = 0f, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) =
        LinearLayout.LayoutParams(w, h, weight).apply { setMargins(dp(l), dp(t), dp(r), dp(b)) }

    private fun badge(label: String, bg: Int, fg: Int = Color.WHITE) = text(label, 10f, fg, 800).apply {
        letterSpacing = 0.08f; setPadding(dp(7), dp(3), dp(7), dp(3)); background = shape(bg, 6)
    }

    private fun searchBox(hint: String, onChange: (String) -> Unit, onSubmit: ((String) -> Unit)? = null): Pair<LinearLayout, EditText> {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0); background = shape(C.panel, 22, C.border)
        }
        box.addView(icon(R.drawable.ic_search, C.muted, 17))
        val input = EditText(this).apply {
            this.hint = hint; setHintTextColor(C.muted); setTextColor(C.text); textSize = 14f
            background = null; setSingleLine(true); setPadding(dp(10), 0, 0, 0)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = onChange(s?.toString().orEmpty().trim())
            })
            if (onSubmit != null) setOnEditorActionListener { v, id, _ -> if (id == EditorInfo.IME_ACTION_SEARCH) { onSubmit(v.text.toString().trim()); true } else false }
        }
        box.addView(input, lp(0, -1, 1f))
        return box to input
    }

    /** Side rail + content area. Returns the content container. */
    private fun shell(active: String): LinearLayout {
        stopPreview()
        refreshList = null
        clockTick?.let { main.removeCallbacks(it) }; clockTick = null
        val root = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(C.bg) }
        val rail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(C.rail); setPadding(0, dp(18), 0, dp(14))
        }
        val mark = FrameLayout(this).apply { background = shape(C.accent, 12) }
        mark.addView(icon(R.drawable.ic_play, Color.WHITE, 20), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
        rail.addView(mark, lp(dp(42), dp(42), b = 18))
        fun item(res: Int, key: String, desc: String, go: () -> Unit) {
            val on = key == active
            val f = FrameLayout(this).apply {
                contentDescription = desc
                background = focusable(if (on) C.panel2 else Color.TRANSPARENT, 14, null)
                addView(icon(res, if (on) Color.WHITE else C.muted, 22), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            }
            clickable(f, go)
            rail.addView(f, lp(dp(52), dp(48), b = 8))
        }
        item(R.drawable.ic_home, "home", "Home") { home() }
        item(R.drawable.ic_live, "live", "Live TV") { load("live") }
        item(R.drawable.ic_movie, "vod", "Movies") { load("vod") }
        item(R.drawable.ic_series, "series", "Series") { load("series") }
        item(R.drawable.ic_guide, "guide", "TV guide") { load("live", guide = true) }
        item(R.drawable.ic_star, "favs", "Favourites") { browse("favs", favourites(), "Favourites") }
        rail.addView(View(this), lp(1, 0, 1f))
        item(R.drawable.ic_settings, "settings", "Settings") { settings() }
        root.addView(rail, LinearLayout.LayoutParams(dp(if (medium) 76 else 64), -1))
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content, lp(0, -1, 1f))
        setContentView(root)
        return content
    }

    // ---------------------------------------------------------------- home

    private fun greeting(): String {
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when { h < 12 -> "Good morning"; h < 18 -> "Good afternoon"; else -> "Good evening" }
    }

    private fun home() {
        val content = shell("home")
        back = null
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(if (medium) 32 else 16), dp(22), dp(if (medium) 32 else 16), dp(22)) }
        scroll.addView(page)
        content.addView(scroll, lp(-1, -1))

        // Header: brand + date, search, clock + account.
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(text(SpannableStringBuilder("YepPlayer").apply { setSpan(ForegroundColorSpan(C.accent), 0, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }, 26f, C.text, 800))
        brand.addView(text("${greeting()} · ${longDayFmt.format(Date())}", 13f, C.muted).apply { setPadding(0, dp(4), 0, 0) })
        header.addView(brand, lp(0, -2, 1f))
        if (medium) {
            val (box, input) = searchBox("Search channels, movies, series", {}) { q -> globalSearch(q) }
            header.addView(box, lp(dp(300), dp(46), r = 0))
        }
        val clockCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END; setPadding(dp(16), 0, 0, 0) }
        val clock = text(timeFmt.format(Date()), 24f, C.text, 700)
        clockCol.addView(clock)
        val acct = text(accountLine(), 12f, C.muted).apply { setPadding(0, dp(4), 0, 0) }
        clockCol.addView(acct)
        header.addView(clockCol)
        if (!medium) header.addView(iconButton(R.drawable.ic_search, "Search") { searchDialog() }, lp(dp(44), dp(44), l = 8))
        page.addView(header)
        clockTick = object : Runnable { override fun run() { clock.text = timeFmt.format(Date()); main.postDelayed(this, 20_000) } }.also { main.postDelayed(it, 20_000) }
        refreshAccount { acct.text = accountLine() }

        if (profile == null) {
            val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = shape(C.panel, 22, C.border); setPadding(dp(24), dp(22), dp(24), dp(22)) }
            card.addView(text("Welcome to YepPlayer", 24f, C.text, 800))
            card.addView(text("Add your Xtream Codes account or an M3U playlist to bring your live channels, films and series together.", 14f, C.text2, 400, 3).apply { setPadding(0, dp(8), 0, dp(16)) })
            card.addView(pill("Connect a playlist", true) { configure() })
            page.addView(card, lp(-1, -2, t = 22))
        }

        // Big tiles.
        val tiles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun tile(res: Int, title: String, sub: String, primary: Boolean, weight: Float, go: () -> Unit) {
            val t = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = if (primary) focusable(C.accent, 22, null) else focusable(C.panel, 22, C.border)
                setPadding(dp(if (medium) 24 else 14), dp(20), dp(14), dp(18))
            }
            t.addView(icon(res, if (primary) Color.WHITE else C.orange, if (medium) 40 else 30))
            t.addView(View(this), lp(1, 0, 1f))
            t.addView(text(title, if (medium) 28f else 19f, if (primary) Color.WHITE else C.text, 800))
            t.addView(text(sub, 13f, if (primary) C.accentSoft else C.muted).apply { setPadding(0, dp(6), 0, 0) })
            clickable(t, go)
            tiles.addView(t, lp(0, -1, weight, r = 8, l = 8))
        }
        tile(R.drawable.ic_live, "Live TV", countLine("live", "channels", "Channels & groups"), true, 1.35f) { load("live") }
        tile(R.drawable.ic_movie, "Movies", countLine("vod", "titles", "Your film library"), false, 1f) { load("vod") }
        tile(R.drawable.ic_series, "Series", countLine("series", "shows", "Seasons & episodes"), false, 1f) { load("series") }
        page.addView(tiles, lp(-1, dp(if (medium) 210 else 150), t = 22, l = -8, r = -8))

        // Shortcuts.
        val shortcuts = GridLayoutCompat(if (medium) 4 else 2)
        fun shortcut(res: Int, label: String, go: () -> Unit) {
            val s = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = focusable(C.panel, 16, C.border); setPadding(dp(18), 0, dp(12), 0)
            }
            s.addView(icon(res, Color.rgb(125, 184, 255), 22))
            s.addView(text(label, 15f, C.text, 600).apply { setPadding(dp(12), 0, 0, 0) })
            clickable(s, go)
            shortcuts.add(s, dp(60))
        }
        shortcut(R.drawable.ic_guide, "TV Guide") { load("live", guide = true) }
        shortcut(R.drawable.ic_replay, "Catch-up") { load("live", catchupOnly = true) }
        shortcut(R.drawable.ic_star, "Favourites") { browse("favs", favourites(), "Favourites") }
        shortcut(R.drawable.ic_user, "Playlists") { configure() }
        page.addView(shortcuts.view, lp(-1, -2, t = 14))

        // Continue watching.
        val rec = recent()
        if (rec.isNotEmpty()) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
            row.addView(text("Continue watching", 18f, C.text, 700), lp(0, -2, 1f))
            row.addView(text("Recently watched", 13f, C.muted))
            page.addView(row, lp(-1, -2, t = 24))
            val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rec.take(12).forEach { item -> strip.addView(recentCard(item), lp(dp(210), -2, r = 14)) }
            page.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(strip) }, lp(-1, -2, t = 12))
        }
        page.addView(text("Only use streams and playlists you are authorised to access.", 11f, C.faint, 400, 2).apply { gravity = Gravity.CENTER; setPadding(0, dp(24), 0, 0) }, lp(-1, -2))
    }

    /** Simple wrapping grid built from rows of weighted cells (keeps D-pad navigation natural). */
    private inner class GridLayoutCompat(val columns: Int) {
        val view = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        private var row: LinearLayout? = null
        private var count = 0
        fun add(v: View, heightPx: Int) {
            if (count % columns == 0) { row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }; view.addView(row, lp(-1, -2, t = if (count == 0) 0 else 12)) }
            row!!.addView(v, LinearLayout.LayoutParams(0, heightPx, 1f).apply { if (count % columns != 0) leftMargin = dp(12) })
            count++
        }
    }

    private fun recentCard(item: MediaItem): View {
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = focusable(C.panel, 14, C.border) }
        val thumb = FrameLayout(this).apply { background = shape(C.panel2, 14) }
        val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; tag = item.logoUrl }
        thumb.addView(img, FrameLayout.LayoutParams(-1, -1))
        val tag = when (item.kind) { "live" -> "LIVE"; "vod" -> "MOVIE"; "episode" -> "EPISODE"; else -> "REPLAY" }
        thumb.addView(badge(tag, if (item.kind == "live") C.red else C.panel, Color.WHITE), FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(8), dp(8), 0, 0) })
        if (item.logoUrl.isNotBlank()) loadLogo(item.logoUrl, img)
        card.addView(thumb, lp(-1, dp(84)))
        val prog = FrameLayout(this).apply { setBackgroundColor(C.border) }
        val frac = progressOf(item)
        prog.addView(View(this).apply { setBackgroundColor(C.orange) }, FrameLayout.LayoutParams((dp(210) * frac).toInt(), -1))
        card.addView(prog, lp(-1, dp(4)))
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(12)) }
        info.addView(text(item.name, 14f, C.text, 600))
        info.addView(text(remainingLine(item), 12f, C.muted).apply { setPadding(0, dp(4), 0, 0) })
        card.addView(info)
        clickable(card) { openMixed(item, recent()) }
        return card
    }

    private fun progressOf(item: MediaItem): Float {
        if (item.kind == "live") return 1f
        val pos = prefs.getLong(Source.resumeKey(item.url), 0L); val dur = prefs.getLong(Source.durationKey(item.url), 0L)
        return if (pos > 0 && dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
    }

    private fun remainingLine(item: MediaItem): String {
        if (item.kind == "live") return "Channel · ${item.category}"
        val pos = prefs.getLong(Source.resumeKey(item.url), 0L); val dur = prefs.getLong(Source.durationKey(item.url), 0L)
        if (pos <= 0 || dur <= 0) return item.category
        val left = (dur - pos) / 60000
        return if (left >= 60) "${left / 60}h ${left % 60}m left" else "${left}m left"
    }

    private fun countLine(kind: String, noun: String, fallback: String): String {
        val list = cache[kind] ?: return fallback
        val groups = list.map { it.category }.distinct().size
        return "%,d $noun · %d groups".format(list.size, groups)
    }

    private fun accountLine(): String {
        val p = profile ?: return "No playlist yet"
        if (p.type != "xtream") return "M3U playlist"
        val exp = prefs.getLong("acct_exp|${p.base}", -1L)
        val cons = prefs.getString("acct_cons|${p.base}", null)
        val e = when { exp < 0 -> "Xtream account"; exp == 0L -> "No expiry"; else -> "Expires " + expFmt.format(Date(exp * 1000)) }
        return if (cons != null) "$e · $cons connections" else e
    }

    private fun refreshAccount(done: () -> Unit) {
        val p = profile ?: return
        if (p.type != "xtream") return
        val last = prefs.getLong("acct_time|${p.base}", 0L)
        if (System.currentTimeMillis() - last < 3 * 3600_000L) return
        pool.execute {
            try {
                val a = Source.account(p)
                prefs.edit().putLong("acct_exp|${p.base}", a.expiry).putString("acct_cons|${p.base}", "${a.activeConnections}/${a.maxConnections}")
                    .putLong("acct_time|${p.base}", System.currentTimeMillis()).apply()
                runOnUiThread(done)
            } catch (_: Exception) { }
        }
    }

    private fun searchDialog() {
        val input = EditText(this).apply { hint = "Channel, movie or series"; setSingleLine(true) }
        AlertDialog.Builder(this).setTitle("Search").setView(input).setPositiveButton("Search") { _, _ -> globalSearch(input.text.toString().trim()) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun globalSearch(q: String) {
        if (q.isEmpty()) return
        val p = profile ?: run { configure(); return }
        val kinds = if (p.type == "xtream") listOf("live", "vod", "series") else listOf("live")
        toast("Searching…")
        pool.execute {
            val all = kinds.flatMap { k -> cache[k] ?: try { Source.load(p, k).also { l -> runOnUiThread { cache[k] = l } } } catch (_: Exception) { emptyList() } }
            val found = all.filter { it.name.contains(q, true) }.take(1500)
            runOnUiThread { browse("search", found, "Search: $q") }
        }
    }

    // ---------------------------------------------------------------- playlist setup

    private fun configure() {
        val content = shell("settings")
        back = { home() }
        val scroll = ScrollView(this)
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(if (medium) 40 else 18), dp(24), dp(if (medium) 40 else 18), dp(24)) }
        scroll.addView(c); content.addView(scroll, lp(-1, -1))
        c.addView(text("Playlists", 26f, C.text, 800))
        val current = profile
        fun field(hintText: String, value: String, password: Boolean = false): EditText = EditText(this).apply {
            hint = hintText; setHintTextColor(C.muted); setTextColor(C.text); textSize = 15f; setSingleLine(true)
            setText(value); background = focusable(C.panel, 14, C.border); setPadding(dp(16), 0, dp(16), 0)
            inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val card1 = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = shape(C.panel, 20, C.border); setPadding(dp(20), dp(18), dp(20), dp(20)) }
        card1.addView(text("XTREAM CODES", 12f, Color.rgb(125, 184, 255), 800).apply { letterSpacing = 0.1f })
        val server = field("Server URL, e.g. http://provider.com:8080", current?.takeIf { it.type == "xtream" }?.base ?: "")
        val user = field("Username", current?.takeIf { it.type == "xtream" }?.username ?: "")
        val pass = field(if (current?.type == "xtream") "Password (leave empty to keep current)" else "Password", "", true)
        listOf(server, user, pass).forEach { card1.addView(it, lp(-1, dp(50), t = 12)) }
        card1.addView(pill("Test & save Xtream account", true) {
            val password = pass.text.toString().ifEmpty { current?.takeIf { it.type == "xtream" }?.password.orEmpty() }
            if (server.text.isBlank() || user.text.isBlank() || password.isBlank()) { toast("Fill in all fields"); return@pill }
            val p = Profile("xtream", server.text.toString().trim(), user.text.toString().trim(), password)
            toast("Checking account…")
            pool.execute {
                try {
                    val info = Source.account(p)
                    runOnUiThread {
                        val saved = p.copy(base = Source.base(p))
                        saveProfile(saved)
                        prefs.edit().putLong("acct_exp|${saved.base}", info.expiry).putString("acct_cons|${saved.base}", "${info.activeConnections}/${info.maxConnections}")
                            .putLong("acct_time|${saved.base}", System.currentTimeMillis()).apply()
                        toast("Connected · ${info.status}")
                        home()
                    }
                } catch (e: Exception) { runOnUiThread { toast(e.message ?: "Unable to connect") } }
            }
        }, lp(-2, dp(46), t = 16))
        c.addView(card1, lp(-1, -2, t = 18))

        val card2 = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = shape(C.panel, 20, C.border); setPadding(dp(20), dp(18), dp(20), dp(20)) }
        card2.addView(text("M3U PLAYLIST", 12f, Color.rgb(125, 184, 255), 800).apply { letterSpacing = 0.1f })
        val m3u = field("https://example.com/playlist.m3u", current?.takeIf { it.type == "m3u" }?.base ?: "")
        card2.addView(m3u, lp(-1, dp(50), t = 12))
        card2.addView(pill("Save M3U URL", true) {
            val v = m3u.text.toString().trim()
            val xt = Regex("^(https?://[^?]+)/get\\.php\\?(.*)$", RegexOption.IGNORE_CASE).find(v)
            val qp = xt?.groupValues?.get(2)?.split('&')?.associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('=', ""), "UTF-8") }
            if (xt != null && !qp?.get("username").isNullOrBlank() && !qp?.get("password").isNullOrBlank()) {
                saveProfile(Profile("xtream", xt.groupValues[1], qp!!["username"]!!, qp["password"]!!))
                toast("Xtream link detected: using Xtream mode for movies, series and guide")
                home()
            } else if (!v.startsWith("http", true)) toast("Enter an HTTP(S) URL")
            else { saveProfile(Profile("m3u", v)); home() }
        }, lp(-2, dp(46), t = 16))
        c.addView(card2, lp(-1, -2, t = 16))
        c.addView(text("Your password is encrypted on this device with the Android Keystore.", 12f, C.muted, 400, 2).apply { setPadding(0, dp(14), 0, 0) })
    }

    private fun saveProfile(p: Profile) {
        Source.saveProfile(prefs, p)
        profile = p
        cache.clear(); nowCache.clear(); detailsCache.clear()
    }

    // ---------------------------------------------------------------- loading

    private fun kindTitle(kind: String) = when (kind) { "live" -> "Live TV"; "vod" -> "Movies"; "series" -> "Series"; "episode" -> "Episodes"; else -> kind }

    private fun load(type: String, guide: Boolean = false, refresh: Boolean = false, catchupOnly: Boolean = false) {
        val p = profile ?: run { configure(); return }
        if (p.type == "m3u" && type != "live") { toast("Movies and series need an Xtream account"); return }
        val title = when { guide -> "TV Guide"; catchupOnly -> "Catch-up"; else -> kindTitle(type) }
        val active = when { guide -> "guide"; catchupOnly -> "live"; else -> type }
        fun show(list: List<MediaItem>) = browse(type, if (catchupOnly) list.filter { it.catchup } else list, title, guide = guide, active = active)
        val cached = cache[type]
        if (cached != null && !refresh) { show(cached); return }
        val content = shell(active)
        back = { home() }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        box.addView(ProgressBar(this).apply { isIndeterminate = true; indeterminateTintList = ColorStateList.valueOf(C.accent) }, lp(dp(48), dp(48)))
        box.addView(text("Loading $title…", 15f, C.muted).apply { setPadding(0, dp(14), 0, 0) })
        content.addView(box, lp(-1, -1))
        pool.execute {
            try {
                val loaded = Source.load(p, type)
                runOnUiThread { cache[type] = loaded; show(loaded) }
            } catch (e: Exception) {
                runOnUiThread {
                    box.removeAllViews()
                    box.addView(text("Connection problem", 22f, C.text, 800))
                    box.addView(text(e.message ?: "Unable to load source", 14f, C.muted, 400, 4).apply { gravity = Gravity.CENTER; setPadding(dp(24), dp(10), dp(24), dp(18)) })
                    box.addView(pill("Try again", true) { load(type, guide, true, catchupOnly) })
                }
            }
        }
    }

    // ---------------------------------------------------------------- browse screen (groups | list or grid | preview)

    private fun hiddenKey(kind: String) = "hidden_groups_$kind"
    private fun hiddenGroups(kind: String): Set<String> = prefs.getStringSet(hiddenKey(kind), null)?.toSet()
        ?: (if (kind == "live") prefs.getStringSet("hidden_groups", emptySet())?.toSet() else null) ?: emptySet()

    private data class Group(val name: String, val count: Int)

    /**
     * [kind]: live / vod / series / episode, or favs / recent / search for mixed lists.
     * Live uses a channel list with a preview pane on wide screens; movies and series use a poster grid with a details card.
     */
    private fun browse(kind: String, data: List<MediaItem>, title: String, guide: Boolean = false, active: String = kind, onBack: () -> Unit = { home() }) {
        val content = shell(active)
        back = onBack
        val grid = kind == "vod" || kind == "series"
        val mixed = kind in setOf("favs", "recent", "search")
        val hidden = if (kind in setOf("live", "vod", "series")) hiddenGroups(kind) else emptySet()
        val base = data.filter { it.category !in hidden }
        val allLabel = when (kind) { "live" -> "All channels"; "vod" -> "All movies"; "series" -> "All series"; "episode" -> "All episodes"; else -> "All" }
        val specials = if (kind in setOf("live", "vod", "series")) listOf("Favourites", "Recently watched") else emptyList()
        val cats = base.groupBy { it.category }
        val groups = listOf(Group(allLabel, base.size)) +
            specials.map { s -> Group(s, (if (s == "Favourites") favourites() else recent()).count { it.kind == kind || (kind == "series" && it.kind == "episode") }) } +
            (if (kind == "episode") cats.keys.toList() else cats.keys.sortedBy { it.lowercase() }).map { Group(it, cats[it]!!.size) }
        var groupIndex = 0
        var query = ""
        var sort = 0 // 0 provider order, 1 recently added, 2 A–Z
        var selected: MediaItem? = null
        var visible = base

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        content.addView(row, lp(-1, -1))

        // Groups pane (or a spinner on phones).
        val groupAdapter = object : BaseAdapter() {
            override fun getCount() = groups.size
            override fun getItem(position: Int) = groups[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val v = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), 0, dp(14), 0)
                    addView(text("", 15f, C.text2, 600), lp(0, -2, 1f)); addView(text("", 12f, C.faint, 500))
                    layoutParams = AbsListView.LayoutParams(-1, dp(46))
                }
                val g = groups[position]; val on = position == groupIndex
                (v.getChildAt(0) as TextView).apply { text = g.name; setTextColor(if (on) Color.WHITE else C.text2) }
                (v.getChildAt(1) as TextView).apply { text = "%,d".format(g.count); setTextColor(if (on) C.accentSoft else C.faint) }
                v.background = shape(if (on) C.accent else Color.TRANSPARENT, 12)
                return v
            }
        }
        var groupList: ListView? = null
        var groupSpinner: Spinner? = null
        val mainPane = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(if (medium) 18 else 12), dp(18), dp(if (medium) 18 else 12), 0) }

        if (medium) {
            val gp = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.rail); setPadding(dp(12), dp(18), dp(12), dp(12)) }
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), 0, 0, dp(12)) }
            head.addView(iconButton(R.drawable.ic_back, "Back", 44, C.panel2) { onBack() })
            head.addView(text(title, 21f, C.text, 800).apply { setPadding(dp(10), 0, 0, 0) }, lp(0, -2, 1f))
            gp.addView(head)
            val gl = ListView(this).apply { divider = null; dividerHeight = dp(4); adapter = groupAdapter; selector = shape(Color.argb(60, 47, 123, 255), 12); isVerticalScrollBarEnabled = false }
            gp.addView(gl, lp(-1, 0, 1f))
            if (kind in setOf("live", "vod", "series")) gp.addView(pill("Manage groups") { manageGroups(data, kind) { browse(kind, data, title, guide, active, onBack) } }, lp(-1, dp(44), t = 8))
            row.addView(gp, LinearLayout.LayoutParams(dp(if (wide) 250 else 210), -1))
            row.addView(View(this).apply { setBackgroundColor(C.line) }, LinearLayout.LayoutParams(dp(1), -1))
            groupList = gl
        } else {
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(iconButton(R.drawable.ic_back, "Back", 44, C.panel2) { onBack() })
            head.addView(text(title, 20f, C.text, 800).apply { setPadding(dp(10), 0, 0, 0) }, lp(0, -2, 1f))
            if (kind in setOf("live", "vod", "series")) head.addView(iconButton(R.drawable.ic_list, "Manage groups") { manageGroups(data, kind) { browse(kind, data, title, guide, active, onBack) } })
            mainPane.addView(head, lp(-1, -2, b = 10))
            if (groups.size > 2) {
                val sp = Spinner(this).apply {
                    adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, groups.map { "${it.name}  (${it.count})" })
                    background = shape(C.panel, 14, C.border)
                }
                mainPane.addView(sp, lp(-1, dp(44), b = 10))
                groupSpinner = sp
            }
        }
        row.addView(mainPane, lp(0, -1, if (grid) 1f else (if (wide && kind == "live") 1.05f else 1f)))

        // Search + sort.
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        lateinit var render: () -> Unit
        val (box, _) = searchBox(if (medium) "Search in ${title.lowercase()}" else "Search", { q -> query = q; render() })
        tools.addView(box, lp(0, dp(44), 1f))
        if (grid) {
            val sortBtn = pill("Default order") {}
            sortBtn.setOnClickListener {
                sort = (sort + 1) % 3
                ((sortBtn.getChildAt(0)) as TextView).text = when (sort) { 1 -> "Recently added"; 2 -> "A–Z"; else -> "Default order" }
                render()
            }
            tools.addView(sortBtn, lp(-2, dp(44), l = 10))
        } else if (kind == "live") {
            tools.addView(iconButton(R.drawable.ic_sort, "Sort A to Z") { sort = if (sort == 2) 0 else 2; render(); toast(if (sort == 2) "Sorted A–Z" else "Provider order") }, lp(dp(44), dp(44), l = 10))
        }
        if (kind in setOf("live", "vod", "series")) tools.addView(iconButton(R.drawable.ic_replay, "Refresh") { load(kind, guide, refresh = true) }, lp(dp(44), dp(44), l = 10))
        mainPane.addView(tools, lp(-1, -2, b = 12))

        // Details card for movies/series (tablets and TVs).
        val details = if (grid && medium) DetailsCard(kind) else null
        details?.let { mainPane.addView(it.view, lp(-1, -2, b = 14)); it.view.visibility = View.GONE }

        val countLabel = text("", 12f, C.muted).apply { setPadding(dp(4), 0, 0, dp(8)) }
        mainPane.addView(countLabel)

        // Preview pane for live TV on wide screens.
        val previewPane = if (kind == "live" && wide) PreviewPane() else null
        previewPane?.let {
            row.addView(View(this).apply { setBackgroundColor(C.line) }, LinearLayout.LayoutParams(dp(1), -1))
            row.addView(it.view, lp(0, -1, 1f))
        }

        fun select(item: MediaItem) {
            selected = item
            details?.show(item)
            previewPane?.show(item, visible)
        }

        val adapter: BaseAdapter
        val collection: AbsListView
        if (grid) {
            adapter = PosterAdapter { visible }
            collection = GridView(this).apply {
                numColumns = GridView.AUTO_FIT; columnWidth = dp(if (medium) 124 else 104); stretchMode = GridView.STRETCH_COLUMN_WIDTH
                horizontalSpacing = dp(12); verticalSpacing = dp(14); this.adapter = adapter; clipToPadding = false; setPadding(0, 0, 0, dp(16))
                selector = shape(Color.TRANSPARENT, 12); setDrawSelectorOnTop(false)
            }
        } else {
            adapter = RowAdapter(kind, mixed) { visible to selected }
            collection = ListView(this).apply {
                divider = null; dividerHeight = dp(4); this.adapter = adapter; isFastScrollEnabled = true
                selector = shape(Color.argb(60, 47, 123, 255), 12); clipToPadding = false; setPadding(0, 0, 0, dp(16))
            }
        }
        mainPane.addView(collection, lp(-1, 0, 1f))

        render = {
            val g = groups.getOrNull(groupIndex)?.name ?: allLabel
            var list = when (g) {
                allLabel -> base
                "Favourites" -> if (specials.isNotEmpty()) favourites().filter { it.kind == kind } else base.filter { it.category == g }
                "Recently watched" -> if (specials.isNotEmpty()) recent().filter { it.kind == kind || (kind == "series" && it.kind == "episode") } else base.filter { it.category == g }
                else -> cats[g] ?: emptyList()
            }
            if (query.isNotEmpty()) list = list.filter { it.name.contains(query, true) || it.category.contains(query, true) }
            list = when (sort) { 1 -> list.sortedByDescending { it.added }; 2 -> list.sortedBy { it.name.lowercase() }; else -> list }
            visible = list
            adapter.notifyDataSetChanged()
            val noun = when (kind) { "live" -> "channels"; "vod" -> "movies"; "series" -> "series"; "episode" -> "episodes"; else -> "items" }
            countLabel.text = if (list.isEmpty()) "Nothing here yet" else "%,d %s".format(list.size, noun)
            if (selected == null || list.none { sameItem(it, selected!!) }) list.firstOrNull()?.let { if (details != null || previewPane != null) select(it) }
        }
        refreshList = { adapter.notifyDataSetChanged() }

        groupList?.setOnItemClickListener { _, _, position, _ -> groupIndex = position; groupAdapter.notifyDataSetChanged(); selected = null; render() }
        groupSpinner?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { if (position != groupIndex) { groupIndex = position; selected = null; render() } }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val sidePane = details != null || previewPane != null
        collection.setOnItemClickListener { _, _, position, _ ->
            val item = visible.getOrNull(position) ?: return@setOnItemClickListener
            val again = selected?.let { sameItem(it, item) } == true
            if (sidePane && !again && !(kind == "live" && guide)) { select(item); adapter.notifyDataSetChanged(); return@setOnItemClickListener }
            when {
                mixed -> openMixed(item, visible)
                kind == "live" && guide && !wide -> withPin(item) { showEpg(item, visible) }
                kind == "live" && guide -> { select(item); adapter.notifyDataSetChanged() }
                else -> openItem(item, visible) { browse(kind, data, title, guide, active, onBack) }
            }
        }
        collection.setOnItemLongClickListener { _, _, position, _ ->
            val item = visible.getOrNull(position) ?: return@setOnItemLongClickListener true
            itemMenu(item, visible); true
        }
        // TV remotes: moving focus over a row updates the preview / details.
        collection.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (sidePane) visible.getOrNull(position)?.let { select(it); adapter.notifyDataSetChanged() }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        previewPane?.onWatch = { item -> openItem(item, visible) { browse(kind, data, title, guide, active, onBack) } }
        details?.onOpen = { item -> openItem(item, visible) { browse(kind, data, title, guide, active, onBack) } }
        render()
    }

    // ---------------------------------------------------------------- list rows

    private inner class RowHolder {
        val row = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = AbsListView.LayoutParams(-1, dp(68))
        }
        val num = text("", 13f, C.faint, 600).apply { gravity = Gravity.END }
        val logoBox = FrameLayout(this@MainActivity).apply { background = shape(C.panel2, 10) }
        val logo = ImageView(this@MainActivity).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(dp(4), dp(4), dp(4), dp(4)) }
        val initials = text("", 11f, C.muted, 800).apply { gravity = Gravity.CENTER }
        val name = text("", 15f, C.text, 600)
        val now = text("", 12f, C.muted)
        val progress = FrameLayout(this@MainActivity).apply { background = shape(C.border, 2) }
        val bar = View(this@MainActivity).apply { background = shape(C.orange, 2) }
        val star = icon(R.drawable.ic_star_filled, C.gold, 18)
        val replay = icon(R.drawable.ic_replay, C.muted, 16)
        init {
            row.addView(num, lp(dp(34), -2, r = 12))
            logoBox.addView(initials, FrameLayout.LayoutParams(-1, -1))
            logoBox.addView(logo, FrameLayout.LayoutParams(-1, -1))
            row.addView(logoBox, lp(dp(48), dp(48)))
            val info = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(8), 0) }
            info.addView(name); info.addView(now, lp(-1, -2, t = 4))
            progress.addView(bar, FrameLayout.LayoutParams(0, -1))
            info.addView(progress, lp(-1, dp(3), t = 6))
            row.addView(info, lp(0, -2, 1f))
            row.addView(replay, lp(dp(16), dp(16), r = 8))
            row.addView(star)
            row.tag = this
        }
    }

    private fun initialsOf(name: String) = name.split(' ', '-', '|', ':').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(if (it.length <= 3) 3 else 1) }.uppercase().take(4)

    private inner class RowAdapter(val kind: String, val mixed: Boolean, val source: () -> Pair<List<MediaItem>, MediaItem?>) : BaseAdapter() {
        override fun getCount() = source().first.size
        override fun getItem(position: Int) = source().first[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val h = (convertView?.tag as? RowHolder) ?: RowHolder()
            val (rows, sel) = source()
            val item = rows[position]
            val on = sel != null && sameItem(sel, item)
            h.row.background = if (on) shape(C.panel2, 12, C.accent, 2) else shape(Color.TRANSPARENT, 12)
            h.num.text = if (item.kind == "live" && !mixed) (if (item.num > 0) item.num else position + 1).toString() else ""
            h.num.visibility = if (h.num.text.isEmpty()) View.GONE else View.VISIBLE
            h.name.text = item.name
            h.initials.text = initialsOf(item.name)
            h.logo.tag = item.logoUrl
            h.logo.setImageDrawable(null)
            if (item.logoUrl.isNotBlank()) loadLogo(item.logoUrl, h.logo)
            h.star.visibility = if (isFavourite(item)) View.VISIBLE else View.GONE
            h.replay.visibility = if (item.catchup) View.VISIBLE else View.GONE
            var frac = -1f
            h.now.text = when (item.kind) {
                "live" -> {
                    val e = nowFor(item)
                    if (e != null) { val n = System.currentTimeMillis() / 1000; frac = ((n - e.start).toFloat() / (e.stop - e.start)).coerceIn(0f, 1f); e.title }
                    else if (mixed) item.category else (if (profile?.type == "xtream") "" else item.category)
                }
                "vod", "episode" -> { val f = progressOf(item); if (f > 0f) frac = f; if (mixed) "${kindTitle(item.kind)} · ${remainingLine(item)}" else remainingLine(item) }
                else -> if (mixed) "${kindTitle(item.kind)} · ${item.category}" else item.category
            }
            h.now.visibility = if (h.now.text.isEmpty()) View.GONE else View.VISIBLE
            h.progress.visibility = if (frac >= 0f) View.VISIBLE else View.GONE
            if (frac >= 0f) h.progress.post { h.bar.layoutParams = FrameLayout.LayoutParams((h.progress.width * frac).toInt(), -1); h.bar.requestLayout() }
            return h.row
        }
    }

    /** Current programme for a live channel; loaded lazily and cached until it ends. */
    private fun nowFor(item: MediaItem): EpgEntry? {
        val p = profile ?: return null
        if (p.type != "xtream") return null
        val now = System.currentTimeMillis() / 1000
        if (nowCache.containsKey(item.id)) {
            val e = nowCache[item.id]
            if (e == null || e.stop > now) return e
        }
        synchronized(nowLoading) { if (!nowLoading.add(item.id)) return null }
        epgPool.execute {
            val e = try { Source.epg(p, item.id).firstOrNull { it.isNow() } } catch (_: Exception) { null }
            runOnUiThread {
                nowCache[item.id] = e
                synchronized(nowLoading) { nowLoading.remove(item.id) }
                main.removeCallbacks(listRefresher); main.postDelayed(listRefresher, 250)
            }
        }
        return null
    }

    private val listRefresher = Runnable { refreshList?.invoke() }

    // ---------------------------------------------------------------- poster grid

    private inner class PosterHolder {
        val cell = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(3), dp(3), dp(3), dp(3)) }
        val frame = FrameLayout(this@MainActivity).apply { background = shape(C.panel2, 12) }
        val img = ImageView(this@MainActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true; background = shape(C.panel2, 12) }
        val placeholder = text("", 12f, C.faint, 600, 3).apply { gravity = Gravity.CENTER; setPadding(dp(8), 0, dp(8), 0) }
        val rating = badge("", C.bg, C.gold)
        val bar = View(this@MainActivity).apply { setBackgroundColor(C.orange) }
        val title = text("", 13f, C.text, 600)
        init {
            frame.addView(placeholder, FrameLayout.LayoutParams(-1, -1))
            frame.addView(img, FrameLayout.LayoutParams(-1, -1))
            frame.addView(rating, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(6), dp(6), 0) })
            frame.addView(bar, FrameLayout.LayoutParams(0, dp(4), Gravity.BOTTOM))
            cell.addView(frame, lp(-1, dp(if (medium) 176 else 150)))
            cell.addView(title, lp(-1, -2, t = 8))
            cell.tag = this
        }
    }

    private inner class PosterAdapter(val source: () -> List<MediaItem>) : BaseAdapter() {
        override fun getCount() = source().size
        override fun getItem(position: Int) = source()[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val h = (convertView?.tag as? PosterHolder) ?: PosterHolder()
            val item = source()[position]
            h.title.text = item.name
            h.placeholder.text = item.name
            h.rating.text = "★ ${item.rating.take(3)}"
            h.rating.visibility = if (item.rating.isNotBlank()) View.VISIBLE else View.GONE
            h.img.tag = item.logoUrl; h.img.setImageDrawable(null)
            if (item.logoUrl.isNotBlank()) loadLogo(item.logoUrl, h.img, 240)
            val f = progressOf(item)
            h.bar.visibility = if (f > 0f) View.VISIBLE else View.GONE
            if (f > 0f) h.frame.post { h.bar.layoutParams = FrameLayout.LayoutParams((h.frame.width * f).toInt(), dp(4), Gravity.BOTTOM); h.bar.requestLayout() }
            h.cell.background = focusable(Color.TRANSPARENT, 14, null)
            return h.cell
        }
    }

    // ---------------------------------------------------------------- details card (movies / series)

    private inner class DetailsCard(val kind: String) {
        val view = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; background = shape(C.panel, 18, C.border); setPadding(dp(16), dp(16), dp(16), dp(16)) }
        private val poster = ImageView(this@MainActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true; background = shape(C.panel2, 12) }
        private val title = text("", 22f, C.text, 800)
        private val meta = text("", 13f, C.muted)
        private val plot = text("", 13f, C.text2, 400, 3).apply { setLineSpacing(0f, 1.25f) }
        private val playBtn: LinearLayout
        private val favBtn: FrameLayout
        private var current: MediaItem? = null
        var onOpen: ((MediaItem) -> Unit)? = null
        init {
            view.addView(poster, lp(dp(104), dp(150)))
            val col = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), 0, 0, 0) }
            col.addView(title); col.addView(meta, lp(-1, -2, t = 8)); col.addView(plot, lp(-1, -2, t = 8))
            val btns = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            playBtn = pill("Play", true, R.drawable.ic_play) { current?.let { onOpen?.invoke(it) } }
            favBtn = iconButton(R.drawable.ic_star, "Add to favourites", 44, C.panel2) { current?.let { toggleFavourite(it); show(it); refreshList?.invoke() } }
            btns.addView(playBtn); btns.addView(favBtn, lp(dp(44), dp(44), l = 10))
            col.addView(btns, lp(-2, -2, t = 12))
            view.addView(col, lp(0, -2, 1f))
        }
        fun show(item: MediaItem) {
            current = item
            view.visibility = View.VISIBLE
            title.text = item.name
            poster.tag = item.logoUrl; poster.setImageDrawable(null)
            if (item.logoUrl.isNotBlank()) loadLogo(item.logoUrl, poster, 240)
            val pos = prefs.getLong(Source.resumeKey(item.url), 0L)
            ((playBtn.getChildAt(1)) as TextView).text = when { kind == "series" -> "Episodes"; pos > 0 -> "Resume ${clockTime(pos)}"; else -> "Play" }
            ((favBtn.getChildAt(0)) as ImageView).setImageResource(if (isFavourite(item)) R.drawable.ic_star_filled else R.drawable.ic_star)
            ((favBtn.getChildAt(0)) as ImageView).imageTintList = ColorStateList.valueOf(if (isFavourite(item)) C.gold else C.text)
            fill(item, detailsCache[item.kind + item.id])
            if (!detailsCache.containsKey(item.kind + item.id)) {
                val p = profile ?: return
                epgPool.execute {
                    val d = try { Source.details(p, item) } catch (_: Exception) { null }
                    runOnUiThread { detailsCache[item.kind + item.id] = d; if (current?.let { sameItem(it, item) } == true) fill(item, d) }
                }
            }
        }
        private fun fill(item: MediaItem, d: Details?) {
            val parts = mutableListOf<String>()
            d?.year?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
            d?.duration?.takeIf { it.isNotBlank() }?.let { parts.add(it.removePrefix("00:").replace(Regex("^(\\d+):(\\d+):\\d+$"), "$1h $2m")) }
            (item.rating.ifBlank { d?.rating.orEmpty() }).takeIf { it.isNotBlank() }?.let { parts.add("★ ${it.take(3)}") }
            parts.add(d?.genre?.takeIf { it.isNotBlank() } ?: item.category)
            meta.text = parts.joinToString("   ")
            plot.text = d?.plot?.takeIf { it.isNotBlank() } ?: if (d == null && detailsCache.containsKey(item.kind + item.id)) "" else "Loading details…"
            if (item.logoUrl.isBlank() && !d?.cover.isNullOrBlank()) { poster.tag = d!!.cover; loadLogo(d.cover, poster, 240) }
        }
    }

    private fun clockTime(ms: Long): String { val s = ms / 1000; return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }

    // ---------------------------------------------------------------- live preview pane

    private inner class PreviewPane {
        val view = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(18), dp(20), dp(12)) }
        private val video = FrameLayout(this@MainActivity).apply { background = shape(Color.rgb(5, 7, 11), 16, C.border); clipToOutline = true }
        private val playerView = PlayerView(this@MainActivity).apply { useController = false; setShutterBackgroundColor(Color.rgb(5, 7, 11)) }
        private val hint = text("Video preview · press OK for full screen", 13f, C.faint).apply { gravity = Gravity.CENTER }
        private val chName = text("", 15f, C.text, 700)
        private val nowLabel = text("", 11f, C.orange, 800).apply { letterSpacing = 0.08f }
        private val nowTitle = text("", 20f, C.text, 700, 2)
        private val nowDesc = text("", 13f, C.muted, 400, 2).apply { setLineSpacing(0f, 1.25f) }
        private val catchupBtn: LinearLayout
        private val favBtn: LinearLayout
        private val upcoming = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        private var current: MediaItem? = null
        private var pending: Runnable? = null
        private var triedTs = false
        var onWatch: ((MediaItem) -> Unit)? = null
        init {
            video.addView(hint, FrameLayout.LayoutParams(-1, -1))
            video.addView(playerView, FrameLayout.LayoutParams(-1, -1))
            val badges = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            badges.addView(badge("LIVE", C.red)); badges.addView(View(this@MainActivity), lp(dp(6), 1))
            video.addView(badges, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(12), dp(12), 0, 0) })
            video.addView(chName, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(14), 0, 0, dp(12)) })
            clickable(video) { current?.let { onWatch?.invoke(it) } }
            view.addView(video, lp(-1, dp(260)))
            view.addView(nowLabel, lp(-1, -2, t = 16)); view.addView(nowTitle, lp(-1, -2, t = 6)); view.addView(nowDesc, lp(-1, -2, t = 6))
            val btns = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            btns.addView(pill("Watch", true, R.drawable.ic_play) { current?.let { onWatch?.invoke(it) } })
            catchupBtn = pill("Catch-up") { current?.let { catchup(it) } }
            favBtn = pill("Add to favourites") { current?.let { toggleFavourite(it); show(it, null, restartVideo = false); refreshList?.invoke() } }
            btns.addView(catchupBtn, lp(-2, dp(44), l = 10)); btns.addView(favBtn, lp(-2, dp(44), l = 10))
            view.addView(HorizontalScrollView(this@MainActivity).apply { isHorizontalScrollBarEnabled = false; addView(btns) }, lp(-1, -2, t = 14))
            view.addView(View(this@MainActivity).apply { setBackgroundColor(C.line) }, lp(-1, dp(1), t = 14))
            view.addView(ScrollView(this@MainActivity).apply { addView(upcoming) }, lp(-1, 0, 1f, t = 6))
        }
        fun show(item: MediaItem, siblings: List<MediaItem>?, restartVideo: Boolean = true) {
            val changed = current?.let { sameItem(it, item) } != true
            current = item
            chName.text = item.name
            catchupBtn.visibility = if (item.catchup && profile?.type == "xtream") View.VISIBLE else View.GONE
            ((favBtn.getChildAt(0)) as TextView).text = if (isFavourite(item)) "Remove favourite" else "Add to favourites"
            if (!changed) return
            nowLabel.text = ""; nowTitle.text = item.name; nowDesc.text = item.category; upcoming.removeAllViews()
            loadGuide(item)
            if (restartVideo) {
                pending?.let { main.removeCallbacks(it) }
                preview?.stop()
                val restricted = prefs.getString("pin", null) != null && isRestricted(item)
                hint.text = if (restricted) "Protected by parental PIN" else "Video preview · press OK for full screen"
                if (!restricted) pending = Runnable { startPreview(item) }.also { main.postDelayed(it, 650) }
            }
        }
        private fun startPreview(item: MediaItem) {
            if (current?.let { sameItem(it, item) } != true) return
            val pl = preview ?: buildPreview().also { preview = it }
            playerView.player = pl
            triedTs = false
            pl.setMediaItem(ExoItem.fromUri(item.url)); pl.prepare(); pl.playWhenReady = true
        }
        private fun buildPreview(): ExoPlayer {
            val http = DefaultHttpDataSource.Factory().setUserAgent(Source.USER_AGENT).setAllowCrossProtocolRedirects(true)
            return ExoPlayer.Builder(this@MainActivity).setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this@MainActivity, http))).build().apply {
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        val url = current?.url ?: return
                        if (!triedTs && url.endsWith(".m3u8")) { triedTs = true; setMediaItem(ExoItem.fromUri(url.removeSuffix(".m3u8") + ".ts")); prepare(); playWhenReady = true }
                        else hint.text = "Preview unavailable"
                    }
                })
            }
        }
        private fun loadGuide(item: MediaItem) {
            val p = profile ?: return
            if (p.type != "xtream") return
            epgPool.execute {
                val list = try { Source.epg(p, item.id) } catch (_: Exception) { emptyList() }
                runOnUiThread {
                    if (current?.let { sameItem(it, item) } != true) return@runOnUiThread
                    val now = System.currentTimeMillis() / 1000
                    val cur = list.firstOrNull { it.isNow(now) }
                    if (cur != null) {
                        nowCache[item.id] = cur
                        nowLabel.text = "NOW · ${timeFmt.format(Date(cur.start * 1000))} – ${timeFmt.format(Date(cur.stop * 1000))}"
                        nowTitle.text = cur.title; nowDesc.text = cur.description
                    } else { nowLabel.text = ""; nowDesc.text = "No guide data for this channel" }
                    upcoming.removeAllViews()
                    list.filter { it.start >= (cur?.stop ?: now) }.take(8).forEach { e ->
                        val r = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                        r.addView(text(timeFmt.format(Date(e.start * 1000)), 14f, C.muted, 600), lp(dp(56), -2))
                        r.addView(text(e.title, 14f, C.text2), lp(0, -2, 1f))
                        clickable(r) { epgDetails(item, e, null) }
                        r.background = focusable(Color.TRANSPARENT, 8, null)
                        upcoming.addView(r, lp(-1, dp(40)))
                    }
                }
            }
        }
    }

    private fun stopPreview() { preview?.release(); preview = null }

    // ---------------------------------------------------------------- images

    private fun loadLogo(url: String, target: ImageView, sizeDp: Int = 96) {
        logoCache.get(url)?.let { target.setImageBitmap(it); return }
        if (synchronized(failedLogos) { url in failedLogos }) return
        logoPool.execute {
            if (target.tag != url) return@execute
            try {
                val bytes = URL(url).openConnection().run {
                    connectTimeout = 5000; readTimeout = 8000
                    setRequestProperty("User-Agent", Source.USER_AGENT)
                    getInputStream().use { it.readBytes() }
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                val want = dp(sizeDp)
                while (bounds.outWidth / (sample * 2) >= want && bounds.outHeight / (sample * 2) >= want) sample *= 2
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                if (bitmap != null) {
                    logoCache.put(url, bitmap)
                    runOnUiThread { if (target.tag == url) target.setImageBitmap(bitmap) }
                } else synchronized(failedLogos) { failedLogos.add(url) }
            } catch (_: Exception) { synchronized(failedLogos) { failedLogos.add(url) } }
        }
    }

    private fun manageGroups(source: List<MediaItem> = cache["live"] ?: emptyList(), kind: String = "live", after: () -> Unit = {}) {
        val groups = source.map { it.category }.distinct().sorted()
        if (groups.isEmpty()) { toast("Load a playlist first"); return }
        val hidden = hiddenGroups(kind).toMutableSet()
        val checked = BooleanArray(groups.size) { groups[it] !in hidden }
        AlertDialog.Builder(this).setTitle("Show or hide ${kindTitle(kind)} groups")
            .setMultiChoiceItems(groups.toTypedArray(), checked) { _, which, isChecked -> if (isChecked) hidden.remove(groups[which]) else hidden.add(groups[which]) }
            .setPositiveButton("Save") { _, _ -> prefs.edit().putStringSet(hiddenKey(kind), hidden).apply(); after() }
            .setNeutralButton("Show all") { _, _ -> prefs.edit().putStringSet(hiddenKey(kind), emptySet()).remove("hidden_groups").apply(); after() }
            .setNegativeButton("Cancel", null).show()
    }

    // ---------------------------------------------------------------- favourites & recent

    private fun itemJson(i: MediaItem) = JSONObject().put("id", i.id).put("name", i.name).put("url", i.url).put("category", i.category)
        .put("kind", i.kind).put("epgId", i.epgId).put("catchup", i.catchup).put("seriesId", i.seriesId).put("logoUrl", i.logoUrl)
        .put("archiveDays", i.archiveDays).put("rating", i.rating).put("num", i.num)
    private fun jsonItem(o: JSONObject) = MediaItem(o.optString("id"), o.optString("name"), o.optString("url"), o.optString("category"), o.optString("kind"),
        o.optString("epgId"), o.optBoolean("catchup"), o.optString("seriesId"), o.optString("logoUrl"), o.optInt("archiveDays"), o.optString("rating"), 0L, o.optInt("num"))
    private fun storedList(key: String): List<MediaItem> = try {
        val arr = JSONArray(prefs.getString("$key|${profile?.base}", "[]")); (0 until arr.length()).map { jsonItem(arr.getJSONObject(it)) }
    } catch (_: Exception) { emptyList() }
    private fun storeList(key: String, list: List<MediaItem>) {
        val arr = JSONArray(); list.forEach { arr.put(itemJson(it)) }
        prefs.edit().putString("$key|${profile?.base}", arr.toString()).apply()
    }
    private fun sameItem(a: MediaItem, b: MediaItem) = a.kind == b.kind && a.id == b.id
    private fun favourites() = storedList("fav_items")
    private fun isFavourite(item: MediaItem) = favourites().any { sameItem(it, item) }
    private fun toggleFavourite(item: MediaItem) {
        val list = favourites().toMutableList()
        if (list.removeAll { sameItem(it, item) }.not()) list.add(item)
        storeList("fav_items", list)
    }
    private fun recent() = storedList("recent_items")
    private fun addRecent(item: MediaItem) {
        if (item.kind == "series") return
        storeList("recent_items", (listOf(item) + recent().filterNot { sameItem(it, item) }).take(30))
    }

    // ---------------------------------------------------------------- opening items

    private fun isRestricted(item: MediaItem) = Regex("adult|xxx|18\\+|porn", RegexOption.IGNORE_CASE).containsMatchIn(item.category + " " + item.name)

    private fun withPin(item: MediaItem, go: () -> Unit) {
        val pin = prefs.getString("pin", null)
        if (pin == null || !isRestricted(item)) { go(); return }
        val entry = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD }
        AlertDialog.Builder(this).setTitle("Parental PIN").setView(entry).setPositiveButton("Unlock") { _, _ -> if (entry.text.toString() == pin) go() else toast("Incorrect PIN") }.setNegativeButton("Cancel", null).show()
    }

    private fun openMixed(item: MediaItem, list: List<MediaItem>) = openItem(item, list) { home() }

    private fun openItem(item: MediaItem, siblings: List<MediaItem>, returnTo: () -> Unit) = withPin(item) {
        val p = profile ?: return@withPin
        when (item.kind) {
            "series" -> {
                val content = shell("series")
                back = returnTo
                content.addView(ProgressBar(this).apply { isIndeterminate = true; indeterminateTintList = ColorStateList.valueOf(C.accent) }, lp(dp(48), dp(48)).apply { gravity = Gravity.CENTER; topMargin = dp(80) })
                pool.execute {
                    try {
                        val eps = Source.episodes(p, item.seriesId.ifBlank { item.id }).map { it.copy(logoUrl = it.logoUrl.ifBlank { item.logoUrl }) }
                        runOnUiThread { browse("episode", eps, item.name, active = "series", onBack = returnTo) }
                    } catch (e: Exception) { runOnUiThread { toast(e.message ?: "Episodes unavailable"); returnTo() } }
                }
            }
            "live" -> playLive(item, siblings)
            else -> { addRecent(item); startPlayer(item.url, item.name, live = false) }
        }
    }

    private fun itemMenu(item: MediaItem, siblings: List<MediaItem>) {
        val actions = mutableListOf(if (isFavourite(item)) "Remove from favourites" else "Add to favourites")
        if (item.kind == "live") { actions.add(0, "Watch"); if (profile?.type == "xtream") actions.add("Programme guide"); if (item.catchup && profile?.type == "xtream") actions.add("Catch-up / replay") }
        else actions.add(0, if (item.kind == "series") "Episodes" else "Play")
        AlertDialog.Builder(this).setTitle(item.name).setItems(actions.toTypedArray()) { _, which ->
            when (actions[which]) {
                "Watch", "Play", "Episodes" -> openItem(item, siblings) { home() }
                "Programme guide" -> withPin(item) { showEpg(item, siblings) }
                "Catch-up / replay" -> withPin(item) { catchup(item) }
                else -> { toggleFavourite(item); refreshList?.invoke(); toast(if (isFavourite(item)) "Added to favourites" else "Removed from favourites") }
            }
        }.show()
    }

    private fun playLive(item: MediaItem, siblings: List<MediaItem>) {
        stopPreview() // free the provider connection before full-screen playback
        addRecent(item)
        val channels = siblings.filter { it.kind == "live" }.ifEmpty { listOf(item) }
        val idx = channels.indexOfFirst { sameItem(it, item) }.coerceAtLeast(0)
        val list = if (channels.size > 2000) { val from = (idx - 1000).coerceAtLeast(0); channels.subList(from, (from + 2000).coerceAtMost(channels.size)) } else channels
        val i = list.indexOfFirst { sameItem(it, item) }.coerceAtLeast(0)
        startActivity(Intent(this, PlayerActivity::class.java)
            .putExtra("url", item.url).putExtra("name", item.name).putExtra("live", true)
            .putExtra("urls", list.map { it.url }.toTypedArray())
            .putExtra("names", list.map { it.name }.toTypedArray())
            .putExtra("ids", list.map { it.id }.toTypedArray())
            .putExtra("nums", list.mapIndexed { n, c -> if (c.num > 0) c.num else n + 1 }.toIntArray())
            .putExtra("index", i))
    }

    private fun epgDetails(item: MediaItem, e: EpgEntry, siblings: List<MediaItem>?) {
        AlertDialog.Builder(this).setTitle(e.title)
            .setMessage("${dayFmt.format(Date(e.start * 1000))}  ${timeFmt.format(Date(e.start * 1000))}–${timeFmt.format(Date(e.stop * 1000))}\n\n${e.description.ifBlank { "No description." }}")
            .setPositiveButton("Watch channel") { _, _ -> playLive(item, siblings ?: listOf(item)) }
            .setNegativeButton("Close", null).show()
    }

    private fun showEpg(item: MediaItem, siblings: List<MediaItem>) {
        val p = profile ?: return
        toast("Loading guide…")
        pool.execute {
            try {
                val now = System.currentTimeMillis() / 1000
                val entries = Source.epg(p, item.id).filter { it.stop > now }
                runOnUiThread {
                    val b = AlertDialog.Builder(this).setTitle(item.name + " · TV Guide")
                    if (entries.isEmpty()) b.setMessage("No guide data available for this channel.")
                    else b.setItems(entries.map { (if (it.isNow(now)) "▶ NOW  " else "") + "${timeFmt.format(Date(it.start * 1000))}–${timeFmt.format(Date(it.stop * 1000))}  ${it.title}" }.toTypedArray()) { _, which -> epgDetails(item, entries[which], siblings) }
                    b.setPositiveButton("Watch now") { _, _ -> playLive(item, siblings) }
                    if (item.catchup) b.setNeutralButton("Catch-up") { _, _ -> catchup(item) }
                    b.setNegativeButton("Close", null).show()
                }
            } catch (e: Exception) { runOnUiThread { toast("Guide unavailable: ${e.message}") } }
        }
    }

    /** Lists past programmes that the provider keeps in its archive and plays them via timeshift. */
    private fun catchup(item: MediaItem) {
        val p = profile ?: return
        toast("Loading archive…")
        pool.execute {
            try {
                val now = System.currentTimeMillis() / 1000
                val limit = now - (item.archiveDays.coerceAtLeast(1) * 86400L)
                val past = Source.epg(p, item.id, full = true).filter { it.isPast(now) && it.start >= limit && (it.hasArchive || item.catchup) }.reversed()
                runOnUiThread {
                    if (past.isEmpty()) { toast("No replay programmes available for this channel"); return@runOnUiThread }
                    val labels = past.map { "${dayFmt.format(Date(it.start * 1000))}  ${timeFmt.format(Date(it.start * 1000))}  ${it.title}" }
                    AlertDialog.Builder(this).setTitle("Replay · ${item.name}").setItems(labels.toTypedArray()) { _, which ->
                        val e = past[which]
                        val url = Source.timeshiftUrl(p, item.id, e.serverStart, e.durationMinutes)
                        if (url == null) toast("This programme can't be replayed") else { stopPreview(); addRecent(item); startPlayer(url, "${item.name} · ${e.title}", live = false) }
                    }.setNegativeButton("Close", null).show()
                }
            } catch (e: Exception) { runOnUiThread { toast("Replay unavailable: ${e.message}") } }
        }
    }

    private fun startPlayer(url: String, name: String, live: Boolean) {
        stopPreview()
        startActivity(Intent(this, PlayerActivity::class.java).putExtra("url", url).putExtra("name", name).putExtra("live", live))
    }

    // ---------------------------------------------------------------- settings

    private fun showAccount() {
        val p = profile ?: run { configure(); return }
        if (p.type != "xtream") { toast("Account details are only available for Xtream accounts"); return }
        toast("Checking account…")
        pool.execute {
            try {
                val a = Source.account(p)
                runOnUiThread {
                    val expiry = if (a.expiry > 0) expFmt.format(Date(a.expiry * 1000)) else "Unlimited"
                    AlertDialog.Builder(this).setTitle("Xtream account")
                        .setMessage("Server: ${Source.base(p)}\nUser: ${p.username}\nStatus: ${a.status}${if (a.isTrial) " (trial)" else ""}\nExpires: $expiry\nConnections: ${a.activeConnections} / ${a.maxConnections}" +
                            (if (a.serverTimezone.isNotBlank()) "\nServer time zone: ${a.serverTimezone}" else ""))
                        .setPositiveButton("OK", null).show()
                }
            } catch (e: Exception) { runOnUiThread { toast(e.message ?: "Unable to reach server") } }
        }
    }

    private fun changePin() {
        val existing = prefs.getString("pin", null)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), 0) }
        val old = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD; hint = "Current PIN" }
        val entry = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD; hint = "New PIN, 4–8 digits" }
        if (existing != null) box.addView(old)
        box.addView(entry)
        val b = AlertDialog.Builder(this).setTitle("Parental PIN").setMessage("Basic category keyword protection. This is not a certified parental-control system.").setView(box).setPositiveButton("Save") { _, _ ->
            if (existing != null && old.text.toString() != existing) { toast("Incorrect current PIN"); return@setPositiveButton }
            val value = entry.text.toString(); if (value.matches(Regex("\\d{4,8}"))) { prefs.edit().putString("pin", value).apply(); toast("PIN saved") } else toast("Use 4–8 digits")
        }.setNegativeButton("Cancel", null)
        if (existing != null) b.setNeutralButton("Remove PIN") { _, _ -> if (old.text.toString() == existing) { prefs.edit().remove("pin").apply(); toast("PIN removed") } else toast("Incorrect current PIN") }
        b.show()
    }

    private fun settings() {
        val options = arrayOf("Playlists", "Account information", "Hide / show live TV groups", "Parental PIN", "Recently watched", "Refresh playlist data", "About YepPlayer")
        AlertDialog.Builder(this).setTitle("Settings").setItems(options) { _, which ->
            when (which) {
                0 -> configure()
                1 -> showAccount()
                2 -> manageGroups(cache["live"] ?: emptyList(), "live")
                3 -> changePin()
                4 -> browse("recent", recent(), "Recently watched")
                5 -> { cache.clear(); nowCache.clear(); detailsCache.clear(); logoCache.evictAll(); synchronized(failedLogos) { failedLogos.clear() }; prefs.edit().remove("acct_time|${profile?.base}").apply(); toast("Playlist data will reload") }
                else -> AlertDialog.Builder(this).setTitle("YepPlayer ${packageManager.getPackageInfo(packageName, 0).versionName}")
                    .setMessage("A personal IPTV player for authorised playlists.\n\nTips: long-press an item for more options. In the player, use the channel buttons (or the remote's up/down keys) to zap between channels.")
                    .setPositiveButton("OK", null).show()
            }
        }.show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) { settings(); return true }
        return super.onKeyDown(keyCode, event)
    }

    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()
    override fun onDestroy() { stopPreview(); clockTick?.let { main.removeCallbacks(it) }; pool.shutdownNow(); logoPool.shutdownNow(); epgPool.shutdownNow(); super.onDestroy() }
}
