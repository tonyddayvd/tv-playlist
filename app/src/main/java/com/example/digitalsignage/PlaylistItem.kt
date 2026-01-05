package com.example.digitalsignage

data class PlaylistData(
    val settings: Settings,
    val items: List<PlaylistItem>
)

data class Settings(
    val orientation: String = "landscape", // "portrait" or "landscape"
    val transitionDuration: Int = 1000
)

data class PlaylistItem(
    val id: String,
    val type: String, // "video" or "image"
    val url: String,
    val durationSeconds: Int = 10,
    val localPath: String? = null
)
