package com.yepplayer.app

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Color
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

class PlayerActivity : Activity() {
    private var player:ExoPlayer?=null
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val url=intent.getStringExtra("url") ?: run { finish(); return }
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        root.addView(TextView(this).apply { text=intent.getStringExtra("name") ?: "YepPlayer"; textSize=18f; setTextColor(Color.WHITE); setPadding(16,16,16,16) })
        val view=PlayerView(this)
        root.addView(view,LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root)
        player=ExoPlayer.Builder(this).build().also { view.player=it; it.setMediaItem(MediaItem.fromUri(url)); it.prepare(); it.playWhenReady=true }
    }
    override fun onStop() { player?.release(); player=null; super.onStop() }
}
