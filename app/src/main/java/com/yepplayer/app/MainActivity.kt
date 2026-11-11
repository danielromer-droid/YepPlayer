package com.yepplayer.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val bg = Color.rgb(7, 12, 22)
    private val panel = Color.rgb(19, 29, 46)
    private val panel2 = Color.rgb(27, 42, 64)
    private val blue = Color.rgb(27, 143, 255)
    private val white = Color.WHITE
    private val muted = Color.rgb(164, 180, 202)
    private val pool = Executors.newSingleThreadExecutor()
    private val logoPool = Executors.newFixedThreadPool(4)
    private val logoCache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val failedLogos = HashSet<String>()
    private lateinit var root: LinearLayout
    private var profile: Profile? = null
    private var items = listOf<MediaItem>()
    private val cache = mutableMapOf<String, List<MediaItem>>()
    private var currentKind = "live"
    private var guideMode = false
    private var back: (() -> Unit)? = null
    private val prefs by lazy { getSharedPreferences("yepplayer", MODE_PRIVATE) }
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dayFmt = SimpleDateFormat("EEE d MMM", Locale.getDefault())

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = Color.rgb(3, 7, 14)
        window.decorView.systemUiVisibility = 0
        profile = readProfile()
        home()
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        val b = back
        if (b != null) b() else super.onBackPressed()
    }

    private fun rounded(color: Int, radius: Float = 18f): GradientDrawable = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius.toInt()).toFloat() / 2f; setStroke(1, Color.rgb(42, 59, 82))
    }

    private fun label(value: String, size: Float = 15f, color: Int = white, bold: Boolean = false): TextView = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); setPadding(dp(10), dp(6), dp(10), dp(6))
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun action(value: String, onClick: () -> Unit, accent: Boolean = false): Button = Button(this).apply {
        text = value; textSize = 14f; isAllCaps = false; setTextColor(white)
        background = rounded(if (accent) Color.rgb(13, 104, 205) else panel2, 14f)
        minWidth = dp(44); minimumWidth = dp(44); minHeight = dp(44); minimumHeight = dp(44)
        setPadding(dp(12), dp(6), dp(12), dp(6)); setOnClickListener { onClick() }
    }

    private fun gap(h: Int = 8) = View(this).apply { layoutParams = LinearLayout.LayoutParams(-1, dp(h)) }

    private fun screen(title: String, onBack: (() -> Unit)? = { home() }) {
        back = onBack
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg); setPadding(dp(12), dp(10), dp(12), dp(6)) }
        setContentView(root)
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (onBack != null) bar.addView(action("‹", { onBack() }), LinearLayout.LayoutParams(dp(44), dp(44)))
        bar.addView(label("Yep", 22f, blue, true).apply { setPadding(dp(10), 0, 0, 0) })
        bar.addView(label("Player", 22f, white, true).apply { setPadding(0, 0, 0, 0) })
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

    // ---------- Profile ----------

    private fun readProfile(): Profile? = try {
        val j = JSONObject(prefs.getString("profile", "")!!)
        Profile(j.getString("type"), j.getString("base"), j.optString("username"), j.optString("password"))
    } catch (_: Exception) { null }

    private fun saveProfile(p: Profile) {
        prefs.edit().putString("profile", JSONObject().put("type", p.type).put("base", p.base).put("username", p.username).put("password", p.password).toString()).apply()
        profile = p
        cache.clear(); items = emptyList()
    }

    // ---------- Home ----------

    private fun homeCard(icon: String, title: String, subtitle: String, onClick: () -> Unit): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.rgb(17, 29, 48), 18f)
            setPadding(dp(12), dp(10), dp(8), dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        val symbol = TextView(this).apply {
            text = icon
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(79, 185, 255))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(12, 65, 111), Color.rgb(24, 42, 78))
            ).apply { cornerRadius = dp(10).toFloat() }
        }
        card.addView(symbol, LinearLayout.LayoutParams(dp(40), dp(40)))
        val copy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        copy.addView(TextView(this).apply {
            text = title
            textSize = 14f
            setTextColor(white)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(2))
            maxLines = 1
        })
        copy.addView(TextView(this).apply {
            text = subtitle
            textSize = 11f
            setTextColor(muted)
            maxLines = 1
        })
        card.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(label("›", 22f, muted, true))
        return card
    }

    private fun homeSection(value: String): TextView =
        label(value.uppercase(), 11f, Color.rgb(112, 166, 222), true).apply {
            setPadding(dp(2), dp(16), dp(2), dp(8))
            letterSpacing = 0.12f
        }

    private fun home() {
        back = null
        guideMode = false
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(16), dp(10), dp(16), dp(8))
        }
        setContentView(root)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(12))
        }
        val mark = TextView(this).apply {
            text = "▶"
            textSize = 19f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(0, 174, 255), Color.rgb(115, 72, 255))
            ).apply { cornerRadius = dp(10).toFloat() }
        }
        header.addView(mark, LinearLayout.LayoutParams(dp(40), dp(40)))
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        brand.addView(TextView(this).apply {
            text = "YepPlayer"
            textSize = 22f
            setTextColor(white)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        })
        brand.addView(label("YOUR ENTERTAINMENT HUB", 9f, muted, true).apply { setPadding(0, 0, 0, 0) })
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(action("⚙", { settings() }))
        root.addView(header)

        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(content)
        }, LinearLayout.LayoutParams(-1, 0, 1f))

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(13, 48, 88), Color.rgb(20, 31, 58), Color.rgb(37, 25, 75))
            ).apply {
                cornerRadius = dp(16).toFloat()
                setStroke(1, Color.rgb(43, 113, 190))
            }
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        hero.addView(label(if (profile == null) "WELCOME TO YEPPLAYER" else "YOUR PLAYLIST IS READY", 10f, Color.rgb(105, 202, 255), true))
        hero.addView(label("TV, made yours.", 25f, white, true))
        hero.addView(label(
            if (profile == null) "Bring your live channels, films and series together."
            else "Your ${profile!!.type.uppercase()} library is ready to explore.",
            12f, Color.rgb(207, 220, 239)
        ))
        hero.addView(action(
            if (profile == null) "＋   CONNECT YOUR PLAYLIST" else "＋   MANAGE PLAYLISTS",
            { configure() }, true
        ), LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        val last = lastChannel()
        if (last != null) {
            hero.addView(action("▶   Resume ${last.name}", { playLive(last, listOf(last)) }), LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(8) })
        }
        content.addView(hero)

        content.addView(homeSection("Explore"))
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val rows = listOf(
            listOf("Live TV", "Movies"),
            listOf("Series", "TV Guide"),
            listOf("Recent", "Favourites"),
            listOf("Recordings", "Account")
        )
        rows.forEach { entries ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            entries.forEach { title ->
                val details: Triple<String, String, () -> Unit> = when (title) {
                    "Live TV" -> Triple("▣", "Channels & groups", { load("live") })
                    "Movies" -> Triple("▤", "Your VOD library", { load("vod") })
                    "Series" -> Triple("▧", "Seasons & episodes", { load("series") })
                    "TV Guide" -> Triple("▦", "Now & next per channel", { load("live", guide = true) })
                    "Recent" -> Triple("◷", "Recently watched", { showItems(recent(), "Recently watched", "recent") })
                    "Favourites" -> Triple("★", "Your saved items", { showItems(favourites(), "Favourites", "favs") })
                    "Recordings" -> Triple("●", "PVR library", { recordings() })
                    else -> Triple("◉", "Expiry & connections", { showAccount() })
                }
                val tile = homeCard(details.first, title, details.second, details.third)
                row.addView(tile, LinearLayout.LayoutParams(0, dp(68), 1f).apply {
                    if (title == entries[0]) rightMargin = dp(5) else leftMargin = dp(5)
                    bottomMargin = dp(10)
                })
            }
            grid.addView(row)
        }
        content.addView(grid)

        content.addView(homeSection("Personalise your experience"))
        content.addView(homeCard("☷", "Manage channels & groups", "Hide, show and organise channel groups") { manageGroups() })
        content.addView(gap())
        content.addView(homeCard("⌑", "Parental controls", "Set or change your viewing PIN") { changePin() })
        content.addView(gap())
        content.addView(homeCard("⚙", "Settings & playlists", "Accounts, playlists and app preferences") { settings() })

        content.addView(TextView(this).apply {
            text = "YepPlayer  •  Only use streams and playlists you are authorised to access."
            textSize = 10f
            setTextColor(Color.rgb(112, 128, 151))
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(16), dp(8), dp(12))
        })
    }

    // ---------- Playlist setup ----------

    private fun configure() {
        screen("Connect a playlist")
        val c = scroll()
        val current = profile
        c.addView(label("XTREAM CODES", 13f, blue, true))
        val server = EditText(this).apply { hint = "Server URL, e.g. http://provider.com:8080"; setSingleLine(true); inputType = InputType.TYPE_TEXT_VARIATION_URI; setText(current?.takeIf { it.type == "xtream" }?.base ?: "") }
        val user = EditText(this).apply { hint = "Username"; setSingleLine(true); setText(current?.takeIf { it.type == "xtream" }?.username ?: "") }
        val pass = EditText(this).apply {
            hint = if (current?.type == "xtream") "Password (leave empty to keep current)" else "Password"
            setSingleLine(true); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        c.addView(server); c.addView(user); c.addView(pass)
        c.addView(action("Test & save Xtream account", {
            val password = pass.text.toString().ifEmpty { current?.takeIf { it.type == "xtream" }?.password.orEmpty() }
            if (server.text.isBlank() || user.text.isBlank() || password.isBlank()) { toast("Fill in all fields"); return@action }
            val p = Profile("xtream", server.text.toString().trim(), user.text.toString().trim(), password)
            toast("Checking account…")
            pool.execute {
                try {
                    val info = Source.account(p)
                    runOnUiThread {
                        saveProfile(p.copy(base = Source.base(p)))
                        toast("Connected • ${info.status}" + (if (info.expiry > 0) " • expires ${dayFmt.format(Date(info.expiry * 1000))}" else ""))
                        load("live")
                    }
                } catch (e: Exception) {
                    runOnUiThread { toast(e.message ?: "Unable to connect") }
                }
            }
        }, true), LinearLayout.LayoutParams(-1, dp(48)))
        c.addView(gap(16))
        c.addView(label("M3U PLAYLIST", 13f, blue, true))
        val m3u = EditText(this).apply { hint = "https://example.com/playlist.m3u"; setSingleLine(true); setText(current?.takeIf { it.type == "m3u" }?.base ?: "") }
        c.addView(m3u)
        c.addView(action("Save M3U URL", {
            val v = m3u.text.toString().trim()
            val xt = Regex("^(https?://[^?]+)/get\\.php\\?(.*)$", RegexOption.IGNORE_CASE).find(v)
            val qp = xt?.groupValues?.get(2)?.split('&')?.associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('=', ""), "UTF-8") }
            if (xt != null && !qp?.get("username").isNullOrBlank() && !qp?.get("password").isNullOrBlank()) {
                // An Xtream get.php link: use the richer Xtream API instead (movies, series, EPG, catch-up).
                saveProfile(Profile("xtream", xt.groupValues[1], qp!!["username"]!!, qp["password"]!!))
                toast("Xtream link detected: using Xtream mode for movies, series and guide")
                load("live")
            } else if (!v.startsWith("http", true)) toast("Enter an HTTP(S) URL")
            else { saveProfile(Profile("m3u", v)); load("live") }
        }, true), LinearLayout.LayoutParams(-1, dp(48)))
    }

    // ---------- Lists ----------

    private fun kindTitle(kind: String) = when (kind) { "live" -> "Live TV"; "vod" -> "Movies"; "series" -> "Series"; else -> kind }

    private fun load(type: String, guide: Boolean = false, refresh: Boolean = false) {
        val p = profile ?: run { configure(); return }
        currentKind = type
        val title = if (guide) "TV Guide" else kindTitle(type)
        val cached = cache[type]
        if (cached != null && !refresh) { items = cached; guideMode = guide; showItems(cached, title, type); return }
        screen("Loading ${title}…")
        root.addView(ProgressBar(this).apply { isIndeterminate = true })
        pool.execute {
            try {
                val loaded = Source.load(p, type)
                runOnUiThread { cache[type] = loaded; items = loaded; guideMode = guide; showItems(loaded, title, type) }
            } catch (e: Exception) {
                runOnUiThread {
                    screen("Connection problem")
                    scroll().addView(label(e.message ?: "Unable to load source", 14f, muted))
                    root.addView(action("Try again", { load(type, guide, true) }, true), LinearLayout.LayoutParams(-1, dp(48)))
                }
            }
        }
    }

    private fun hiddenKey(kind: String) = "hidden_groups_$kind"
    private fun hiddenGroups(kind: String): Set<String> = prefs.getStringSet(hiddenKey(kind), null)?.toSet()
        ?: (if (kind == "live") prefs.getStringSet("hidden_groups", emptySet())?.toSet() else null) ?: emptySet()

    private inner class RowHolder {
        val row = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        val logo = ImageView(this@MainActivity).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(dp(3), dp(3), dp(3), dp(3)) }
        val name = TextView(this@MainActivity).apply { textSize = 15f; setTextColor(white); typeface = Typeface.DEFAULT_BOLD; maxLines = 2 }
        val sub = TextView(this@MainActivity).apply { textSize = 11f; setTextColor(muted); maxLines = 1 }
        val star = TextView(this@MainActivity).apply { textSize = 22f; setTextColor(Color.rgb(255, 196, 61)); gravity = Gravity.CENTER; isFocusable = false }
        init {
            row.addView(logo, LinearLayout.LayoutParams(dp(48), dp(48)))
            val info = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(2), dp(4), dp(2)) }
            info.addView(name); info.addView(sub)
            row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(star, LinearLayout.LayoutParams(dp(44), dp(44)))
            row.tag = this
        }
    }

    private inner class ItemAdapter(var rows: List<MediaItem>) : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val h = (convertView?.tag as? RowHolder) ?: RowHolder()
            val item = rows[position]
            h.name.text = item.name
            h.sub.text = item.category + (if (item.catchup) "  •  ⟲ catch-up" else "")
            h.star.text = if (isFavourite(item)) "★" else "☆"
            h.star.setOnClickListener { toggleFavourite(item); notifyDataSetChanged() }
            h.logo.tag = item.logoUrl
            h.logo.setImageDrawable(null)
            h.logo.background = rounded(Color.rgb(10, 45, 82), 12f)
            if (item.logoUrl.isNotBlank()) loadLogo(item.logoUrl, h.logo)
            return h.row
        }
    }

    /** [kind] is live/vod/series/episode, or recent/favs for mixed lists. */
    private fun showItems(data: List<MediaItem>, title: String, kind: String, onBack: () -> Unit = { home() }) {
        screen(title, onBack)
        val hidden = if (kind in setOf("live", "vod", "series")) hiddenGroups(kind) else emptySet()
        val base = data.filter { it.category !in hidden }
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val search = EditText(this).apply {
            hint = "⌕  Search ${title.lowercase()}"; setSingleLine(true); setTextColor(white); setHintTextColor(muted)
            background = rounded(panel, 14f); setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        controls.addView(search, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(6) })
        if (kind in setOf("live", "vod", "series")) {
            controls.addView(action("Groups", { manageGroups(data, kind) }))
            controls.addView(action("⟳", { load(kind, guideMode, refresh = true) }), LinearLayout.LayoutParams(dp(44), dp(44)).apply { leftMargin = dp(6) })
        }
        root.addView(controls)
        val categories = listOf("All") + base.map { it.category }.distinct().sorted()
        val filter = Spinner(this)
        filter.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, categories)
        if (categories.size > 2) root.addView(filter)
        val count = label("", 11f, muted)
        root.addView(count)
        val adapter = ItemAdapter(base)
        val list = ListView(this).apply {
            divider = null; dividerHeight = dp(4); this.adapter = adapter; isFastScrollEnabled = true
            selector = rounded(Color.argb(90, 27, 143, 255), 14f)
            emptyView?.visibility = View.GONE
        }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        fun render() {
            val selected = categories.getOrElse(filter.selectedItemPosition.coerceAtLeast(0)) { "All" }
            val q = search.text.toString().trim()
            val visible = base.filter { (selected == "All" || it.category == selected) && (q.isEmpty() || it.name.contains(q, true) || it.category.contains(q, true)) }
            adapter.rows = visible
            adapter.notifyDataSetChanged()
            val noun = when (kind) { "live" -> "channels"; "vod" -> "movies"; "series" -> "series"; "episode" -> "episodes"; else -> "items" }
            count.text = if (visible.isEmpty()) "Nothing here yet" else "${visible.size} $noun" + (if (visible.size != data.size) " of ${data.size}" else "")
        }
        list.setOnItemClickListener { _, _, position, _ ->
            val item = adapter.rows[position]
            open(item, adapter.rows) { showItems(data, title, kind, onBack) }
        }
        list.setOnItemLongClickListener { _, _, position, _ ->
            val item = adapter.rows[position]
            toggleFavourite(item); adapter.notifyDataSetChanged()
            toast(if (isFavourite(item)) "Added to favourites" else "Removed from favourites"); true
        }
        filter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = render()
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = render()
        })
        render()
    }

    private fun loadLogo(url: String, target: ImageView) {
        logoCache.get(url)?.let { target.setImageBitmap(it); return }
        if (url in failedLogos) return
        logoPool.execute {
            if (target.tag != url) return@execute
            try {
                val bytes = URL(url).openConnection().run {
                    connectTimeout = 5000; readTimeout = 7000
                    setRequestProperty("User-Agent", Source.USER_AGENT)
                    getInputStream().use { it.readBytes() }
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                val want = dp(96)
                while (bounds.outWidth / (sample * 2) >= want && bounds.outHeight / (sample * 2) >= want) sample *= 2
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                if (bitmap != null) {
                    logoCache.put(url, bitmap)
                    runOnUiThread { if (target.tag == url) target.setImageBitmap(bitmap) }
                } else synchronized(failedLogos) { failedLogos.add(url) }
            } catch (_: Exception) { synchronized(failedLogos) { failedLogos.add(url) } }
        }
    }

    private fun manageGroups(source: List<MediaItem> = cache[currentKind] ?: items, kind: String = currentKind) {
        val groups = source.map { it.category }.distinct().sorted()
        if (groups.isEmpty()) { toast("Load a playlist first"); return }
        val hidden = hiddenGroups(kind).toMutableSet()
        val checked = BooleanArray(groups.size) { groups[it] !in hidden }
        AlertDialog.Builder(this).setTitle("Show or hide ${kindTitle(kind)} groups")
            .setMultiChoiceItems(groups.toTypedArray(), checked) { _, which, isChecked -> if (isChecked) hidden.remove(groups[which]) else hidden.add(groups[which]) }
            .setPositiveButton("Save") { _, _ -> prefs.edit().putStringSet(hiddenKey(kind), hidden).apply(); showItems(source, kindTitle(kind), kind) }
            .setNeutralButton("Show all") { _, _ -> prefs.edit().putStringSet(hiddenKey(kind), emptySet()).remove("hidden_groups").apply(); showItems(source, kindTitle(kind), kind) }
            .setNegativeButton("Cancel", null).show()
    }

    // ---------- Favourites & recent (stored with full item so they work without reloading) ----------

    private fun itemJson(i: MediaItem) = JSONObject().put("id", i.id).put("name", i.name).put("url", i.url).put("category", i.category)
        .put("kind", i.kind).put("epgId", i.epgId).put("catchup", i.catchup).put("seriesId", i.seriesId).put("logoUrl", i.logoUrl).put("archiveDays", i.archiveDays)
    private fun jsonItem(o: JSONObject) = MediaItem(o.optString("id"), o.optString("name"), o.optString("url"), o.optString("category"), o.optString("kind"),
        o.optString("epgId"), o.optBoolean("catchup"), o.optString("seriesId"), o.optString("logoUrl"), o.optInt("archiveDays"))
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
        if (item.kind == "live") prefs.edit().putString("last_channel|${profile?.base}", itemJson(item).toString()).apply()
    }
    private fun lastChannel(): MediaItem? = try { JSONObject(prefs.getString("last_channel|${profile?.base}", null) ?: return null).let { jsonItem(it) } } catch (_: Exception) { null }

    // ---------- Opening items ----------

    private fun isRestricted(item: MediaItem) = Regex("adult|xxx|18\\+|porn", RegexOption.IGNORE_CASE).containsMatchIn(item.category + " " + item.name)

    private fun open(item: MediaItem, siblings: List<MediaItem>, returnTo: () -> Unit) {
        val pin = prefs.getString("pin", null)
        if (pin != null && isRestricted(item)) {
            val entry = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD }
            AlertDialog.Builder(this).setTitle("Parental PIN").setView(entry).setPositiveButton("Unlock") { _, _ -> if (entry.text.toString() == pin) openUnlocked(item, siblings, returnTo) else toast("Incorrect PIN") }.setNegativeButton("Cancel", null).show()
        } else openUnlocked(item, siblings, returnTo)
    }

    private fun openUnlocked(item: MediaItem, siblings: List<MediaItem>, returnTo: () -> Unit) {
        val p = profile ?: return
        when (item.kind) {
            "series" -> {
                screen("Loading episodes…", returnTo)
                root.addView(ProgressBar(this).apply { isIndeterminate = true })
                pool.execute {
                    try {
                        val eps = Source.episodes(p, item.seriesId.ifBlank { item.id })
                        runOnUiThread { showItems(eps, item.name, "episode", returnTo) }
                    } catch (e: Exception) { runOnUiThread { toast(e.message ?: "Episodes unavailable"); returnTo() } }
                }
            }
            "live" -> {
                if (guideMode && p.type == "xtream") { showEpg(item, siblings); return }
                if (p.type != "xtream") { playLive(item, siblings); return }
                val actions = mutableListOf("Play", "Programme guide")
                if (item.catchup) actions.add("Catch-up / replay")
                AlertDialog.Builder(this).setTitle(item.name).setItems(actions.toTypedArray()) { _, which ->
                    when (actions[which]) { "Play" -> playLive(item, siblings); "Programme guide" -> showEpg(item, siblings); else -> catchup(item) }
                }.show()
            }
            else -> { addRecent(item); startPlayer(item.url, item.name, live = false) }
        }
    }

    private fun playLive(item: MediaItem, siblings: List<MediaItem>) {
        addRecent(item)
        val channels = siblings.filter { it.kind == "live" }.ifEmpty { listOf(item) }
        val idx = channels.indexOfFirst { sameItem(it, item) }.coerceAtLeast(0)
        val list = if (channels.size > 2000) {
            // Keep the Intent small on huge playlists: pass a window around the chosen channel.
            val from = (idx - 1000).coerceAtLeast(0); channels.subList(from, (from + 2000).coerceAtMost(channels.size))
        } else channels
        val i = list.indexOfFirst { sameItem(it, item) }.coerceAtLeast(0)
        startActivity(Intent(this, PlayerActivity::class.java)
            .putExtra("url", item.url).putExtra("name", item.name).putExtra("live", true)
            .putExtra("urls", list.map { it.url }.toTypedArray())
            .putExtra("names", list.map { it.name }.toTypedArray())
            .putExtra("index", i))
    }

    private fun epgLine(e: EpgEntry): String {
        val now = System.currentTimeMillis() / 1000
        val mark = when { e.isNow(now) -> "▶ NOW  "; e.isPast(now) && e.hasArchive -> "⟲  "; else -> "" }
        return "$mark${timeFmt.format(Date(e.start * 1000))}–${timeFmt.format(Date(e.stop * 1000))}  ${e.title}"
    }

    private fun showEpg(item: MediaItem, siblings: List<MediaItem>) {
        val p = profile ?: return
        toast("Loading guide…")
        pool.execute {
            try {
                val now = System.currentTimeMillis() / 1000
                val entries = Source.epg(p, item.id).filter { it.stop > now }
                runOnUiThread {
                    val b = AlertDialog.Builder(this).setTitle(item.name + " • TV Guide")
                    if (entries.isEmpty()) b.setMessage("No guide data available for this channel.")
                    else b.setItems(entries.map { epgLine(it) }.toTypedArray()) { _, which ->
                        val e = entries[which]
                        AlertDialog.Builder(this).setTitle(e.title)
                            .setMessage("${dayFmt.format(Date(e.start * 1000))}  ${timeFmt.format(Date(e.start * 1000))}–${timeFmt.format(Date(e.stop * 1000))}\n\n${e.description.ifBlank { "No description." }}")
                            .setPositiveButton("Watch channel") { _, _ -> playLive(item, siblings) }
                            .setNegativeButton("Close", null).show()
                    }
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
                    AlertDialog.Builder(this).setTitle("Replay • ${item.name}").setItems(labels.toTypedArray()) { _, which ->
                        val e = past[which]
                        val url = Source.timeshiftUrl(p, item.id, e.serverStart, e.durationMinutes)
                        if (url == null) toast("This programme can't be replayed") else { addRecent(item); startPlayer(url, "${item.name} • ${e.title}", live = false) }
                    }.setNegativeButton("Close", null).show()
                }
            } catch (e: Exception) { runOnUiThread { toast("Replay unavailable: ${e.message}") } }
        }
    }

    private fun startPlayer(url: String, name: String, live: Boolean) {
        startActivity(Intent(this, PlayerActivity::class.java).putExtra("url", url).putExtra("name", name).putExtra("live", live))
    }

    // ---------- Settings ----------

    private fun showAccount() {
        val p = profile ?: run { configure(); return }
        if (p.type != "xtream") { toast("Account details are only available for Xtream accounts"); return }
        toast("Checking account…")
        pool.execute {
            try {
                val a = Source.account(p)
                runOnUiThread {
                    val expiry = if (a.expiry > 0) dayFmt.format(Date(a.expiry * 1000)) + " " + SimpleDateFormat("yyyy", Locale.getDefault()).format(Date(a.expiry * 1000)) else "Unlimited"
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
        val options = arrayOf("Manage playlists", "Account information", "Manage / hide channel groups", "Parental PIN", "Refresh playlist data", "About YepPlayer")
        AlertDialog.Builder(this).setTitle("YepPlayer settings").setItems(options) { _, which ->
            when (which) {
                0 -> configure()
                1 -> showAccount()
                2 -> manageGroups()
                3 -> changePin()
                4 -> { cache.clear(); items = emptyList(); logoCache.evictAll(); failedLogos.clear(); toast("Playlist data will reload") }
                else -> AlertDialog.Builder(this).setTitle("YepPlayer ${packageManager.getPackageInfo(packageName, 0).versionName}")
                    .setMessage("A personal IPTV player for authorised playlists.\n\nTips: long-press an item to add it to favourites. In the player, use CH +/− (or the remote's up/down keys) to zap between channels.")
                    .setPositiveButton("OK", null).show()
            }
        }.show()
    }

    private fun recordings() {
        AlertDialog.Builder(this).setTitle("Recordings").setMessage("Live recording is not included in this build yet. It needs a compatible stream type, storage permission handling and a background recording service. Provider catch-up (replay) is available from a channel's menu where supported.").setPositiveButton("OK", null).show()
    }

    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()
    override fun onDestroy() { pool.shutdownNow(); logoPool.shutdownNow(); super.onDestroy() }
}
