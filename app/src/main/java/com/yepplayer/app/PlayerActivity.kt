package com.yepplayer.app

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * Full-screen player. Extras:
 *  - "url"/"name": what to play
 *  - "urls"/"names" + "index": optional channel list for zapping (live TV)
 *  - "live": true when the stream is a live channel (enables .m3u8 -> .ts fallback, no resume)
 */
@OptIn(UnstableApi::class)
class PlayerActivity : Activity() {
    private var player: ExoPlayer? = null
    private lateinit var view: PlayerView
    private lateinit var title: TextView
    private lateinit var errorBox: LinearLayout
    private lateinit var errorText: TextView
    private lateinit var spinner: ProgressBar
    private lateinit var topBar: LinearLayout

    private var urls = arrayOf<String>()
    private var names = arrayOf<String>()
    private var ids = arrayOf<String>()
    private var nums = intArrayOf()
    private lateinit var infoCard: LinearLayout
    private lateinit var infoNum: TextView
    private lateinit var infoName: TextView
    private lateinit var nowTitle: TextView
    private lateinit var nowTime: TextView
    private lateinit var nextTitle: TextView
    private lateinit var nextTime: TextView
    private lateinit var nowBar: View
    private lateinit var nowTrack: FrameLayout
    private val epgPool = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val timeFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    private var index = 0
    private var live = false
    private var triedFallback = false
    private var currentUrl = ""
    private var resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
    private val prefs by lazy { getSharedPreferences("yepplayer", MODE_PRIVATE) }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val single = intent.getStringExtra("url") ?: run { finish(); return }
        urls = intent.getStringArrayExtra("urls") ?: arrayOf(single)
        names = intent.getStringArrayExtra("names") ?: arrayOf(intent.getStringExtra("name") ?: "YepPlayer")
        index = savedInstanceState?.getInt("index") ?: intent.getIntExtra("index", 0)
        if (index !in urls.indices || names.size != urls.size) { urls = arrayOf(single); names = arrayOf(intent.getStringExtra("name") ?: "YepPlayer"); index = 0 }
        live = intent.getBooleanExtra("live", false)
        ids = intent.getStringArrayExtra("ids")?.takeIf { it.size == urls.size } ?: arrayOf()
        nums = intent.getIntArrayExtra("nums")?.takeIf { it.size == urls.size } ?: IntArray(urls.size) { it + 1 }
        resizeMode = prefs.getInt("resize_mode", AspectRatioFrameLayout.RESIZE_MODE_FIT)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        view = PlayerView(this).apply {
            setShowSubtitleButton(true)
            setShowNextButton(false)
            setShowPreviousButton(false)
            keepScreenOn = true
            this.resizeMode = this@PlayerActivity.resizeMode
            setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> topBar.visibility = v; if (live) infoCard.visibility = v })
            if (live) { setShowFastForwardButton(false); setShowRewindButton(false) }
        }
        root.addView(view, FrameLayout.LayoutParams(-1, -1))

        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.argb(200, 0, 0, 0), Color.TRANSPARENT))
        }
        topBar.addView(roundIcon(R.drawable.ic_back, "Back") { finish() })
        title = TextView(this).apply { textSize = 17f; setTextColor(Color.WHITE); setPadding(dp(12), 0, dp(10), 0); maxLines = 1; typeface = android.graphics.Typeface.DEFAULT_BOLD }
        topBar.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        topBar.addView(roundIcon(R.drawable.ic_aspect, "Aspect ratio") { cycleResize() })
        root.addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        // Live channel info: number, name, now / next with progress, channel up / down.
        infoCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = GradientDrawable().apply { setColor(Color.argb(240, 15, 21, 32)); cornerRadius = dp(20).toFloat(); setStroke(dp(1), Color.rgb(37, 48, 66)) }
            visibility = if (live) View.VISIBLE else View.GONE
        }
        if (urls.size > 1) infoCard.addView(roundIcon(R.drawable.ic_up, "Previous channel") { zap(-1) })
        val numBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(Color.rgb(28, 39, 56)); cornerRadius = dp(14).toFloat() }
        }
        infoNum = label("", 13f, Color.rgb(154, 168, 188), true)
        numBox.addView(infoNum)
        infoCard.addView(numBox, LinearLayout.LayoutParams(dp(64), dp(64)).apply { leftMargin = dp(14) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), 0) }
        val nameRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        infoName = label("", 19f, Color.WHITE, true)
        nameRow.addView(infoName)
        nameRow.addView(label("LIVE", 10f, Color.WHITE, true).apply {
            setPadding(dp(7), dp(3), dp(7), dp(3)); letterSpacing = 0.08f
            background = GradientDrawable().apply { setColor(Color.rgb(229, 72, 77)); cornerRadius = dp(6).toFloat() }
        }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10) })
        col.addView(nameRow)
        val nowRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        nowRow.addView(label("NOW", 13f, Color.rgb(255, 159, 67), true))
        nowTitle = label("", 14f, Color.WHITE, false)
        nowRow.addView(nowTitle, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
        nowTime = label("", 13f, Color.rgb(154, 168, 188), false)
        nowRow.addView(nowTime)
        col.addView(nowRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        nowTrack = FrameLayout(this).apply { background = GradientDrawable().apply { setColor(Color.rgb(37, 48, 66)); cornerRadius = dp(3).toFloat() } }
        nowBar = View(this).apply { background = GradientDrawable().apply { setColor(Color.rgb(255, 159, 67)); cornerRadius = dp(3).toFloat() } }
        nowTrack.addView(nowBar, FrameLayout.LayoutParams(0, -1))
        col.addView(nowTrack, LinearLayout.LayoutParams(-1, dp(5)).apply { topMargin = dp(8) })
        val nextRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        nextRow.addView(label("NEXT", 12f, Color.rgb(154, 168, 188), true))
        nextTitle = label("", 13f, Color.rgb(154, 168, 188), false)
        nextRow.addView(nextTitle, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
        nextTime = label("", 12f, Color.rgb(154, 168, 188), false)
        nextRow.addView(nextTime)
        col.addView(nextRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        infoCard.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        if (urls.size > 1) infoCard.addView(roundIcon(R.drawable.ic_down, "Next channel") { zap(1) })
        // Sits above the player's own seek/controls bar.
        root.addView(infoCard, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(24), 0, dp(24), dp(84)) })

        spinner = ProgressBar(this).apply { isIndeterminate = true; visibility = View.GONE }
        root.addView(spinner, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER))

        errorBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; visibility = View.GONE
            setPadding(dp(24), dp(20), dp(24), dp(20))
            background = GradientDrawable().apply { setColor(Color.argb(220, 15, 22, 36)); cornerRadius = dp(16).toFloat() }
        }
        errorText = TextView(this).apply { setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER }
        errorBox.addView(errorText)
        errorBox.addView(barButton("Retry") { triedFallback = false; play(urls[index]) }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12); gravity = Gravity.CENTER_HORIZONTAL })
        root.addView(errorBox, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        setContentView(root)
        hideSystemUi()
    }

    private fun barButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false; setTextColor(Color.WHITE); textSize = 14f
        minWidth = dp(48); minHeight = dp(40); setPadding(dp(10), 0, dp(10), 0)
        background = GradientDrawable().apply { setColor(Color.argb(140, 40, 60, 90)); cornerRadius = dp(12).toFloat() }
        setOnClickListener { onClick() }
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private fun roundIcon(res: Int, desc: String, onClick: () -> Unit) = FrameLayout(this).apply {
        contentDescription = desc
        background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { setColor(Color.rgb(28, 39, 56)); cornerRadius = dp(24).toFloat(); setStroke(dp(2), Color.rgb(47, 123, 255)) })
            addState(intArrayOf(), GradientDrawable().apply { setColor(Color.argb(230, 28, 39, 56)); cornerRadius = dp(24).toFloat() })
        }
        addView(ImageView(this@PlayerActivity).apply { setImageResource(res); imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE) }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { leftMargin = dp(8) }
        isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun updateInfo() {
        if (!live) return
        infoNum.text = nums.getOrNull(index)?.toString() ?: ""
        infoName.text = names[index]
        nowTitle.text = ""; nowTime.text = ""; nextTitle.text = ""; nextTime.text = ""
        nowBar.layoutParams = FrameLayout.LayoutParams(0, -1)
        val id = ids.getOrNull(index) ?: return
        val p = Source.readProfile(prefs) ?: return
        if (p.type != "xtream") return
        val forIndex = index
        epgPool.execute {
            val list = try { Source.epg(p, id) } catch (_: Exception) { emptyList() }
            runOnUiThread {
                if (forIndex != index || isFinishing) return@runOnUiThread
                val now = System.currentTimeMillis() / 1000
                val cur = list.firstOrNull { it.isNow(now) }
                val next = list.firstOrNull { it.start >= (cur?.stop ?: now) }
                nowTitle.text = cur?.title ?: "No guide data"
                nowTime.text = cur?.let { "${timeFmt.format(java.util.Date(it.start * 1000))} – ${timeFmt.format(java.util.Date(it.stop * 1000))}" } ?: ""
                nextTitle.text = next?.title ?: ""
                nextTime.text = next?.let { timeFmt.format(java.util.Date(it.start * 1000)) } ?: ""
                if (cur != null) {
                    val frac = ((now - cur.start).toFloat() / (cur.stop - cur.start)).coerceIn(0f, 1f)
                    nowTrack.post { nowBar.layoutParams = FrameLayout.LayoutParams((nowTrack.width * frac).toInt(), -1) }
                }
            }
        }
    }

    private fun hideSystemUi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) hideSystemUi() }

    private fun buildPlayer(): ExoPlayer {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Source.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(20000)
        val sources = DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http))
        return ExoPlayer.Builder(this).setMediaSourceFactory(sources).build().apply {
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    spinner.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                    if (state == Player.STATE_READY) errorBox.visibility = View.GONE
                }
                override fun onPlayerError(error: PlaybackException) = handleError(error)
            })
        }
    }

    private fun handleError(error: PlaybackException) {
        // Live streams that fall behind the live window: jump back to live edge.
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            player?.seekToDefaultPosition(); player?.prepare(); return
        }
        // Many Xtream panels serve live channels only as MPEG-TS: retry once with .ts.
        if (live && !triedFallback && currentUrl.endsWith(".m3u8")) {
            triedFallback = true
            play(currentUrl.removeSuffix(".m3u8") + ".ts")
            return
        }
        spinner.visibility = View.GONE
        val reason = when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "The server refused the stream. Your account may have reached its connection limit, or the channel is offline."
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Network problem. Check your connection."
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "This stream format isn't supported on this device."
            else -> "Playback failed (${error.errorCodeName})."
        }
        errorText.text = reason
        errorBox.visibility = View.VISIBLE
        topBar.visibility = View.VISIBLE
    }


    private fun play(url: String) {
        val p = player ?: return
        saveResume()
        currentUrl = url
        title.text = names[index]
        errorBox.visibility = View.GONE
        updateInfo()
        p.setMediaItem(MediaItem.fromUri(url))
        if (!live) {
            val pos = prefs.getLong(Source.resumeKey(url), 0L)
            if (pos > 0) p.seekTo(pos)
        }
        p.prepare()
        p.playWhenReady = true
    }

    private fun saveResume() {
        val p = player ?: return
        if (live || currentUrl.isEmpty()) return
        val pos = p.currentPosition; val dur = p.duration
        val e = prefs.edit()
        if (dur > 0 && pos > 30_000 && pos < dur - 60_000) e.putLong(Source.resumeKey(currentUrl), pos).putLong(Source.durationKey(currentUrl), dur)
        else if (dur > 0) e.remove(Source.resumeKey(currentUrl)).remove(Source.durationKey(currentUrl))
        e.apply()
    }

    private fun zap(delta: Int) {
        if (urls.size < 2) return
        index = (index + delta + urls.size) % urls.size
        triedFallback = false
        prefs.edit().putString("last_channel_url", urls[index]).apply()
        play(urls[index])
    }

    private fun cycleResize() {
        resizeMode = when (resizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        view.resizeMode = resizeMode
        prefs.edit().putInt("resize_mode", resizeMode).apply()
        val label = when (resizeMode) { AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Zoom"; AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Stretch"; else -> "Fit" }
        android.widget.Toast.makeText(this, label, android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (urls.size > 1) when (keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> { zap(1); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> { zap(-1); return true }
            KeyEvent.KEYCODE_DPAD_UP -> if (!view.isControllerFullyVisible) { zap(1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> if (!view.isControllerFullyVisible) { zap(-1); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onStart() {
        super.onStart()
        if (player == null) {
            player = buildPlayer()
            view.player = player
            play(urls[index])
        }
    }

    override fun onStop() {
        saveResume()
        view.player = null
        player?.release(); player = null
        super.onStop()
    }

    override fun onDestroy() { epgPool.shutdownNow(); super.onDestroy() }

    override fun onSaveInstanceState(outState: Bundle) { outState.putInt("index", index); super.onSaveInstanceState(outState) }
}
