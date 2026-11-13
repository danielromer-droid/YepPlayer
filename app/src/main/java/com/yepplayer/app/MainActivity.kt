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
    private var resumePreview: (() -> Unit)? = null
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
        C.load(prefs)
        window.statusBarColor = C.rail
        window.navigationBarColor = C.rail
        NetGuard.load(prefs); NetGuard.fileRoot = RecordingService.dir(this).canonicalPath
        profile = Source.readProfile(prefs)
        // Re-save once so an old plain-text password gets encrypted.
        profile?.let { if (prefs.getString("profile", "")?.contains("\"password\"") == true) Source.saveProfile(prefs, it) }
        if (intent?.getStringExtra("open") == "recordings") recordings() else home()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getStringExtra("open") == "recordings") recordings()
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        val b = back
        if (b != null) b() else super.onBackPressed()
    }

    override fun onStop() { stopPreview(); super.onStop() }

    /** Back from full screen: the channel carries on in the small preview window (wide screens). */
    override fun onRestart() { super.onRestart(); resumePreview?.invoke() }

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

    /** List/grid selector for TV remotes: a bright frame drawn on top of the focused row, so it is always visible. */
    private fun tvSelector(radiusDp: Int): Drawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), shape(C.accentTint, radiusDp, Color.WHITE, 3))
        addState(intArrayOf(android.R.attr.state_focused), shape(Color.TRANSPARENT, radiusDp, Color.WHITE, 3))
        addState(intArrayOf(), shape(Color.TRANSPARENT, radiusDp))
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
        if (iconRes != null) addView(icon(iconRes, if (primary) C.onAccent else C.text, 16).apply { (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(8) })
        addView(text(label, 14f, if (primary) C.onAccent else C.text, if (primary) 700 else 600))
        layoutParams = LinearLayout.LayoutParams(-2, dp(44))
        clickable(this, onClick)
    }

    /** Layout params: [w]/[h] in px (or -1/-2), margins in dp. */
    private fun lp(w: Int, h: Int, weight: Float = 0f, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) =
        LinearLayout.LayoutParams(w, h, weight).apply { setMargins(dp(l), dp(t), dp(r), dp(b)) }

    private fun badge(label: String, bg: Int, fg: Int = Color.WHITE) = text(label, 10f, fg, 800).apply {
        letterSpacing = 0.08f; setPadding(dp(7), dp(3), dp(7), dp(3)); background = shape(bg, 6)
    }

    // ---------------------------------------------------------------- recent searches (last 5)

    private fun recentSearches(): List<String> = try {
        JSONArray(prefs.getString("recent_searches", "[]")).let { a -> (0 until a.length()).map { a.getString(it) } }
    } catch (_: Exception) { emptyList() }

    private fun saveRecentSearches(list: List<String>) =
        prefs.edit().putString("recent_searches", JSONArray(list.take(5)).toString()).apply()

    /** Newest first, no duplicates (ignoring case), at most 5. A longer version of the newest word replaces it. */
    private fun rememberSearch(q: String) {
        val w = q.trim().take(60)
        if (w.length < 2) return
        val old = recentSearches().toMutableList()
        if (old.isNotEmpty() && (w.startsWith(old[0], true) || old[0].startsWith(w, true)) && old[0].length <= w.length) old.removeAt(0)
        old.removeAll { it.equals(w, true) }
        saveRecentSearches(listOf(w) + old)
    }

    private fun openKeyboard(input: EditText) {
        input.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        input.post { if (!imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)) imm.toggleSoftInput(android.view.inputmethod.InputMethodManager.SHOW_FORCED, 0) }
    }

    private fun searchBox(hint: String, onChange: (String) -> Unit, onSubmit: ((String) -> Unit)? = null): Pair<LinearLayout, EditText> {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(6), 0); background = shape(C.panel, 22, C.border)
        }
        box.addView(icon(R.drawable.ic_search, C.muted, 17))
        lateinit var input: EditText
        var popup: PopupWindow? = null
        val saveLater = Runnable { rememberSearch(input.text.toString()) }
        val clear = FrameLayout(this).apply {
            contentDescription = "Clear search"; visibility = View.GONE
            background = focusable(C.panel2, 16, null)
            addView(icon(R.drawable.ic_close, C.text, 14), FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER))
        }

        fun showRecent() {
            popup?.dismiss(); popup = null
            val words = recentSearches()
            if (words.isEmpty() || input.text.isNotEmpty() || !input.hasFocus() || box.windowToken == null) return
            val list = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(8), dp(6), dp(8)); background = shape(C.panel, 18, C.border)
                elevation = dp(12).toFloat()
            }
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(2), dp(4), dp(4)) }
            head.addView(text("RECENT SEARCHES", 11f, C.soft, 800).apply { letterSpacing = 0.1f }, lp(0, -2, 1f))
            val wipe = text("Clear all", 12f, C.muted, 600).apply { setPadding(dp(10), dp(8), dp(10), dp(8)); background = focusable(C.panel, 12, null) }
            clickable(wipe) { saveRecentSearches(emptyList()); popup?.dismiss() }
            head.addView(wipe)
            list.addView(head)
            words.forEach { w ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                val pick = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(12), 0)
                    background = focusable(C.panel, 12, null)
                    addView(icon(R.drawable.ic_history, C.muted, 16))
                    addView(text(w, 15f, C.text, 600).apply { setPadding(dp(12), 0, 0, 0) }, lp(0, -2, 1f))
                }
                clickable(pick) {
                    popup?.dismiss()
                    input.setText(w); input.setSelection(w.length)
                    rememberSearch(w)
                    onSubmit?.invoke(w)
                }
                row.addView(pick, lp(0, dp(44), 1f))
                val del = FrameLayout(this).apply {
                    contentDescription = "Remove $w"; background = focusable(C.panel, 18, null)
                    addView(icon(R.drawable.ic_close, C.muted, 13), FrameLayout.LayoutParams(dp(13), dp(13), Gravity.CENTER))
                }
                clickable(del) { saveRecentSearches(recentSearches().filterNot { it.equals(w, true) }); popup?.dismiss(); input.requestFocus(); showRecent() }
                row.addView(del, lp(dp(36), dp(36), l = 4))
                list.addView(row)
            }
            popup = PopupWindow(list, box.width.coerceAtLeast(dp(260)), ViewGroup.LayoutParams.WRAP_CONTENT, false).apply {
                inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED
                isOutsideTouchable = true
                setBackgroundDrawable(null)
                setOnDismissListener { if (popup === this) popup = null }
                showAsDropDown(box, 0, dp(8))
            }
        }

        input = EditText(this).apply {
            this.hint = hint; setHintTextColor(C.muted); setTextColor(C.text); textSize = 14f
            background = null; setSingleLine(true); setPadding(dp(10), 0, dp(6), 0)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val q = s?.toString().orEmpty().trim()
                    clear.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                    main.removeCallbacks(saveLater)
                    if (q.isEmpty()) showRecent() else {
                        popup?.dismiss()
                        if (onSubmit == null) main.postDelayed(saveLater, 2500) // live filters: remember after a pause
                    }
                    onChange(q)
                }
            })
            setOnEditorActionListener { v, id, _ ->
                if (id == EditorInfo.IME_ACTION_SEARCH || id == EditorInfo.IME_ACTION_DONE) {
                    val q = v.text.toString().trim()
                    main.removeCallbacks(saveLater); rememberSearch(q); popup?.dismiss()
                    onSubmit?.invoke(q); onSubmit != null
                } else false
            }
            setOnFocusChangeListener { _, has ->
                box.background = if (has) shape(C.panel, 22, Color.WHITE, 3) else shape(C.panel, 22, C.border)
                if (has) post { showRecent() } else main.postDelayed({ if (popup?.isFocusable != true) popup?.dismiss() }, 150) }
            setOnClickListener { if (popup == null) showRecent() }
            // TV remote: Down moves into the recent list.
            setOnKeyListener { _, code, ev ->
                val pw = popup
                // TV remote OK (or Enter on an empty box): always bring up the on-screen keyboard.
                val ok = code == KeyEvent.KEYCODE_DPAD_CENTER || (code == KeyEvent.KEYCODE_ENTER && input.text.isEmpty())
                if (ok && ev.action == KeyEvent.ACTION_UP) { openKeyboard(input); true }
                else if (ok) true else if (code == KeyEvent.KEYCODE_DPAD_DOWN && ev.action == KeyEvent.ACTION_DOWN && pw != null && pw.isShowing) {
                    pw.isFocusable = true; pw.update()
                    pw.contentView.post { (pw.contentView as? ViewGroup)?.getChildAt(1)?.let { (it as ViewGroup).getChildAt(0).requestFocus() } }
                    true
                } else false
            }
        }
        clickable(clear) { input.setText(""); openKeyboard(input) }
        box.addView(input, lp(0, -1, 1f))
        box.addView(clear, lp(dp(32), dp(32)))
        box.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) { main.removeCallbacks(saveLater); popup?.dismiss() }
        })
        return box to input
    }

    /** Side rail + content area. Returns the content container. */
    private fun shell(active: String): LinearLayout {
        stopPreview()
        window.statusBarColor = C.rail; window.navigationBarColor = C.rail
        refreshList = null
        resumePreview = null
        clockTick?.let { main.removeCallbacks(it) }; clockTick = null
        val root = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(C.rail); fitsSystemWindows = true } // keep clear of status/navigation bars (Android 15 edge-to-edge)
        val rail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(C.rail); setPadding(0, dp(18), 0, dp(14))
        }
        val mark = ImageView(this).apply { setImageResource(R.drawable.yep_logo); contentDescription = "YepPlayer"; scaleType = ImageView.ScaleType.FIT_CENTER }
        clickable(mark) { home() }
        rail.addView(mark, lp(dp(if (medium) 56 else 48), dp(if (medium) 56 else 48), b = 14))
        val railItems = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        var target = railItems
        fun item(res: Int, key: String, desc: String, go: () -> Unit) {
            val on = key == active
            val f = FrameLayout(this).apply {
                contentDescription = desc
                background = focusable(if (on) C.panel2 else Color.TRANSPARENT, 14, null)
                addView(icon(res, if (on) Color.WHITE else C.muted, 22), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            }
            clickable(f, go)
            target.addView(f, lp(dp(52), dp(48), b = 8))
        }
        item(R.drawable.ic_home, "home", "Home") { home() }
        item(R.drawable.ic_live, "live", "Live TV") { load("live") }
        item(R.drawable.ic_movie, "vod", "Movies") { load("vod") }
        item(R.drawable.ic_series, "series", "Series") { load("series") }
        item(R.drawable.ic_guide, "guide", "TV guide") { load("live", guide = true) }
        item(R.drawable.ic_star, "favs", "Favourites") { browse("favs", favourites(), "Favourites") }
        item(R.drawable.ic_sport, "sports", "Sports") { sports() }
        item(R.drawable.ic_record, "recordings", "Recordings") { recordings() }
        rail.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(railItems) }, lp(-2, 0, 1f))
        target = rail
        item(R.drawable.ic_settings, "settings", "Settings") { settings() }
        root.addView(rail, LinearLayout.LayoutParams(dp(if (medium) 76 else 64), -1))
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.bg) }
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
            t.addView(icon(res, if (primary) C.onAccent else C.highlight, if (medium) 40 else 30))
            t.addView(View(this), lp(1, 0, 1f))
            t.addView(text(title, if (medium) 28f else 19f, if (primary) C.onAccent else C.text, 800))
            t.addView(text(sub, 13f, if (primary) C.onAccent else C.muted).apply { setPadding(0, dp(6), 0, 0) })
            clickable(t, go)
            tiles.addView(t, lp(0, -1, weight, r = 8, l = 8))
        }
        val tv = homeTvTile()
        tiles.addView(tv, lp(0, -1, 1.35f, r = 8, l = 8))
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
            s.addView(icon(res, C.soft, 22))
            s.addView(text(label, 15f, C.text, 600).apply { setPadding(dp(12), 0, 0, 0) })
            clickable(s, go)
            shortcuts.add(s, dp(60))
        }
        shortcut(R.drawable.ic_guide, "TV Guide") { load("live", guide = true) }
        shortcut(R.drawable.ic_replay, "Catch-up") { load("live", catchupOnly = true) }
        shortcut(R.drawable.ic_star, "Favourites") { browse("favs", favourites(), "Favourites") }
        shortcut(R.drawable.ic_record, "Recordings") { recordings() }
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
        tv.post { tv.requestFocus() } // the remote starts on Live TV
        page.addView(text("Only use streams and playlists you are authorised to access.", 11f, C.faint, 400, 2).apply { gravity = Gravity.CENTER; setPadding(0, dp(24), 0, 0) }, lp(-1, -2))
    }

    // ---------------------------------------------------------------- home TV box

    /** The channel the Home TV box plays: the last one watched, otherwise BBC One. */
    private fun homeChannel(list: List<MediaItem>): MediaItem? {
        val last = prefs.getString("last_live_url", null)
        list.firstOrNull { it.url == last }?.let { return it }
        val hidden = hiddenGroups("live")
        val bbc = Regex("\\bbbc[\\s._-]*(one|1)\\b", RegexOption.IGNORE_CASE)
        // UK BBC One: prefer channels whose name or group says UK, then the plain national channel (not a regional/+1 version), then HD.
        val uk = Regex("(^|[^a-z])(uk|gb|united kingdom|great britain|england)([^a-z]|$)", RegexOption.IGNORE_CASE)
        val regional = Regex("scotland|wales|cymru|northern ireland|\\bni\\b|london|yorkshire|midlands|north|south|east|west|channel islands|\\+1", RegexOption.IGNORE_CASE)
        return list.filter { bbc.containsMatchIn(it.name) && it.category !in hidden }
            .sortedWith(compareBy(
                { !(uk.containsMatchIn(it.name) || uk.containsMatchIn(it.category)) },
                { regional.containsMatchIn(it.name) },
                { !it.name.contains("HD", true) },
                { it.name.length })).firstOrNull()
    }

    private fun homeTvTile(): FrameLayout {
        val tile = FrameLayout(this).apply { background = shape(Color.BLACK, 22); clipToOutline = true }
        val glow = ImageView(this).apply { setImageResource(R.drawable.home_glow); scaleType = ImageView.ScaleType.CENTER_CROP }
        tile.addView(glow, FrameLayout.LayoutParams(-1, -1))
        val pv = PlayerView(this).apply { useController = false; setShutterBackgroundColor(Color.TRANSPARENT); visibility = View.INVISIBLE; isFocusable = false }
        tile.addView(pv, FrameLayout.LayoutParams(-1, -1))
        tile.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.TRANSPARENT, Color.argb(190, 0, 0, 0)))
        }, FrameLayout.LayoutParams(-1, dp(120), Gravity.BOTTOM))
        val live = badge("LIVE", C.red).apply { visibility = View.GONE }
        tile.addView(live, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(16), dp(16), 0, 0) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text("Live TV", if (medium) 28f else 19f, Color.WHITE, 800))
        val sub = text(countLine("live", "channels", "Channels & groups"), 13f, Color.rgb(233, 230, 245), 600).apply { setPadding(0, dp(4), 0, 0) }
        col.addView(sub)
        tile.addView(col, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(if (medium) 22 else 14), 0, dp(14), dp(16)) })
        // Bright frame on top when the remote is on it.
        tile.foreground = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(Color.TRANSPARENT, 22, Color.WHITE, 3))
            addState(intArrayOf(), shape(Color.TRANSPARENT, 22))
        }
        var playing: MediaItem? = null
        var channels: List<MediaItem> = emptyList()
        clickable(tile) {
            val item = playing
            if (item != null) withPin(item) { playLive(item, channels) } else load("live")
        }
        fun idle() { pv.visibility = View.INVISIBLE; live.visibility = View.GONE; playing = null }
        fun start(list: List<MediaItem>) {
            channels = list
            val item = homeChannel(list) ?: run { idle(); return }
            if (prefs.getString("pin", null) != null && isRestricted(item)) { idle(); return }
            stopPreview()
            var triedTs = false
            val http = DefaultHttpDataSource.Factory().setUserAgent(Source.USER_AGENT).setAllowCrossProtocolRedirects(true)
            val pl = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(Source.guarded(DefaultDataSource.Factory(this, http)))).build()
            pl.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    if (!triedTs && item.url.endsWith(".m3u8")) { triedTs = true; pl.setMediaItem(ExoItem.fromUri(item.url.removeSuffix(".m3u8") + ".ts")); pl.prepare(); pl.playWhenReady = true }
                    else idle()
                }
                override fun onRenderedFirstFrame() { pv.visibility = View.VISIBLE; live.visibility = View.VISIBLE }
            })
            preview = pl
            pv.player = pl
            playing = item
            sub.text = item.name + (nowCache[item.id]?.let { " · ${it.title}" } ?: "")
            pl.setMediaItem(ExoItem.fromUri(item.url)); pl.prepare(); pl.playWhenReady = true
        }
        fun begin() {
            val p = profile ?: return
            cache["live"]?.let { start(it); return }
            pool.execute {
                val l = try { Source.load(p, "live") } catch (_: Exception) { null }
                runOnUiThread { if (l != null && tile.isAttachedToWindow) { cache["live"] = l; start(l) } }
            }
        }
        tile.post { begin() }
        resumePreview = { if (tile.isAttachedToWindow) { idle(); begin() } }
        return tile
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
        prog.addView(View(this).apply { setBackgroundColor(C.highlight) }, FrameLayout.LayoutParams((dp(210) * frac).toInt(), -1))
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
        val input = EditText(this).apply { hint = "Channel, movie or series"; setSingleLine(true); imeOptions = EditorInfo.IME_ACTION_SEARCH }
        val recent = recentSearches()
        val b = AlertDialog.Builder(this).setTitle("Search").setView(input)
            .setPositiveButton("Search") { _, _ -> input.text.toString().trim().let { rememberSearch(it); globalSearch(it) } }
            .setNegativeButton("Cancel", null)
        if (recent.isNotEmpty()) b.setItems(recent.toTypedArray()) { _, i -> rememberSearch(recent[i]); globalSearch(recent[i]) }
        val d = b.show()
        input.setOnEditorActionListener { v, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEARCH) { d.dismiss(); v.text.toString().trim().let { rememberSearch(it); globalSearch(it) }; true } else false
        }
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
        card1.addView(text("XTREAM CODES", 12f, C.soft, 800).apply { letterSpacing = 0.1f })
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
        card2.addView(text("M3U PLAYLIST", 12f, C.soft, 800).apply { letterSpacing = 0.1f })
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
            (if (kind == "episode") cats.keys.toList() else orderedGroups(kind, cats.keys)).map { Group(it, cats[it]!!.size) }
        // Live TV opens on Favourites (when there are some), with the remote on the first favourite channel.
        val startOnFavs = kind == "live" && !guide && specials.isNotEmpty() && favourites().any { it.kind == "live" && it.category !in hidden }
        var groupIndex = if (startOnFavs) groups.indexOfFirst { it.name == "Favourites" }.coerceAtLeast(0) else 0
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
                (v.getChildAt(0) as TextView).apply { text = g.name; setTextColor(if (on) C.onAccent else C.text2) }
                (v.getChildAt(1) as TextView).apply { text = "%,d".format(g.count); setTextColor(if (on) C.onAccent else C.faint) }
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
            val gl = ListView(this).apply {
                id = View.generateViewId(); divider = null; dividerHeight = dp(4); adapter = groupAdapter
                selector = tvSelector(12); setDrawSelectorOnTop(true); isVerticalScrollBarEnabled = false
            }
            gp.addView(gl, lp(-1, 0, 1f))
            row.addView(gp, LinearLayout.LayoutParams(dp(if (wide) 250 else 210), -1))
            row.addView(View(this).apply { setBackgroundColor(C.line) }, LinearLayout.LayoutParams(dp(1), -1))
            groupList = gl
        } else {
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(iconButton(R.drawable.ic_back, "Back", 44, C.panel2) { onBack() })
            head.addView(text(title, 20f, C.text, 800).apply { setPadding(dp(10), 0, 0, 0) }, lp(0, -2, 1f))
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
        val (box, searchInput) = searchBox(if (medium) "Search in ${title.lowercase()}" else "Search", { q -> query = q; render() })
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
                selector = tvSelector(14); setDrawSelectorOnTop(true)
            }
        } else {
            adapter = RowAdapter(kind, mixed) { visible to selected }
            collection = ListView(this).apply {
                divider = null; dividerHeight = dp(4); this.adapter = adapter; isFastScrollEnabled = true
                selector = tvSelector(12); setDrawSelectorOnTop(true); clipToPadding = false; setPadding(0, 0, 0, dp(16))
            }
        }
        mainPane.addView(collection, lp(-1, 0, 1f))

        // TV remote: arrows move between the groups column, the search bar and the list (never into the preview pane).
        collection.id = View.generateViewId()
        if (searchInput.id == View.NO_ID) searchInput.id = View.generateViewId()
        collection.nextFocusUpId = searchInput.id
        collection.nextFocusRightId = collection.id
        searchInput.nextFocusDownId = collection.id
        groupList?.let { gl ->
            gl.nextFocusRightId = collection.id
            collection.nextFocusLeftId = gl.id
            searchInput.nextFocusLeftId = gl.id
        }

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
            if (item.kind == "live") {
                // Long press (or holding OK on the remote) adds/removes a favourite; the Menu key has the other options.
                toggleFavourite(item)
                toast(if (isFavourite(item)) "★ Added to favourites" else "Removed from favourites")
                adapter.notifyDataSetChanged()
                if (groups.getOrNull(groupIndex)?.name == "Favourites") render()
            } else itemMenu(item, visible)
            true
        }
        collection.setOnKeyListener { _, code, ev ->
            if (code == KeyEvent.KEYCODE_MENU && ev.action == KeyEvent.ACTION_UP) {
                visible.getOrNull(collection.selectedItemPosition)?.let { itemMenu(it, visible) }; true
            } else false
        }
        // TV remotes: moving focus over a row updates the preview / details.
        collection.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (sidePane) visible.getOrNull(position)?.let { select(it); adapter.notifyDataSetChanged() }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        if (previewPane != null) resumePreview = {
            val url = prefs.getString("last_live_url", null)
            val item = visible.firstOrNull { it.url == url } ?: selected
            if (item != null) {
                selected = item
                previewPane.show(item, visible, force = true)
                adapter.notifyDataSetChanged()
                val pos = visible.indexOfFirst { sameItem(it, item) }
                if (pos >= 0) collection.setSelection(pos)
            }
        }
        if (startOnFavs) {
            groupSpinner?.setSelection(groupIndex)
            collection.post { collection.requestFocus(); collection.setSelection(0) }
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
        val bar = View(this@MainActivity).apply { background = shape(C.highlight, 2) }
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
        val bar = View(this@MainActivity).apply { setBackgroundColor(C.highlight) }
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
        private val hint = text("Video preview · press OK again for full screen", 13f, C.faint).apply { gravity = Gravity.CENTER }
        private val chName = text("", 15f, C.text, 700)
        private val bigName = text("", 22f, C.text, 800, 2)
        private val nowLabel = text("", 11f, C.highlight, 800).apply { letterSpacing = 0.08f }
        private val nowTitle = text("", 20f, C.text, 700, 2)
        private val nowDesc = text("", 13f, C.muted, 400, 2).apply { setLineSpacing(0f, 1.25f) }
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
            video.isFocusable = false // the remote stays in the channel list; OK on the chosen channel opens full screen
            view.addView(video, lp(-1, dp(260)))
            view.addView(bigName, lp(-1, -2, t = 16))
            view.addView(nowLabel, lp(-1, -2, t = 8)); view.addView(nowTitle, lp(-1, -2, t = 6)); view.addView(nowDesc, lp(-1, -2, t = 6))
            view.addView(View(this@MainActivity).apply { setBackgroundColor(C.line) }, lp(-1, dp(1), t = 14))
            view.addView(text("SCHEDULE", 11f, C.soft, 800).apply { letterSpacing = 0.1f }, lp(-1, -2, t = 10))
            view.addView(ScrollView(this@MainActivity).apply { isFocusable = false; addView(upcoming) }, lp(-1, 0, 1f, t = 4))
        }
        fun show(item: MediaItem, siblings: List<MediaItem>?, restartVideo: Boolean = true, force: Boolean = false) {
            val changed = current?.let { sameItem(it, item) } != true
            current = item
            chName.text = item.name
            bigName.text = item.name
            if (!changed && !force) return
            nowLabel.text = ""; nowTitle.text = ""; nowDesc.text = item.category; upcoming.removeAllViews()
            loadGuide(item)
            if (restartVideo) {
                pending?.let { main.removeCallbacks(it) }
                preview?.stop()
                val restricted = prefs.getString("pin", null) != null && isRestricted(item)
                hint.text = if (restricted) "Protected by parental PIN" else "Video preview · press OK again for full screen"
                if (!restricted) pending = Runnable { startPreview(item) }.also { main.postDelayed(it, if (force) 900L else 650L) }
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
            return ExoPlayer.Builder(this@MainActivity).setMediaSourceFactory(DefaultMediaSourceFactory(Source.guarded(DefaultDataSource.Factory(this@MainActivity, http)))).build().apply {
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
                    } else { nowLabel.text = ""; nowTitle.text = ""; nowDesc.text = "No guide data for this channel" }
                    upcoming.removeAllViews()
                    list.filter { it.start >= (cur?.stop ?: now) }.take(8).forEach { e ->
                        val r = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                        r.addView(text(timeFmt.format(Date(e.start * 1000)), 14f, C.muted, 600), lp(dp(56), -2))
                        r.addView(text(e.title, 14f, C.text2), lp(0, -2, 1f))
                        r.setOnClickListener { epgDetails(item, e, null) } // touch only; remote stays in the list
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
                val bytes = Source.fetch(url, 5000, 8000)
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

    private fun groupOrderKey(kind: String) = "group_order|$kind|${profile?.base}"
    private fun groupOrder(kind: String): List<String> = try {
        JSONArray(prefs.getString(groupOrderKey(kind), "[]")).let { a -> (0 until a.length()).map { a.getString(it) } }
    } catch (_: Exception) { emptyList() }

    /** Groups in the user's chosen order; groups never arranged follow A–Z. */
    private fun orderedGroups(kind: String, names: Collection<String>): List<String> {
        val order = groupOrder(kind)
        if (order.isEmpty()) return names.sortedBy { it.lowercase() }
        val rank = order.withIndex().associate { it.value to it.index }
        return names.sortedWith(compareBy({ rank[it] ?: Int.MAX_VALUE }, { it.lowercase() }))
    }

    private fun chooseArrange() {
        val p = profile ?: run { configure(); return }
        val kinds = if (p.type == "xtream") listOf("live", "vod", "series") else listOf("live")
        val labels = kinds.map { when (it) { "live" -> "Live TV groups"; "vod" -> "Movie groups"; else -> "Series groups" } } + "Favourite channels"
        AlertDialog.Builder(this).setTitle("Arrange order").setItems(labels.toTypedArray()) { _, which ->
            if (which == kinds.size) { val f = favourites(); arrange("Favourite channels", f.map { it.name }) { order -> storeList("fav_items", order.mapNotNull { i -> f.getOrNull(i) }) }; return@setItems }
            val k = kinds[which]
            fun open(list: List<MediaItem>) {
                val hidden = hiddenGroups(k)
                val names = orderedGroups(k, list.map { it.category }.distinct().filter { it !in hidden })
                arrange(labels[which], names) { order -> prefs.edit().putString(groupOrderKey(k), JSONArray(order.map { names[it] }).toString()).apply() }
            }
            cache[k]?.let { open(it); return@setItems }
            toast("Loading…")
            pool.execute {
                val l = try { Source.load(p, k) } catch (_: Exception) { null }
                runOnUiThread { if (l == null) toast("Couldn't load the list") else { cache[k] = l; open(l) } }
            }
        }.setNegativeButton("Cancel", null).show()
    }

    /** Reorder screen: OK picks an item up, Up/Down moves it, OK drops it. Touch: tap to pick, tap another row to move it there. [save] gets the new order as original indexes. */
    private fun arrange(title: String, names: List<String>, save: (List<Int>) -> Unit) {
        if (names.isEmpty()) { toast("Nothing to arrange yet"); return }
        val content = shell("settings")
        back = { home() }
        val order = names.indices.toMutableList()
        var held = -1
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(if (medium) 40 else 16), dp(24), dp(if (medium) 40 else 16), dp(16)) }
        content.addView(page, lp(-1, -1))
        page.addView(text(title, 26f, C.text, 800))
        page.addView(text("Press OK to pick up an item, move it with Up / Down, then press OK again to drop it. The order is saved straight away.", 14f, C.muted, 400, 3).apply { setPadding(0, dp(6), 0, dp(14)) })
        lateinit var list: ListView
        val adapter = object : BaseAdapter() {
            override fun getCount() = order.size
            override fun getItem(position: Int) = names[order[position]]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val v = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), 0, dp(14), 0)
                    addView(text("", 13f, C.faint, 700).apply { gravity = Gravity.END }, lp(dp(28), -2, r = 14))
                    addView(text("", 15f, C.text, 600), lp(0, -2, 1f))
                    addView(text("", 11f, C.soft, 800).apply { letterSpacing = 0.06f })
                    layoutParams = AbsListView.LayoutParams(-1, dp(50))
                }
                val on = position == held
                (v.getChildAt(0) as TextView).text = (position + 1).toString()
                (v.getChildAt(1) as TextView).text = names[order[position]]
                (v.getChildAt(2) as TextView).text = if (on) "▲▼ MOVING" else ""
                v.background = shape(if (on) C.accentTint else C.panel, 12, if (on) C.accent else C.border, if (on) 2 else 1)
                return v
            }
        }
        list = ListView(this).apply {
            divider = null; dividerHeight = dp(6); this.adapter = adapter
            selector = tvSelector(12); setDrawSelectorOnTop(true); clipToPadding = false; setPadding(0, 0, 0, dp(16))
        }
        page.addView(list, lp(if (medium) dp(620) else -1, 0, 1f))
        fun moveTo(to: Int) {
            if (held < 0 || to < 0 || to >= order.size || to == held) return
            order.add(to, order.removeAt(held)); held = to
            adapter.notifyDataSetChanged(); list.setSelection(to)
            save(order.toList())
        }
        list.setOnItemClickListener { _, _, position, _ ->
            when {
                held < 0 -> held = position
                held == position -> held = -1
                else -> { moveTo(position); held = -1 }
            }
            adapter.notifyDataSetChanged()
        }
        list.setOnKeyListener { _, code, ev ->
            if (held < 0 || ev.action != KeyEvent.ACTION_DOWN) false
            else when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> { moveTo(held - 1); true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { moveTo(held + 1); true }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> true
                else -> false
            }
        }
        list.post { list.requestFocus(); list.setSelection(0) }
    }

    private fun chooseGroupsToManage() {
        val p = profile ?: run { configure(); return }
        val kinds = if (p.type == "xtream") listOf("live", "vod", "series") else listOf("live")
        val labels = kinds.map { when (it) { "live" -> "Live TV"; "vod" -> "Movies"; else -> "Series" } }
        AlertDialog.Builder(this).setTitle("Hide / show groups").setItems(labels.toTypedArray()) { _, which ->
            val k = kinds[which]
            cache[k]?.let { manageGroups(it, k); return@setItems }
            toast("Loading ${labels[which].lowercase()}…")
            pool.execute {
                val l = try { Source.load(p, k) } catch (_: Exception) { null }
                runOnUiThread { if (l == null) toast("Couldn't load the list") else { cache[k] = l; manageGroups(l, k) } }
            }
        }.setNegativeButton("Cancel", null).show()
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
        if (item.kind == "live") { actions.add(0, "Watch"); actions.add("Record"); if (profile?.type == "xtream") actions.add("Programme guide"); if (item.catchup && profile?.type == "xtream") actions.add("Catch-up / replay") }
        else { actions.add(0, if (item.kind == "series") "Episodes" else "Play"); if (item.kind != "series") actions.add("Download") }
        AlertDialog.Builder(this).setTitle(item.name).setItems(actions.toTypedArray()) { _, which ->
            when (actions[which]) {
                "Watch", "Play", "Episodes" -> openItem(item, siblings) { home() }
                "Programme guide" -> withPin(item) { showEpg(item, siblings) }
                "Catch-up / replay" -> withPin(item) { catchup(item) }
                "Record" -> recordLive(item)
                "Download" -> { RecordingService.start(this, item.url, item.name, 0); toast("Download started · see Recordings") }
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
        val options = arrayOf("Playlists", "Account information", "Hide / show groups", "Arrange order", "Parental PIN", "Recently watched", "Refresh playlist data", "Network safety", "Colours", "About YepPlayer")
        AlertDialog.Builder(this).setTitle("Settings").setItems(options) { _, which ->
            when (which) {
                0 -> configure()
                1 -> showAccount()
                2 -> chooseGroupsToManage()
                3 -> chooseArrange()
                4 -> changePin()
                5 -> browse("recent", recent(), "Recently watched")
                7 -> networkSafety()
                8 -> colours()
                6 -> { cache.clear(); nowCache.clear(); detailsCache.clear(); logoCache.evictAll(); synchronized(failedLogos) { failedLogos.clear() }; prefs.edit().remove("acct_time|${profile?.base}").apply(); toast("Playlist data will reload") }
                else -> AlertDialog.Builder(this).setTitle("YepPlayer ${packageManager.getPackageInfo(packageName, 0).versionName}")
                    .setMessage("A personal IPTV player for authorised playlists.\n\nTips: long-press an item for more options. In the player, use the channel buttons (or the remote's up/down keys) to zap between channels.")
                    .setPositiveButton("OK", null).show()
            }
        }.show()
    }

    private fun recordLive(item: MediaItem) {
        val url = if (item.url.endsWith(".m3u8") && item.url.contains("/live/")) item.url.removeSuffix(".m3u8") + ".ts" else item.url
        if (url.substringBefore('?').endsWith(".m3u8")) { toast("This channel's stream type can't be recorded"); return }
        val mins = intArrayOf(30, 60, 120, 180)
        AlertDialog.Builder(this).setTitle("Record ${item.name}").setItems(arrayOf("30 minutes", "1 hour", "2 hours", "3 hours")) { _, which ->
            stopPreview()
            RecordingService.start(this, url, item.name, mins[which]); toast("Recording started · see Recordings")
        }.setNegativeButton("Cancel", null).show()
    }

    /** Recorded channels and downloaded films, stored in the app's own folder. */
    private fun recordings() {
        val content = shell("recordings")
        back = { home() }
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(if (medium) 28 else 14), dp(20), dp(if (medium) 28 else 14), 0) }
        content.addView(page, lp(-1, -1))
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(iconButton(R.drawable.ic_back, "Back", 44, C.panel2) { home() })
        head.addView(text("Recordings", 24f, C.text, 800).apply { setPadding(dp(12), 0, 0, 0) }, lp(0, -2, 1f))
        page.addView(head)
        page.addView(text("Record a channel from the player (the ● button) or by long-pressing a channel. Films and episodes can be downloaded the same way. Recording uses one extra connection of your account.", 13f, C.muted, 400, 3).apply { setPadding(0, dp(12), 0, dp(12)) })
        val dir = RecordingService.dir(this)
        var files = listOf<java.io.File>()
        val sizeFmt = { b: Long -> if (b > 1024L * 1024 * 1024) "%.1f GB".format(b / 1073741824.0) else "${b / 1048576} MB" }
        val adapter = object : BaseAdapter() {
            override fun getCount() = files.size
            override fun getItem(position: Int) = files[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val f = files[position]
                val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10)); background = shape(C.panel, 14, C.border) }
                val active = RecordingService.isRecording(f)
                row.addView(icon(if (active) R.drawable.ic_record else R.drawable.ic_play, if (active) C.red else C.text, 22))
                val col = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0) }
                col.addView(text(f.nameWithoutExtension, 15f, C.text, 600))
                col.addView(text((if (active) "● Recording · " else "") + sizeFmt(f.length()) + " · " + dayFmt.format(Date(f.lastModified())) + " " + timeFmt.format(Date(f.lastModified())), 12f, if (active) C.highlight else C.muted).apply { setPadding(0, dp(4), 0, 0) })
                row.addView(col, lp(0, -2, 1f))
                return row
            }
        }
        val list = ListView(this).apply { divider = null; dividerHeight = dp(8); this.adapter = adapter; selector = shape(C.accentTint, 14) }
        val empty = text("No recordings yet", 15f, C.muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(40), 0, 0) }
        page.addView(empty, lp(-1, -2))
        page.addView(list, lp(-1, 0, 1f))
        fun reload() {
            files = (dir.listFiles()?.filter { it.isFile } ?: emptyList()).sortedByDescending { it.lastModified() }
            empty.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
            adapter.notifyDataSetChanged()
        }
        reload()
        val ticker = object : Runnable { override fun run() { if (list.isAttachedToWindow) { reload(); main.postDelayed(this, 3000) } } }
        main.postDelayed(ticker, 3000)
        list.setOnItemClickListener { _, _, position, _ ->
            val f = files.getOrNull(position) ?: return@setOnItemClickListener
            startPlayer(android.net.Uri.fromFile(f).toString(), f.nameWithoutExtension, live = false)
        }
        list.setOnItemLongClickListener { _, _, position, _ ->
            val f = files.getOrNull(position) ?: return@setOnItemLongClickListener true
            val job = RecordingService.jobs.values.firstOrNull { it.file == f }
            val b = AlertDialog.Builder(this).setTitle(f.nameWithoutExtension)
            if (job != null) b.setPositiveButton("Stop recording") { _, _ -> RecordingService.stop(this, job.id); toast("Stopping…") }
            else b.setPositiveButton("Delete") { _, _ -> f.delete(); reload() }
            b.setNegativeButton("Cancel", null).show(); true
        }
    }

    // ---------------------------------------------------------------- colours

    private fun colours() {
        val content = shell("settings")
        back = { home() }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        content.addView(row, lp(-1, -1))
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(if (medium) 32 else 16), dp(24), dp(if (medium) 24 else 16), dp(24)) }
        scroll.addView(col)
        row.addView(scroll, lp(if (medium) dp(520) else -1, -1))
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(iconButton(R.drawable.ic_back, "Back", 44, C.panel2) { home() })
        head.addView(text("Colours", 26f, C.text, 800).apply { setPadding(dp(12), 0, 0, 0) })
        col.addView(head)
        col.addView(text("Choose how YepPlayer looks. Changes apply straight away.", 14f, C.muted, 400, 2).apply { setPadding(0, dp(8), 0, 0) })
        fun section(label: String) = col.addView(text(label, 12f, C.soft, 800).apply { letterSpacing = 0.1f; setPadding(0, dp(22), 0, dp(10)) })
        fun apply(theme: Int = C.themeIndex, accent: Int = C.accentIndex, bg: Int = C.backdropIndex) { C.save(prefs, theme, accent, bg); colours() }
        fun dot(color: Int, size: Int) = View(this).apply { background = shape(color, size / 2, C.border); layoutParams = LinearLayout.LayoutParams(dp(size), dp(size)).apply { rightMargin = dp(6) } }

        section("THEME")
        val grid = GridLayoutCompat(if (medium) 3 else 2)
        C.presets.forEachIndexed { i, t ->
            val on = C.themeIndex == i && C.accentIndex < 0
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(12), dp(12))
                background = focusable(C.panel, 16, C.border)
            }
            if (on) tile.background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), shape(C.panel2, 16, C.text, 2))
                addState(intArrayOf(), shape(C.panel, 16, t.accent, 2))
            }
            val sw = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            sw.addView(dot(t.accent, 22)); sw.addView(dot(C.panel2, 22)); sw.addView(dot(t.highlight, 22))
            tile.addView(sw); tile.addView(View(this), lp(1, 0, 1f))
            tile.addView(text(t.name, 14f, C.text, 700))
            clickable(tile) { apply(theme = i, accent = -1) }
            grid.add(tile, dp(88))
        }
        col.addView(grid.view)

        section("OR PICK YOUR OWN MAIN COLOUR")
        val accRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        C.accents.forEachIndexed { i, a ->
            val on = C.accentIndex == i
            val b = FrameLayout(this).apply {
                contentDescription = a.name
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), shape(a.color, 24, C.text, 3))
                    addState(intArrayOf(), shape(a.color, 24, if (on) C.text else null, 3))
                }
            }
            clickable(b) { apply(accent = i) }
            accRow.addView(b, lp(dp(48), dp(48), r = 12))
        }
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(accRow) })

        section("BACKGROUND")
        val bgRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        C.backdrops.forEachIndexed { i, b ->
            val on = C.backdropIndex == i
            val pill = LinearLayout(this).apply {
                gravity = Gravity.CENTER; setPadding(dp(18), 0, dp(18), 0)
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), shape(b.panel, 22, C.text, 2))
                    addState(intArrayOf(), shape(b.panel, 22, if (on) C.accent else b.border, if (on) 2 else 1))
                }
                addView(text(b.name, 14f, C.text, 600))
            }
            clickable(pill) { apply(bg = i) }
            bgRow.addView(pill, lp(-2, dp(46), r = 12))
        }
        col.addView(bgRow)
        col.addView(pill("Reset to YepPlayer colours") { apply(0, -1, 0) }, lp(-2, dp(44), t = 26))

        if (medium) {
            val pv = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(24), dp(28), dp(24)) }
            pv.addView(text("PREVIEW", 12f, C.soft, 800).apply { letterSpacing = 0.1f })
            val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = shape(C.bg, 20, C.border); setPadding(dp(20), dp(20), dp(20), dp(20)) }
            card.addView(text(SpannableStringBuilder("YepPlayer").apply { setSpan(ForegroundColorSpan(C.accent), 0, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }, 24f, C.text, 800))
            val tiles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            fun tile(title: String, sub: String, primary: Boolean) = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM; setPadding(dp(16), dp(14), dp(14), dp(14))
                background = shape(if (primary) C.accent else C.panel, 18, if (primary) null else C.border)
                addView(text(title, 22f, if (primary) C.onAccent else C.text, 800))
                addView(text(sub, 13f, if (primary) C.onAccent else C.muted, 600))
            }
            tiles.addView(tile("Live TV", "Channels & groups", true), lp(0, dp(130), 1.3f, r = 6))
            tiles.addView(tile("Movies", "Your film library", false), lp(0, dp(130), 1f, l = 6))
            card.addView(tiles, lp(-1, -2, t = 14))
            val ch = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = shape(C.panel2, 12, C.accent, 2); setPadding(dp(12), 0, dp(12), 0) }
            ch.addView(View(this).apply { background = shape(C.panel, 9) }, lp(dp(38), dp(38)))
            val chCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(12), 0) }
            chCol.addView(text("Sample Sport 1", 14f, C.text, 600))
            val track = FrameLayout(this).apply { background = shape(C.border, 2) }
            track.addView(View(this).apply { background = shape(C.highlight, 2) }, FrameLayout.LayoutParams(dp(120), -1))
            chCol.addView(track, lp(-1, dp(3), t = 6))
            ch.addView(chCol, lp(0, -2, 1f))
            ch.addView(text("NOW", 11f, C.highlight, 800))
            card.addView(ch, lp(-1, dp(58), t = 14))
            val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            btns.addView(pill("Watch", true, R.drawable.ic_play) {}); btns.addView(pill("Catch-up") {}, lp(-2, dp(44), l = 10))
            card.addView(btns, lp(-2, -2, t = 14))
            pv.addView(card, lp(-1, -2, t = 12))
            row.addView(pv, lp(0, -1, 1f))
        }
    }

    // ---------------------------------------------------------------- sports

    private fun mySports(): Set<String> = prefs.getStringSet("my_sports", emptySet()) ?: emptySet()

    private fun sports(selectedSport: String = "All", force: Boolean = false) {
        val p = profile ?: run { configure(); return }
        if (p.type != "xtream") { toast("Sports needs an Xtream account with a TV guide"); return }
        val content = shell("sports")
        back = { home() }
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(if (medium) 28 else 14), dp(20), dp(if (medium) 28 else 14), 0) }
        content.addView(page, lp(-1, -1))
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(iconButton(R.drawable.ic_back, "Back", 44, C.panel2) { home() })
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        titles.addView(text("Sports", 26f, C.text, 800))
        if (medium) titles.addView(text("Live and upcoming sport on your channels, from the TV guide", 13f, C.muted).apply { setPadding(0, dp(4), 0, 0) })
        head.addView(titles, lp(0, -2, 1f))
        head.addView(pill("My sports") {
            val all = Sports.names.toTypedArray(); val mine = mySports().toMutableSet()
            AlertDialog.Builder(this).setTitle("My sports").setMultiChoiceItems(all, BooleanArray(all.size) { all[it] in mine }) { _, w, on -> if (on) mine.add(all[w]) else mine.remove(all[w]) }
                .setPositiveButton("Save") { _, _ -> prefs.edit().putStringSet("my_sports", mine).apply(); sports(selectedSport) }
                .setNegativeButton("Cancel", null).show()
        })
        head.addView(iconButton(R.drawable.ic_replay, "Refresh") { sports(selectedSport, force = true) }, lp(dp(44), dp(44), l = 10))
        page.addView(head)

        val mine = mySports()
        val order = listOf("All") + (if (mine.isNotEmpty()) listOf("My sports") else emptyList()) + Sports.names.sortedBy { if (it in mine) 0 else 1 }
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        order.forEach { s ->
            val on = s == selectedSport
            val chip = LinearLayout(this).apply {
                gravity = Gravity.CENTER; setPadding(dp(16), 0, dp(16), 0)
                background = if (on) focusable(C.accent, 21, null) else focusable(C.panel, 21, C.border)
                addView(text(s, 14f, if (on) C.onAccent else C.text, 700))
            }
            clickable(chip) { sports(s) }
            chips.addView(chip, lp(-2, dp(42), r = 10))
        }
        page.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) }, lp(-1, -2, t = 16, b = 12))

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        body.addView(ProgressBar(this).apply { isIndeterminate = true; indeterminateTintList = ColorStateList.valueOf(C.accent) }, lp(dp(44), dp(44), t = 40))
        body.addView(text("Searching the TV guide for sport…", 14f, C.muted).apply { setPadding(0, dp(12), 0, 0) })
        page.addView(body, lp(-1, 0, 1f))

        pool.execute {
            try {
                val channels = cache["live"] ?: Source.load(p, "live").also { l -> runOnUiThread { cache["live"] = l } }
                val events = Sports.load(p, channels, force)
                runOnUiThread { if (body.isAttachedToWindow) showSports(body, events, selectedSport, mine) }
            } catch (e: Exception) {
                runOnUiThread {
                    body.removeAllViews()
                    body.addView(text(e.message ?: "TV guide unavailable", 14f, C.muted, 400, 3).apply { gravity = Gravity.CENTER; setPadding(0, dp(40), 0, dp(14)) })
                    body.addView(pill("Try again", true) { sports(selectedSport, true) })
                }
            }
        }
    }

    private fun showSports(body: LinearLayout, all: List<SportEvent>, selected: String, mine: Set<String>) {
        body.removeAllViews(); body.gravity = Gravity.NO_GRAVITY
        val now = System.currentTimeMillis() / 1000
        val events = all.filter { it.stop > now && when (selected) { "All" -> true; "My sports" -> it.sport in mine; else -> it.sport == selected } }
        val live = events.filter { it.isLive(now) }.take(80)
        val next = events.filter { it.start > now }.take(80)
        val liveChannels = live.map { it.channel }.distinctBy { it.id }
        fun liveCard(e: SportEvent): View {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10)); background = focusable(C.panel, 14, C.border) }
            val logo = FrameLayout(this).apply { background = shape(C.panel2, 10) }
            logo.addView(text(initialsOf(e.channel.name), 11f, C.muted, 800).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1))
            val img = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(dp(4), dp(4), dp(4), dp(4)); tag = e.channel.logoUrl }
            logo.addView(img, FrameLayout.LayoutParams(-1, -1)); if (e.channel.logoUrl.isNotBlank()) loadLogo(e.channel.logoUrl, img)
            r.addView(logo, lp(dp(46), dp(46)))
            val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(10), 0) }
            val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            top.addView(text(e.sport.uppercase(), 11f, C.accent, 800).apply { letterSpacing = 0.06f })
            top.addView(text(e.channel.name, 12f, C.muted).apply { setPadding(dp(8), 0, 0, 0) })
            c.addView(top); c.addView(text(e.title, 15f, C.text, 700), lp(-1, -2, t = 4))
            val track = FrameLayout(this).apply { background = shape(C.border, 2) }
            val frac = ((now - e.start).toFloat() / (e.stop - e.start)).coerceIn(0f, 1f)
            val bar = View(this).apply { background = shape(C.highlight, 2) }
            track.addView(bar, FrameLayout.LayoutParams(0, -1)); track.post { bar.layoutParams = FrameLayout.LayoutParams((track.width * frac).toInt(), -1) }
            c.addView(track, lp(-1, dp(3), t = 6))
            r.addView(c, lp(0, -2, 1f))
            r.addView(pill("Watch", true) { withPin(e.channel) { playLive(e.channel, liveChannels) } })
            clickable(r) { withPin(e.channel) { playLive(e.channel, liveChannels) } }
            return r
        }
        fun nextRow(e: SportEvent): View {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10)); background = focusable(Color.TRANSPARENT, 12, null) }
            val day = if (dayFmt.format(Date(e.start * 1000)) == dayFmt.format(Date())) "" else dayFmt.format(Date(e.start * 1000)).substringBefore(' ') + " "
            r.addView(text(day + timeFmt.format(Date(e.start * 1000)), 15f, C.text, 700), lp(dp(if (day.isEmpty()) 56 else 90), -2))
            val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, 0, 0) }
            c.addView(text(e.title, 14f, C.text, 600)); c.addView(text("${e.sport} · ${e.channel.name}", 12f, C.muted).apply { setPadding(0, dp(3), 0, 0) })
            r.addView(c, lp(0, -2, 1f))
            clickable(r) {
                AlertDialog.Builder(this).setTitle(e.title)
                    .setMessage("${e.sport} · ${e.channel.name}\n${dayFmt.format(Date(e.start * 1000))}  ${timeFmt.format(Date(e.start * 1000))}–${timeFmt.format(Date(e.stop * 1000))}")
                    .setPositiveButton("Watch channel") { _, _ -> withPin(e.channel) { playLive(e.channel, listOf(e.channel)) } }
                    .setNegativeButton("Close", null).show()
            }
            return r
        }
        fun header(label: String, liveBadge: Boolean, count: String) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(10))
            if (liveBadge) addView(badge(label, C.red)) else addView(text(label, 13f, C.soft, 800).apply { letterSpacing = 0.08f })
            addView(text(count, 13f, C.muted).apply { setPadding(dp(10), 0, 0, 0) })
        }
        val liveCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        liveCol.addView(header("LIVE NOW", true, if (live.isEmpty()) "Nothing live right now" else "${live.size} events"))
        live.forEach { liveCol.addView(liveCard(it), lp(-1, -2, b = 8)) }
        val nextCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        nextCol.addView(header("COMING UP", false, if (next.isEmpty()) "Nothing found in the guide" else ""))
        next.forEach { nextCol.addView(nextRow(it)); nextCol.addView(View(this).apply { setBackgroundColor(C.line) }, lp(-1, 1)) }
        if (all.isEmpty()) liveCol.addView(text("No sport found. Your provider's TV guide may not list these channels.", 13f, C.muted, 400, 3))
        if (medium) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(ScrollView(this).apply { addView(liveCol) }, lp(0, -1, 1.15f, r = 12))
            row.addView(ScrollView(this).apply { addView(nextCol) }, lp(0, -1, 1f, l = 12))
            body.addView(row, lp(-1, -1))
        } else {
            val one = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(liveCol); addView(nextCol, lp(-1, -2, t = 16)) }
            body.addView(ScrollView(this).apply { addView(one) }, lp(-1, -1))
        }
    }

    private fun networkSafety() {
        val on = prefs.getBoolean("allow_local", false)
        AlertDialog.Builder(this).setTitle("Network safety")
            .setMessage("YepPlayer only connects out to your IPTV provider. It opens no ports and accepts no connections from other devices.\n\n" +
                "For extra safety, playlists, logos and streams that point to devices on your home network (router, computers, cameras) are blocked.\n\n" +
                "Home network servers are currently " + (if (on) "ALLOWED." else "BLOCKED (recommended).") +
                "\n\nOnly allow them if your IPTV server really runs at home.")
            .setPositiveButton(if (on) "Block again" else "Allow home servers") { _, _ ->
                prefs.edit().putBoolean("allow_local", !on).apply(); NetGuard.load(prefs)
                toast(if (on) "Home network blocked" else "Home network servers allowed")
            }
            .setNegativeButton("Close", null).show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) { settings(); return true }
        return super.onKeyDown(keyCode, event)
    }

    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()
    override fun onDestroy() { stopPreview(); clockTick?.let { main.removeCallbacks(it) }; pool.shutdownNow(); logoPool.shutdownNow(); epgPool.shutdownNow(); super.onDestroy() }
}
