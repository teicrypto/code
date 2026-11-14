package com.teicrypto.syswidget

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Thành phần giao diện dùng chung, kiểu Material tối giống Shizuku. */
object UiKit {
    const val BG = "#0F1420"
    const val CARD = "#151C28"
    const val ACCENT = "#A8C8FF"
    const val ON_ACCENT = "#0B2A5B"

    fun dp(a: Activity, v: Int) = (v * a.resources.displayMetrics.density).toInt()

    fun round(a: Activity, color: String, r: Int) = GradientDrawable().apply {
        setColor(Color.parseColor(color))
        cornerRadius = dp(a, r).toFloat()
    }

    fun card(a: Activity): LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        background = round(a, CARD, 24)
        val p = dp(a, 24)
        setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(a, 12) }
    }

    fun icon(a: Activity, glyph: String): TextView = TextView(a).apply {
        text = glyph
        textSize = 24f
        setTextColor(Color.parseColor("#D6E3FF"))
        gravity = Gravity.CENTER
        background = round(a, "#0B4A8F", 30)
        layoutParams = LinearLayout.LayoutParams(dp(a, 60), dp(a, 60))
    }

    fun title(a: Activity, text: String): TextView = TextView(a).apply {
        this.text = text
        textSize = 20f
        setTextColor(Color.WHITE)
    }

    fun body(a: Activity, text: String): TextView = TextView(a).apply {
        this.text = text
        textSize = 16f
        setTextColor(Color.parseColor("#C9D1D9"))
        setLineSpacing(0f, 1.2f)
    }

    fun pill(a: Activity, label: String, glyph: String, onClick: () -> Unit): TextView = TextView(a).apply {
        text = "$glyph   $label"
        textSize = 16f
        setTextColor(Color.parseColor(ON_ACCENT))
        background = round(a, ACCENT, 30)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(a, 24), dp(a, 16), dp(a, 28), dp(a, 16))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(a, 12) }
        setOnClickListener { onClick() }
    }

    fun spacer(a: Activity, h: Int) = View(a).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(a, h))
    }

    fun bold(t: TextView): TextView { t.setTypeface(null, Typeface.NORMAL); return t }
}
