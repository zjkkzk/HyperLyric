package com.lidesheng.hyperlyric.service.source

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.toColorInt
import com.lidesheng.hyperlyric.common.image.AlbumImageHelper
import com.lidesheng.hyperlyric.common.lyric.LyricSplitter
import com.lidesheng.hyperlyric.lyric.DynamicLyricData
import com.lidesheng.hyperlyric.lyric.LrcLine
import com.lidesheng.hyperlyric.lyric.LyricProviderFactory
import com.lidesheng.hyperlyric.lyric.LyricSearchParams
import com.lidesheng.hyperlyric.service.NotificationPresenter
import com.lidesheng.hyperlyric.utils.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import com.lidesheng.hyperlyric.root.utils.Constants as RootConstants
import com.lidesheng.hyperlyric.service.Constants as ServiceConstants
import com.lidesheng.hyperlyric.ui.utils.Constants as UIConstants

class AppLyricSink(
    private val context: Context,
    private val scope: CoroutineScope,
    private val lyricSplitter: LyricSplitter,
    private val notificationPresenter: NotificationPresenter
) {
    private val islandBitmapHeight = 128

    private var currentSongIdentifier = ""
    private var cachedNotificationEnabled = false
    private var lastPermissionCheckTime = 0L
    private val permissionCheckInterval = 30_000L

    private val textPaint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.LEFT
            textSize = 100f
            val rawHeight = fontMetrics.descent - fontMetrics.ascent
            textSize = 100f * (islandBitmapHeight.toFloat() / rawHeight)
        }
    }

    private val lyricProvider by lazy { LyricProviderFactory.create(context) }

    private var tickerJob: Job? = null
    private var progressJob: Job? = null
    private var isCurrentlyPlaying: Boolean = false
    private var currentLyricLines: List<LrcLine>? = null
    private var lastDispatchedLrc: String = ""
    private var currentSyncData: SyncData? = null

    private var lastDispatchedIslandLeft = ""
    private var lastDispatchedIsPlaying = false
    private var lastDispatchedShowAlbum = false

    fun startCollecting(lyricUpdateFlow: Flow<SyncData>) {
        scope.launch {
            lyricUpdateFlow.debounce(200.milliseconds).collectLatest { data -> processSyncData(data) }
        }
    }

    fun stop() {
        tickerJob?.cancel()
        progressJob?.cancel()
    }

    fun clearState() {
        currentSongIdentifier = ""
        isCurrentlyPlaying = false
        DynamicLyricData.updateLoadingAlbumArt(false)
        DynamicLyricData.updateFetchingLyrics(false)
        DynamicLyricData.updateAnchor(0L, false)
        DynamicLyricData.updateRightTitles(" ", " ", " ", " ", 0L, false, "")
        notificationPresenter.clearNotifications()
    }

    private suspend fun processSyncData(data: SyncData) {
        val sp = context.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE)
        val enableDynamicIsland = sp.getBoolean(RootConstants.KEY_HOOK_ENABLE_DYNAMIC_ISLAND, RootConstants.DEFAULT_HOOK_ENABLE_DYNAMIC_ISLAND)
        val pauseListening = !enableDynamicIsland
        val isWhitelisted = DynamicLyricData.whitelistState.value.contains(data.currentPackageName)
        LogManager.d("AppLyricSink", "processSyncData: 超级岛开关=$enableDynamicIsland, 白名单通过=$isWhitelisted, pkg=${data.currentPackageName}")

        if (pauseListening || !isWhitelisted) {
            isCurrentlyPlaying = false
            DynamicLyricData.updateAnchor(data.position, false)
            DynamicLyricData.updateRightTitles(
                islandText = " ",
                notificationText = " ",
                newSongLyric = " ",
                newSongInfo = " ",
                newDuration = 0L,
                newIsPlaying = false,
                newPackageName = data.currentPackageName
            )
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastPermissionCheckTime > permissionCheckInterval) {
            cachedNotificationEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
            lastPermissionCheckTime = now
        }
        if (!cachedNotificationEnabled) {
            LogManager.w("AppLyricSink", "通知权限未授予，跳过处理")
            return
        }

        DynamicLyricData.updateAnchor(data.position, data.isPlaying)

        if (data.isNewSong) {
            val progressColorEnabled = sp.getBoolean(ServiceConstants.KEY_NOTIFICATION_PROGRESS_COLOR, ServiceConstants.DEFAULT_NOTIFICATION_PROGRESS_COLOR)
            val highlightColorEnabled = sp.getBoolean(ServiceConstants.KEY_NOTIFICATION_HIGHLIGHT_COLOR, ServiceConstants.DEFAULT_NOTIFICATION_HIGHLIGHT_COLOR)
            val songInfoHighlightColorEnabled = sp.getBoolean(ServiceConstants.KEY_NOTIFICATION_SONG_INFO_HIGHLIGHT_COLOR, ServiceConstants.DEFAULT_NOTIFICATION_SONG_INFO_HIGHLIGHT_COLOR)

            val shouldExtract = progressColorEnabled || highlightColorEnabled || songInfoHighlightColorEnabled

            val colors = if (shouldExtract) {
                AlbumImageHelper.extractColors(data.albumBitmap)
            } else {
                val default = "#E0E0E0".toColorInt()
                AlbumImageHelper.ExtractedColors(default, default)
            }
            LogManager.d("AppLyricSink", "正在提取封面取色: 主色=${String.format("#%06X", 0xFFFFFF and colors.main)}, 次色=${String.format("#%06X", 0xFFFFFF and colors.secondary)}")
            DynamicLyricData.updateColor(colors.main, colors.secondary)
        }

        isCurrentlyPlaying = data.isPlaying
        currentSyncData = data

        if (data.isNewSong) {
            currentSongIdentifier = data.identifier
            lastDispatchedLrc = ""
            currentLyricLines = null

            if (sp.getBoolean(ServiceConstants.KEY_ONLINE_LYRIC_ENABLED, ServiceConstants.DEFAULT_ONLINE_LYRIC_ENABLED)) {
                DynamicLyricData.updateFetchingLyrics(true)
                val lines = lyricProvider.fetchLyrics(
                    LyricSearchParams(
                        title = data.identityTitle,
                        artist = data.identityArtist,
                        album = data.identityAlbum,
                        packageName = data.currentPackageName,
                        duration = data.duration
                    )
                )
                DynamicLyricData.updateFetchingLyrics(false)
                LogManager.d("AppLyricSink", "在线歌词获取完成: 行数=${lines?.size ?: 0}, 身份守卫=${currentSongIdentifier == data.identifier}")

                if (!lines.isNullOrEmpty()) {
                    if (currentSongIdentifier == data.identifier) {
                        currentLyricLines = lines
                        launchLyricScheduler()
                        launchProgressScheduler()
                    }
                } else {
                    if (currentSongIdentifier == data.identifier) {
                        dispatchLyricContent(data.dynamicTitle, data)
                        launchProgressScheduler()
                    }
                }
            } else {
                dispatchLyricContent(data.dynamicTitle, data)
            }
            notificationPresenter.updateState(DynamicLyricData.currentState, force = true)
        } else {
            if (currentLyricLines != null) launchLyricScheduler() else dispatchLyricContent(data.dynamicTitle, data)
            launchProgressScheduler()
        }
    }

    private fun launchLyricScheduler() {
        tickerJob?.cancel()
        val lines = currentLyricLines ?: return
        LogManager.d("AppLyricSink", "启动歌词调度器: 行数=${lines.size}")

        tickerJob = scope.launch {
            while (true) {
                val data = currentSyncData ?: break
                val currentPos = with(DynamicLyricData) { musicState.value.getCurrentPosition() }

                val currentLineIndex = lines.indexOfLast { it.startTimeMs <= currentPos }
                val targetLine = if (currentLineIndex != -1) lines[currentLineIndex].content else data.dynamicTitle

                if (targetLine != lastDispatchedLrc) {
                    lastDispatchedLrc = targetLine
                    dispatchLyricContent(targetLine, data)
                }

                if (!data.isPlaying) break
                delay(150.milliseconds)
            }
        }
    }

    private fun launchProgressScheduler() {
        progressJob?.cancel()
        val sp = context.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE)
        val showProgress = sp.getBoolean(ServiceConstants.KEY_NOTIFICATION_SHOW_PROGRESS, ServiceConstants.DEFAULT_NOTIFICATION_SHOW_PROGRESS)
        if (!showProgress) return
        LogManager.d("AppLyricSink", "启动进度调度器")

        progressJob = scope.launch {
            var lastPercent = -1
            while (true) {
                val data = currentSyncData ?: break
                val duration = data.duration
                if (!data.isPlaying || duration <= 1000) break

                val currentPos = with(DynamicLyricData) { musicState.value.getCurrentPosition() }
                val currentPercent = ((currentPos.toDouble() / duration.toDouble()) * 100).toInt().coerceIn(0, 100)

                if (currentPercent != lastPercent) {
                    DynamicLyricData.emitProgress(currentPercent.toFloat())
                    lastPercent = currentPercent
                }

                if (currentPercent >= 100) break
                delay(1000.milliseconds)
            }
        }
    }

    private fun dispatchLyricContent(targetText: String, data: SyncData) {
        val songLyric = if (currentLyricLines != null) targetText else data.dynamicTitle
        val pref = context.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE)

        val islandLeftIconStyle = pref.getInt(ServiceConstants.KEY_ISLAND_LEFT_ICON, ServiceConstants.DEFAULT_ISLAND_LEFT_ICON)
        val showIslandLeftAlbum = islandLeftIconStyle in 0..2
        val showAlbumArt = pref.getBoolean(ServiceConstants.KEY_NOTIFICATION_ALBUM, ServiceConstants.DEFAULT_NOTIFICATION_ALBUM)
        val notificationType = pref.getInt(ServiceConstants.KEY_NOTIFICATION_TYPE, ServiceConstants.DEFAULT_NOTIFICATION_TYPE)
        val disableLyricSplit = pref.getBoolean(ServiceConstants.KEY_NOTIFICATION_ISLAND_DISABLE_LYRIC_SPLIT, ServiceConstants.DEFAULT_NOTIFICATION_ISLAND_DISABLE_LYRIC_SPLIT) || notificationType == 0
        val limitMaxWidth = pref.getBoolean(ServiceConstants.KEY_NOTIFICATION_ISLAND_LIMIT_WIDTH, ServiceConstants.DEFAULT_NOTIFICATION_ISLAND_LIMIT_WIDTH)
        val maxWidth = pref.getInt(ServiceConstants.KEY_NOTIFICATION_ISLAND_MAX_WIDTH, ServiceConstants.DEFAULT_NOTIFICATION_ISLAND_MAX_WIDTH)

        val splitResult = lyricSplitter.split(
            songLyric,
            LyricSplitter.Config(
                showIslandLeftAlbum = showIslandLeftAlbum,
                showAlbumArt = showAlbumArt,
                disableLyricSplit = disableLyricSplit,
                limitMaxWidth = limitMaxWidth,
                maxWidth = maxWidth
            )
        )

        val finalIslandLeft = splitResult.islandLeft
        val finalIslandRight = splitResult.islandRight
        val finalNotificationLeft = splitResult.notificationLeft
        val finalNotificationRight = splitResult.notificationRight

        val titleStyle = pref.getInt(ServiceConstants.KEY_NOTIFICATION_TITLE_STYLE, ServiceConstants.DEFAULT_NOTIFICATION_TITLE_STYLE)
        val songInfo = when (titleStyle) {
            0 -> ""
            1 -> data.identityTitle
            2 -> data.identityArtist
            3 -> data.identityAlbum
            4 -> "${data.identityTitle} - ${data.identityArtist}"
            5 -> "${data.identityArtist} - ${data.identityTitle}"
            6 -> "${data.identityArtist} - ${data.identityAlbum}"
            else -> ""
        }
        LogManager.d("AppLyricSink", "分发歌词: islandLeft=$finalIslandLeft, islandRight=$finalIslandRight, songInfo=$songInfo")

        val shouldUpdateBitmap = data.isNewSong ||
                                finalIslandLeft != lastDispatchedIslandLeft ||
                                data.isPlaying != lastDispatchedIsPlaying ||
                                showIslandLeftAlbum != lastDispatchedShowAlbum

        if (shouldUpdateBitmap) {
            lastDispatchedIslandLeft = finalIslandLeft
            lastDispatchedIsPlaying = data.isPlaying
            lastDispatchedShowAlbum = showIslandLeftAlbum
        }

        DynamicLyricData.updateBitmaps(data.albumBitmap, data.notificationAlbumBitmap, data.notificationAlbumBitmapCircular)
        DynamicLyricData.updateIslandLeftIconStyle(islandLeftIconStyle)
        DynamicLyricData.updateLeftTitles(finalIslandLeft, finalNotificationLeft)
        DynamicLyricData.updateRightTitles(finalIslandRight,
            finalNotificationRight, songLyric, songInfo, data.duration, data.isPlaying, data.currentPackageName, showIslandLeftAlbum)
    }
}
