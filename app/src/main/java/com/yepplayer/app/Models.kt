package com.yepplayer.app

data class MediaItem(val id: String, val name: String, val url: String, val category: String, val kind: String, val epgId: String = "", val catchup: Boolean = false, val seriesId: String = "")
data class Profile(val type: String, val base: String, val username: String = "", val password: String = "")
