package com.pengxh.daily.app.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.graphics.withClip
import com.pengxh.daily.app.R
import com.pengxh.kt.lite.extensions.convertColor

/**
 * 根据Android自定义View的标准实践，即使没有自定义属性（attr），也应该实现4个构造函数
 * 但是可以使用Kotlin的 @JvmOverloads 注解简化
 *
 * | 构造函数 | 调用场景 |
 * |:----:|:----:|
 * | context | 代码中动态创建View |
 * | Context, AttributeSet? | XML布局中使用（最常见） |
 * | + defStyleAttr | 应用主题样式 |
 * | + defStyleRes | 特定样式资源 |
 *
 */
class WaveProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : View(context, attrs, defStyleAttr, defStyleRes) {

    // 波浪画笔
    private val wavePaint by lazy {
        Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
            color = DEFAULT_WAVE_COLOR
        }
    }

    // 底部波浪画笔（相位不同，颜色稍浅）
    private val wavePaint2 by lazy {
        Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
            color = DEFAULT_WAVE_COLOR2
        }
    }

    // 圆形边框画笔
    private val circlePaint by lazy {
        Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = R.color.ios_green.convertColor(context)
        }
    }

    // 中间进度文字
    private val textPaint by lazy {
        Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
            color = DEFAULT_TEXT_COLOR
            textSize = DEFAULT_TEXT_SIZE
            textAlign = Paint.Align.CENTER
        }
    }

    // 波浪路径
    private val wavePath by lazy { Path() }

    // 圆形裁剪路径
    private val circlePath by lazy { Path() }

    // 动画相关
    private var waveAnimator: ValueAnimator? = null
    private var waveOffset = 0f  // 波浪相位偏移

    // 进度相关
    private var progress = 0f  // 0-100
    private var targetProgress = 0f
    private var currentWaterLevel = 0f  // 当前水位（像素）

    // 视图尺寸
    private var viewWidth = 0
    private var viewHeight = 0
    private var centerX = 0f
    private var centerY = 0f
    private var radius = 0f

    // 波浪参数
    private var waveAmplitude = DEFAULT_WAVE_AMPLITUDE  // 波浪振幅
    private var waveLength = 0f  // 波长

    companion object {
        private const val DEFAULT_WAVE_COLOR = 0xFF2196F3.toInt()      // 蓝色
        private const val DEFAULT_WAVE_COLOR2 = 0xFF42A5F5.toInt()     // 浅蓝色
        private const val DEFAULT_TEXT_COLOR = Color.WHITE
        private const val DEFAULT_BORDER_WIDTH = 4f
        private const val DEFAULT_TEXT_SIZE = 22f
        private const val DEFAULT_WAVE_AMPLITUDE = 8f
        private const val ANIMATION_DURATION = 2000L  // 波浪动画周期（毫秒）
        private const val SEGMENTS_PER_WAVELENGTH = 24  // 每个波长的分段数（值越大波浪更平滑）
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewWidth = w
        viewHeight = h
        centerX = w / 2f
        centerY = h / 2f
        radius = (w.coerceAtMost(h) / 2f) - DEFAULT_BORDER_WIDTH
        waveLength = w * 1.5f  // 波长为视图宽度的1.5倍

        // 初始化圆形裁剪路径
        circlePath.reset()
        circlePath.addCircle(centerX, centerY, radius, Path.Direction.CW)

        updateWaterLevel()

        restartWaveAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 裁剪为圆形并保存画布状态
        canvas.withClip(circlePath) {
            drawWave(canvas)
        }

        // 绘制圆形边框
        canvas.drawCircle(centerX, centerY, radius, circlePaint)

        // 绘制进度文字
        drawProgressText(canvas)
    }

    private fun drawWave(canvas: Canvas) {
        // 绘制第二条波浪（底层，相位不同）
        drawWavePath(canvas, wavePaint2, waveOffset + Math.PI.toFloat() / 4)

        // 绘制第一条波浪（上层）
        drawWavePath(canvas, wavePaint, waveOffset)
    }

    /**
     * 绘制单条波浪路径
     * 使用二阶贝塞尔曲线模拟正弦波浪
     */
    private fun drawWavePath(canvas: Canvas, paint: Paint, offset: Float) {
        wavePath.reset()

        // 水位高度（从底部开始计算）
        val waterLevel = centerY + radius - (progress / 100f) * (radius * 2)

        // 波浪起始点（左侧超出视图）
        val startX = -waveLength + (offset % waveLength)

        val segmentWidth = waveLength / SEGMENTS_PER_WAVELENGTH

        var x = startX
        val currentY = waterLevel - waveAmplitude * getSinValue(x, offset)
        wavePath.moveTo(x, currentY)

        // 将正弦波分解为多个贝塞尔曲线段
        while (x < viewWidth + waveLength) {
            val nextX = x + segmentWidth
            val nextY = waterLevel - waveAmplitude * getSinValue(nextX, offset)

            // 计算控制点（使用线段中点）
            val controlX = x + segmentWidth / 2
            val controlY = waterLevel - waveAmplitude * getSinValue(controlX, offset)

            // 使用 quadTo 绘制二阶贝塞尔曲线
            wavePath.quadTo(controlX, controlY, nextX, nextY)

            x = nextX
        }

        // 闭合路径（填充到视图底部）
        wavePath.lineTo(viewWidth + waveLength, viewHeight.toFloat())
        wavePath.lineTo(-waveLength, viewHeight.toFloat())
        wavePath.close()

        canvas.drawPath(wavePath, paint)
    }

    /**
     * 计算正弦值（用于模拟波浪）
     */
    private fun getSinValue(x: Float, offset: Float): Float {
        return kotlin.math.sin((x + offset) * 2 * Math.PI.toFloat() / waveLength)
    }

    private fun drawProgressText(canvas: Canvas) {
        val progressText = "${progress.toInt()}%"
        // 文字垂直居中修正
        val fontMetrics = textPaint.fontMetrics
        val textY = centerY - (fontMetrics.top + fontMetrics.bottom) / 2
        canvas.drawText(progressText, centerX, textY, textPaint)
    }

    private fun updateWaterLevel() {
        currentWaterLevel = centerY + radius - (progress / 100f) * (radius * 2)
    }

    fun setProgress(targetProgress: Int) {
        this.targetProgress = targetProgress.coerceIn(0, 100).toFloat()

        // 使用属性动画平滑过渡
        val animator = ValueAnimator.ofFloat(progress, this.targetProgress)
        animator.duration = 1500L
        animator.addUpdateListener { animation ->
            progress = animation.animatedValue as Float
            updateWaterLevel()
            invalidate()
        }
        animator.start()
    }

    fun startWaveAnimation() {
        if (waveLength == 0f) {
            return
        }

        if (waveAnimator == null) {
            waveAnimator = ValueAnimator.ofFloat(0f, waveLength).apply {
                duration = ANIMATION_DURATION
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                interpolator = LinearInterpolator()
                addUpdateListener { animation ->
                    waveOffset = animation.animatedValue as Float
                    invalidate()
                }
            }
        }
        waveAnimator?.start()
    }

    private fun restartWaveAnimation() {
        // 先取消旧动画
        waveAnimator?.cancel()
        waveAnimator = null

        // 重新创建并启动动画
        startWaveAnimation()
    }

    fun stopWaveAnimation() {
        waveAnimator?.cancel()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startWaveAnimation()
    }

    override fun onDetachedFromWindow() {
        stopWaveAnimation()
        super.onDetachedFromWindow()
    }
}