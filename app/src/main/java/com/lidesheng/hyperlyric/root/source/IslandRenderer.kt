package com.lidesheng.hyperlyric.root.source

interface IslandRenderer {
    fun refreshActiveIsland()
    fun updateLyricLine()
    fun updatePosition(position: Long)
    fun onPlaybackStateChanged(isPlaying: Boolean)
    fun clearAllViews()
}
