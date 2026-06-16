package com.junerver.videorecorder

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * 自定义圆形进度条组件，用于录制时的进度指示
 * 替代 MaterialProgressBar 库，减少外部依赖
 */
class CircleProgressBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
    }

    private val rect = RectF()
    var progress: Int = 0
        set(value) {
            field = value.coerceIn(0, 100)
            invalidate()
        }

    var max: Int = 100

    var isIndeterminate: Boolean = false

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val width = width.toFloat()
        val height = height.toFloat()
        val size = minOf(width, height)
        val strokeWidth = backgroundPaint.strokeWidth
        val radius = (size - strokeWidth) / 2

        rect.set(
            strokeWidth / 2,
            strokeWidth / 2,
            strokeWidth / 2 + radius * 2,
            strokeWidth / 2 + radius * 2
        )

        // 绘制背景圆环
        canvas.drawArc(rect, 0f, 360f, false, backgroundPaint)

        // 绘制进度弧
        val sweepAngle = 360f * progress / max
        canvas.drawArc(rect, -90f, sweepAngle, false, progressPaint)
    }
}