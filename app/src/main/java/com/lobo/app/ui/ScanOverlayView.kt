package com.lobo.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/** 扫码取景框覆盖层：中间挖空 + 四角描边 + 四周压暗。 */
class ScanOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val dimPaint = Paint().apply { color = 0xB0000000.toInt(); style = Paint.Style.FILL }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }
    private val frame = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val size = 240f * density
        val left = (width - size) / 2f
        val top = (height - size) / 2f - 40f * density
        frame.set(left, top, left + size, top + size)

        canvas.drawRect(0f, 0f, width.toFloat(), frame.top, dimPaint)
        canvas.drawRect(0f, frame.bottom, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawRect(0f, frame.top, frame.left, frame.bottom, dimPaint)
        canvas.drawRect(frame.right, frame.top, width.toFloat(), frame.bottom, dimPaint)

        val corner = 26f * density
        val radius = 16f * density
        // 左上
        canvas.drawRoundRect(frame.left, frame.top, frame.left + corner, frame.top + corner, radius, radius, cornerPaint)
        canvas.drawLine(frame.left, frame.top + corner, frame.left, frame.top + size / 3f, cornerPaint)
        canvas.drawLine(frame.left + corner, frame.top, frame.left + size / 3f, frame.top, cornerPaint)
        // 右上
        canvas.drawRoundRect(frame.right - corner, frame.top, frame.right, frame.top + corner, radius, radius, cornerPaint)
        canvas.drawLine(frame.right, frame.top + corner, frame.right, frame.top + size / 3f, cornerPaint)
        canvas.drawLine(frame.right - corner, frame.top, frame.right - size / 3f, frame.top, cornerPaint)
        // 左下
        canvas.drawRoundRect(frame.left, frame.bottom - corner, frame.left + corner, frame.bottom, radius, radius, cornerPaint)
        canvas.drawLine(frame.left, frame.bottom - corner, frame.left, frame.bottom - size / 3f, cornerPaint)
        canvas.drawLine(frame.left + corner, frame.bottom, frame.left + size / 3f, frame.bottom, cornerPaint)
        // 右下
        canvas.drawRoundRect(frame.right - corner, frame.bottom - corner, frame.right, frame.bottom, radius, radius, cornerPaint)
        canvas.drawLine(frame.right, frame.bottom - corner, frame.right, frame.bottom - size / 3f, cornerPaint)
        canvas.drawLine(frame.right - corner, frame.bottom, frame.right - size / 3f, frame.bottom, cornerPaint)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val measured = min(measuredWidth, measuredHeight)
        if (measured > 0) {
            // 保证取景框在竖屏下有足够空间
            setMeasuredDimension(measuredWidth, measuredHeight)
        }
    }
}
