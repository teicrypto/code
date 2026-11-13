package com.teicrypto.syswidget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon

/** Vẽ chữ (vd "42%", "1.2M", "38°") thành icon nhỏ để hiện trên thanh trạng thái. */
object StatusIcon {
    fun make(text: String): Icon {
        val size = 96
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            textSize = 80f
        }
        while (p.measureText(text) > size - 4 && p.textSize > 20f) p.textSize -= 4f
        val fm = p.fontMetrics
        val y = size / 2f - (fm.ascent + fm.descent) / 2f
        canvas.drawText(text, size / 2f, y, p)
        return Icon.createWithBitmap(bmp)
    }
}
