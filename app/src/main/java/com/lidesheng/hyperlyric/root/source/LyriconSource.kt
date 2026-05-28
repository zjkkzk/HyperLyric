package com.lidesheng.hyperlyric.root.source

import android.app.Application
import android.content.SharedPreferences
import com.lidesheng.hyperlyric.lyric.source.LyricSink
import com.lidesheng.hyperlyric.lyric.source.LyricSource
import com.lidesheng.hyperlyric.root.HookIslandLyric
import com.lidesheng.hyperlyric.root.HookIslandSpaceGateLyric
import com.lidesheng.hyperlyric.root.LyriconDataBridge
import com.lidesheng.hyperlyric.root.aitrans.AITranslator
import com.lidesheng.hyperlyric.root.utils.HookLogger
import io.github.proify.lyricon.app.bridge.AppBridgeConstants
import io.github.proify.lyricon.app.bridge.LyriconBridge
import io.github.proify.lyricon.central.BridgeCentral
import io.github.proify.lyricon.central.provider.player.ActivePlayerDispatcher
import io.github.proify.lyricon.central.provider.player.ActivePlayerListener
import io.github.proify.lyricon.central.util.ScreenStateMonitor
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.ProviderInfo

class LyriconSource : LyricSource {

    override val id = "lyricon"
    override val displayName = "Lyricon (逐字歌词)"

    private var sink: LyricSink? = null
    private var app: Application? = null
    private var prefs: SharedPreferences? = null
    private var activeMode: Int = 0
    private var renderer: IslandRenderer? = null

    override fun isAvailable(): Boolean = true

    override fun start(sink: LyricSink) {
        this.sink = sink
        val application = app ?: run {
            HookLogger.w("LyriconSource", "Application 未初始化，无法启动")
            return
        }

        initializeLyricon(application)
        registerActivePlayerListener()
        HookLogger.i("LyriconSource", "Lyricon 数据源已启动")
    }

    override fun stop() {
        sink?.onStop()
        sink = null
        HookLogger.i("LyriconSource", "Lyricon 数据源已停止")
    }

    fun initialize(app: Application, prefs: SharedPreferences, activeMode: Int) {
        this.app = app
        this.prefs = prefs
        this.activeMode = activeMode
        this.renderer = if (activeMode == 1) HookIslandSpaceGateLyric else HookIslandLyric

        LyriconDataBridge.onAiTranslationComplete = {
            renderer?.refreshActiveIsland()
        }
    }

    private fun getRenderer(): IslandRenderer = renderer ?: if (activeMode == 1) HookIslandSpaceGateLyric else HookIslandLyric

    private fun initializeLyricon(app: Application) {
        ScreenStateMonitor.initialize(app)
        BridgeCentral.initialize(app)
        BridgeCentral.sendBootCompleted()
        AITranslator.init(app)
        initBridgeRouting(app)
    }

    private fun initBridgeRouting(app: Application) {
        LyriconBridge.routing(app) {
            onCommand(AppBridgeConstants.REQUEST_UPDATE_LYRIC_STYLE) {
                HookLogger.d("LyriconSource", "Bridge : 接收到样式更新请求")
                getRenderer().refreshActiveIsland()
            }
            onCommand("com.lidesheng.hyperlyric.REFRESH_ISLAND") {
                HookLogger.d("LyriconSource", "Bridge : 接收到超级岛刷新请求")
                getRenderer().refreshActiveIsland()
            }
            onCommand("com.lidesheng.hyperlyric.UPDATE_LYRIC_ANIM") {
                HookLogger.d("LyriconSource", "Bridge : 接收到歌词动画刷新请求")
                getRenderer().refreshActiveIsland()
            }
        }
    }

    private fun registerActivePlayerListener() {
        ActivePlayerDispatcher.addActivePlayerListener(object : ActivePlayerListener {
            override fun onActiveProviderChanged(providerInfo: ProviderInfo?) {
                sink?.onStop()
                LyriconDataBridge.activePackageName = providerInfo?.playerPackageName
            }

            override fun onSongChanged(song: Song?) {
                LyriconDataBridge.updateSong(song)
                sink?.onSongChanged(song)
                getRenderer().refreshActiveIsland()
            }

            override fun onPlaybackStateChanged(isPlaying: Boolean) {
                LyriconDataBridge.isPlaying = isPlaying
                sink?.onPlaybackStateChanged(isPlaying)
                getRenderer().onPlaybackStateChanged(isPlaying)
            }

            override fun onPositionChanged(position: Long) {
                val lyricChanged = LyriconDataBridge.updatePosition(position)
                if (lyricChanged) {
                    getRenderer().updateLyricLine()
                }
                sink?.onPositionChanged(position)
                getRenderer().updatePosition(position)
            }

            override fun onSeekTo(position: Long) {}

            override fun onSendText(text: String?) {
                LyriconDataBridge.updateLyric(text)
                sink?.onPlainText(text)
                getRenderer().updateLyricLine()
            }

            override fun onDisplayTranslationChanged(isDisplayTranslation: Boolean) {
                LyriconDataBridge.isDisplayTranslation = isDisplayTranslation
                getRenderer().refreshActiveIsland()
            }

            override fun onDisplayRomaChanged(displayRoma: Boolean) {
                LyriconDataBridge.isDisplayRoma = displayRoma
                getRenderer().refreshActiveIsland()
            }
        })
    }
}
