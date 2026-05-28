package com.lidesheng.hyperlyric.lyric

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import androidx.core.content.edit
import com.lidesheng.hyperlyric.common.PrefsBridge
import com.lidesheng.hyperlyric.ui.utils.Constants as UIConstants
import com.lidesheng.hyperlyric.service.Constants as ServiceConstants
import com.lidesheng.hyperlyric.root.utils.Constants as RootConstants
import com.lidesheng.hyperlyric.utils.LyricProviderManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

val commonMusicApps = mapOf(
    "com.salt.music" to "Salt Player",
    "com.netease.cloudmusic" to "网易云音乐",
    "com.tencent.qqmusic" to "QQ音乐",
    "cn.kuwo.player" to "酷我音乐",
    "com.kugou.android" to "酷狗音乐",
    "com.apple.android.music" to "Apple Music",
    "com.spotify.music" to "Spotify",
    "cmccwm.mobilemusic" to "咪咕音乐",
    "com.luna.music" to "汽水音乐",
    "com.kugou.android.lite" to "酷狗音乐概念版",
    "com.google.android.apps.youtube.music" to "YouTube Music",
    "cn.wenyu.bodian" to "波点音乐",
    "com.miui.player" to "小米音乐",
    "com.xuncorp.qinalt.music" to "青盐云听"
)

data class PlaybackAnchor(
    val position: Long = 0L,      // 基础进度 (ms)
    val timestamp: Long = 0L,     // 记录该进度时的系统时间(SystemClock.elapsedRealtime)
    val speed: Float = 1.0f,      // 播放倍速
    val isPlaying: Boolean = false
)

data class LyricState(
    val islandTitleLeft: String = "等待播放...",
    val islandTitleRight: String = "HyperLyric",
    val notificationTitleLeft: String = "",
    val notificationTitleRight: String = "",
    val songLyric: String = "",
    val songInfo: String = "",
    val showIslandLeftAlbum: Boolean = false,
    val duration: Long = 100L,
    val isPlaying: Boolean = false,
    val targetPackageName: String = "",
    val albumColor: Int = Color.BLACK,
    val albumColorEnd: Int = Color.BLACK,
    val albumBitmap: Bitmap? = null,
    val notificationAlbumBitmap: Bitmap? = null,
    val notificationAlbumBitmapCircular: Bitmap? = null,
    val islandLeftIconStyle: Int = 0,
    
    val isFetchingLyrics: Boolean = false,
    val isLoadingAlbumArt: Boolean = false,
    val playbackAnchor: PlaybackAnchor = PlaybackAnchor()
)

object DynamicLyricData {
    private val _musicState = MutableStateFlow(LyricState())
    val musicState = _musicState.asStateFlow()

    val currentState: LyricState
        get() = _musicState.value

    private val _progressFlow = MutableSharedFlow<Float>(extraBufferCapacity = 1)
    val progressFlow: SharedFlow<Float> = _progressFlow

    fun emitProgress(progress: Float) {
        _progressFlow.tryEmit(progress)
    }

    fun updateFetchingLyrics(fetching: Boolean) {
        _musicState.update { it.copy(isFetchingLyrics = fetching) }
    }

    fun updateLoadingAlbumArt(loading: Boolean) {
        _musicState.update { it.copy(isLoadingAlbumArt = loading) }
    }

    fun updateAnchor(position: Long, isPlaying: Boolean, speed: Float = 1.0f) {
        val newAnchor = PlaybackAnchor(
            position = position,
            timestamp = SystemClock.elapsedRealtime(),
            speed = speed,
            isPlaying = isPlaying
        )
        _musicState.update { it.copy(playbackAnchor = newAnchor, isPlaying = isPlaying) }
    }

    fun updateLeftTitles(islandText: String, notificationText: String = "") {
        _musicState.update { 
            it.copy(
                islandTitleLeft = islandText.ifBlank { " " },
                notificationTitleLeft = notificationText
            ) 
        }
    }

    fun updateBitmaps(albumBmp: Bitmap?, notificationAlbumBmp: Bitmap? = null, notificationAlbumBmpCircular: Bitmap? = null) {
        _musicState.update { it.copy(
            albumBitmap = albumBmp,
            notificationAlbumBitmap = notificationAlbumBmp ?: it.notificationAlbumBitmap,
            notificationAlbumBitmapCircular = notificationAlbumBmpCircular ?: it.notificationAlbumBitmapCircular
        ) }
    }

    fun updateIslandLeftIconStyle(style: Int) {
        _musicState.update { it.copy(islandLeftIconStyle = style) }
    }

    fun updateColor(color: Int, colorEnd: Int) {
        _musicState.update { it.copy(albumColor = color, albumColorEnd = colorEnd) }
    }

    fun updateRightTitles(
        islandText: String,
        notificationText: String = "",
        newSongLyric: String,
        newSongInfo: String,
        newDuration: Long,
        newIsPlaying: Boolean,
        newPackageName: String,
        newShowIslandLeftAlbum: Boolean = false
    ) {
        _musicState.update { oldState ->
            oldState.copy(
                islandTitleRight = islandText,
                notificationTitleRight = notificationText,
                songLyric = newSongLyric,
                songInfo = newSongInfo,
                duration = if (newDuration > 0) newDuration else oldState.duration,
                isPlaying = newIsPlaying,
                targetPackageName = newPackageName,
                showIslandLeftAlbum = newShowIslandLeftAlbum
            )
        }
    }

    fun LyricState.getCurrentPosition(): Long {
        if (!playbackAnchor.isPlaying) return playbackAnchor.position
        val elapsed = SystemClock.elapsedRealtime() - playbackAnchor.timestamp
        return playbackAnchor.position + (elapsed * playbackAnchor.speed).toLong()
    }

    private val _whitelistState = MutableStateFlow<Set<String>>(emptySet())
    val whitelistState = _whitelistState.asStateFlow()

    private val _hookWhitelistState = MutableStateFlow<Set<String>>(emptySet())
    val hookWhitelistState = _hookWhitelistState.asStateFlow()

    private val _hookAddedState = MutableStateFlow<Set<String>>(emptySet())
    val hookAddedState = _hookAddedState.asStateFlow()

    fun initWhitelist(context: Context) {
        val prefs = context.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE)
        val savedSet = prefs.getStringSet(ServiceConstants.KEY_NOTIFICATION_WHITELIST, emptySet())?.toSet() ?: emptySet()
        _whitelistState.value = savedSet

        val hookSavedSet = prefs.getStringSet(RootConstants.KEY_HOOK_WHITELIST, emptySet())?.toSet() ?: emptySet()
        _hookWhitelistState.value = hookSavedSet

        val hookAddedSet = prefs.getStringSet(RootConstants.KEY_HOOK_ADDED_LIST, emptySet())?.toSet() ?: emptySet()
        _hookAddedState.value = hookAddedSet
    }

    // --- 通知页白名单 (原有逻辑) ---
    fun addPackageToWhitelist(context: Context, packageName: String): Boolean {
        val currentSet = _whitelistState.value.toMutableSet()
        if (!currentSet.add(packageName)) return false
        saveWhitelist(context, currentSet)
        return true
    }

    fun removePackageFromWhitelist(context: Context, packageName: String) {
        val currentSet = _whitelistState.value.toMutableSet()
        if (currentSet.remove(packageName)) {
            saveWhitelist(context, currentSet)
        }
    }

    private fun saveWhitelist(context: Context, set: Set<String>) {
        _whitelistState.value = set
        context.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE).edit {
            putStringSet(ServiceConstants.KEY_NOTIFICATION_WHITELIST, set)
        }
        PrefsBridge.putStringSet(ServiceConstants.KEY_NOTIFICATION_WHITELIST, set)
    }

    // --- 歌词钩子白名单 (新逻辑) ---
    fun addPackageToHookList(context: Context, packageName: String): Boolean {
        val addedSet = _hookAddedState.value.toMutableSet()
        if (!addedSet.add(packageName)) return false
        
        val whitelistSet = _hookWhitelistState.value.toMutableSet()
        whitelistSet.add(packageName) // 默认开启
        
        saveHookLists(context, whitelistSet, addedSet)
        return true
    }

    fun toggleHookStatus(context: Context, packageName: String, enabled: Boolean) {
        val currentWhitelist = _hookWhitelistState.value.toMutableSet()
        val currentAdded = _hookAddedState.value.toMutableSet()
        
        if (enabled) {
            currentWhitelist.add(packageName)
            currentAdded.add(packageName) // 确保在已添加列表中，否则 Hook 端可能拦截
        } else {
            currentWhitelist.remove(packageName)
            // 关闭时不从 Added 列表移除，保持其作为曾用项
        }
        saveHookLists(context, currentWhitelist, currentAdded)
    }

    fun removePackageFromHookPage(context: Context, packageName: String) {
        val addedSet = _hookAddedState.value.toMutableSet()
        val whitelistSet = _hookWhitelistState.value.toMutableSet()
        
        addedSet.remove(packageName)
        whitelistSet.remove(packageName)
        
        saveHookLists(context, whitelistSet, addedSet)
    }

    /**
     * 清理包名：如果一个 App 原本是由插件关联的，但现在插件已卸载，则自动清理其 Hook 记录。
     */
    fun cleanupOrphanedPackages(context: Context, installedProviderPkgs: Set<String>) {
        val allPossibleTargets = LyricProviderManager.providerToTargetMap.values.flatten().toSet()
        val currentlyCoveredTargets = installedProviderPkgs.flatMap { 
            LyricProviderManager.providerToTargetMap[it] ?: emptyList()
        }.toSet()

        // 找出“原本在映射表中，但当前没有任何插件覆盖”的包名
        val orphanedTargets = allPossibleTargets - currentlyCoveredTargets
        val newAddedSet = _hookAddedState.value.toMutableSet()
        val newWhitelistSet = _hookWhitelistState.value.toMutableSet()

        var changed = false
        orphanedTargets.forEach { orphaned: String ->
            if (newAddedSet.remove(orphaned)) changed = true
            if (newWhitelistSet.remove(orphaned)) changed = true
        }

        if (changed) {
            saveHookLists(context, newWhitelistSet, newAddedSet)
        }
    }

    private fun saveHookLists(context: Context, whitelist: Set<String>, added: Set<String>) {
        _hookWhitelistState.value = whitelist
        _hookAddedState.value = added

        context.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE).edit {
            putStringSet(RootConstants.KEY_HOOK_WHITELIST, whitelist)
            putStringSet(RootConstants.KEY_HOOK_ADDED_LIST, added)
        }
        
        PrefsBridge.putStringSet(RootConstants.KEY_HOOK_WHITELIST, whitelist)
        PrefsBridge.putStringSet(RootConstants.KEY_HOOK_ADDED_LIST, added)
    }
}
