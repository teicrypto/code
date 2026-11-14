package com.teicrypto.syswidget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.view.View

/**
 * Biểu đồ đường tự vẽ, giữ lịch sử ~3 phút (90 mẫu x 2 giây).
 * fixedMax != null: trục cố định [fixedMin, fixedMax] (vd phần trăm 0..100, nhiệt độ 20..80).
 * fixedMax == null: tự co giãn theo giá trị lớn nhất.
 * compact = true: kiểu mini (chữ nhỏ, viền hẹp) dùng trong cửa sổ nổi.
 */
class LineChartView(
    context: Context,
    private val title: String,
    private val color: Int,
    private val fixedMax: Float?,
    private val fixedMin: Float = 0f,
    private val compact: Boolean = false,
    private val bare: Boolean = false   // kiểu OSD: không chữ, không lưới, chỉ vẽ đường
) : View(context) {

    private val data = ArrayDeque<Float>()
    private val maxPoints = 90
    private var label = ""
    private val d = resources.displayMetrics.density

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.parseColor(if (bare) "#66000000" else "#1A1F26") }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = Color.parseColor("#2A2F36")
        strokeWidth = 1f
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = (if (compact) 1.5f else 2f) * d
        strokeJoin = Paint.Join.ROUND
        this.color = this@LineChartView.color
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        this.color = (this@LineChartView.color and 0x00FFFFFF) or (0x40 shl 24)
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = Color.parseColor("#8A94A0")
        textSize = (if (compact) 9f else 11f) * d
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = this@LineChartView.color
        textSize = (if (compact) 9f else 11f) * d
        textAlign = Paint.Align.RIGHT
        typeface = Typeface.DEFAULT_BOLD
    }

    fun add(value: Float, text: String) {
        data.addLast(value)
        while (data.size > maxPoints) data.removeFirst()
        label = text
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val radius = (if (bare) 3 else if (compact) 8 else 12) * d
        canvas.drawRoundRect(0f, 0f, w, h, radius, radius, bgPaint)

        val pad = (if (bare) 2 else if (compact) 6 else 10) * d
        val left = pad
        val right = w - pad
        val top = if (bare) pad else (if (compact) 20 else 26) * d
        val bottom = h - pad
        val textY = (if (compact) 14 else 17) * d

        if (!bare) {
            for (i in 0..3) {
                val y = top + (bottom - top) * i / 3f
                canvas.drawLine(left, y, right, y, gridPaint)
            }
            canvas.drawText(title, left, textY, titlePaint)
            canvas.drawText(label, right, textY, valuePaint)
        }

        if (data.size < 2) return

        val maxV = fixedMax ?: maxOf((data.maxOrNull() ?: 0f) * 1.2f, fixedMin + 1f)
        val range = maxOf(maxV - fixedMin, 1f)
        val step = (right - left) / (maxPoints - 1)
        val n = data.size
        val line = Path()
        val fill = Path()

        for ((i, v) in data.withIndex()) {
            val x = right - (n - 1 - i) * step
            val y = bottom - ((v - fixedMin).coerceIn(0f, range) / range) * (bottom - top)
            if (i == 0) {
                line.moveTo(x, y)
                fill.moveTo(x, bottom)
                fill.lineTo(x, y)
            } else {
                line.lineTo(x, y)
                fill.lineTo(x, y)
            }
        }
        fill.lineTo(right, bottom)
        fill.close()

        canvas.drawPath(fill, fillPaint)
        canvas.drawPath(line, linePaint)
    }
}
