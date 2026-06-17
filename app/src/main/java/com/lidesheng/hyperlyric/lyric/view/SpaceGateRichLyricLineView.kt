/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.lidesheng.hyperlyric.lyric.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.Gravity
import android.widget.LinearLayout
import androidx.core.graphics.withScale
import androidx.core.view.forEach
import com.lidesheng.hyperlyric.lyric.model.interfaces.IRichLyricLine
import com.lidesheng.hyperlyric.lyric.view.line.SpaceGateLyricLineView

@SuppressLint("ViewConstructor")
class SpaceGateRichLyricLineView(
    context: Context,
    var displayTranslation: Boolean = true,
    var enableRelativeProgress: Boolean = false,
    var enableRelativeProgressHighlight: Boolean = false,
    var displayRoma: Boolean = true
) : LinearLayout(context), UpdatableColor {

    val main = SpaceGateLyricLineView(context)
    val secondary = SpaceGateLyricLineView(context).apply { visibleIfChanged = false }

    var alwaysShowSecondary = false

    var renderScale = 1.0f
        private set

    private val assembler = LyricLineAssembler(
        displayTranslation, displayRoma,
        enableRelativeProgress, enableRelativeProgressHighlight
    )

    private var animationTransition = false
    private var pendingLine: IRichLyricLine? = null
    private var pendingPosition: Long? = null
    private var requestMarquee = false

    var rawLine: IRichLyricLine? = null

    var line: IRichLyricLine?
        get() = rawLine
        set(value) {
            rawLine = value
            requestMarquee = false
            if (animationTransition) {
                pendingLine = value
            } else {
                refreshLines()
            }
        }

    init {
        orientation = VERTICAL
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        addView(main, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(secondary, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        updateLayoutTransitionX()
    }

    fun setSpaceGateConfig(isRightSide: Boolean, sibling: SpaceGateRichLyricLineView?) {
        main.isRightSide = isRightSide
        main.siblingView = sibling?.main
        secondary.isRightSide = isRightSide
        secondary.siblingView = sibling?.secondary
    }

    fun reset() {
        line = null
        renderScale = 1.0f
        animationTransition = false
        pendingLine = null
        pendingPosition = null
        alwaysShowSecondary = false
        refreshLines()
    }

    fun beginAnimationTransition() {
        animationTransition = true
    }

    fun endAnimationTransition() {
        animationTransition = false
        if (pendingLine != null) {
            refreshLines()
            pendingPosition?.let { setPosition(it) }
        }
        pendingLine = null
        pendingPosition = null
    }

    fun setTransitionConfig(config: String?) {
        updateLayoutTransitionX(config)
    }

    fun notifyLineChanged() = refreshLines()

    fun seekTo(position: Long) {
        if (animationTransition) {
            pendingPosition = position; return
        }
        main.seekTo(position)
        secondary.seekTo(position)
    }

    fun setPosition(position: Long) {
        if (animationTransition) {
            pendingPosition = position; return
        }
        main.updatePosition(position)
        secondary.updatePosition(position)
    }

    fun requestStartMarquee() {
        requestMarquee = true
        main.requestScroll()
        secondary.requestScroll()
    }

    fun setMetadataMarqueeConfig(
        speed: Float, initialDelay: Int, loopDelay: Int,
        repeatCount: Int, stopAtEnd: Boolean
    ) {
        listOf(main, secondary).forEach {
            it.setMarqueeSpeed(speed)
            it.setMarqueeInitialDelay(initialDelay)
            it.setMarqueeLoopDelay(loopDelay)
            it.setMarqueeRepeatCount(repeatCount)
            it.setMarqueeStopAtEnd(stopAtEnd)
        }
    }

    fun setStyle(style: LyricViewStyle) {
        assembler.updateFlags(
            displayTranslation, displayRoma,
            style.primary.relativeProgress, style.primary.relativeHighlight
        )
        enableRelativeProgress = style.primary.relativeProgress
        enableRelativeProgressHighlight = style.primary.relativeHighlight

        setTransitionConfig(style.transitionConfig)

        applyLineStyle(
            main,
            style.primary,
            style.highlight,
            style.marquee,
            style.gradient,
            style.fadingEdge,
            style.wordMotion,
            style.centerIfPossible
        )
        applyLineStyle(
            secondary,
            style.secondary,
            style.highlight,
            style.marquee,
            style.gradient,
            style.fadingEdge,
            style.wordMotion,
            style.centerIfPossible
        )
    }

    override fun updateColor(primary: IntArray, background: IntArray, highlight: IntArray) {
        forEach { if (it is UpdatableColor) it.updateColor(primary, background, highlight) }
    }

    fun setMainLyricPlayListener(listener: LyricPlayListener?) {
        main.playListener = listener
    }

    fun setSecondaryLyricPlayListener(listener: LyricPlayListener?) {
        secondary.playListener = listener
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        if (renderScale != 1.0f && renderScale > 0) {
            val origW = MeasureSpec.getSize(wSpec)
            val mode = MeasureSpec.getMode(wSpec)
            val compW = (origW / renderScale).toInt()
            super.onMeasure(MeasureSpec.makeMeasureSpec(compW, mode), hSpec)
            setMeasuredDimension(origW, measuredHeight)
        } else {
            super.onMeasure(wSpec, hSpec)
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (renderScale != 1.0f) {
            canvas.withScale(renderScale, renderScale, 0f, height / 2f) {
                super.dispatchDraw(this)
            }
        } else {
            super.dispatchDraw(canvas)
        }
    }

    fun setRenderScale(scale: Float) {
        if (renderScale != scale) {
            renderScale = scale
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        reset()
    }

    private var oldLine: IRichLyricLine? = null

    private fun refreshLines() {
        if (oldLine === line && line.isTitleLine()) return
        oldLine = line

        assembler.updateFlags(displayTranslation, displayRoma, enableRelativeProgress, enableRelativeProgressHighlight)
        val mainResult = assembler.buildMain(line)
        main.setLyric(mainResult.line)
        main.isScrollOnly = mainResult.isScrollOnly

        val secResult = assembler.buildSecondary(line)
        alwaysShowSecondary = secResult.alwaysShow
        secondary.visibleIfChanged = secResult.alwaysShow
        secondary.setLyric(secResult.line)
        secondary.isScrollOnly = secResult.isScrollOnly

        if (requestMarquee) requestStartMarquee()
    }

    private fun applyLineStyle(
        view: SpaceGateLyricLineView, text: TextLook, highlight: Highlight,
        marquee: Marquee, gradient: Boolean, fadingEdge: Int, wordMotion: WordMotion,
        centerIfPossible: Boolean
    ) {
        view.wordMotion = wordMotion
        view.configureWith(text, highlight, marquee, gradient, fadingEdge, centerIfPossible)
    }

    private fun updateLayoutTransitionX(config: String? = LayoutTransitionX.TRANSITION_CONFIG_SMOOTH) {
        layoutTransition = LayoutTransitionX(config).apply { setAnimateParentHierarchy(true) }
    }
}


