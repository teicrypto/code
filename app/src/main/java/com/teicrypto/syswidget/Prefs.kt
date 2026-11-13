package com.teicrypto.syswidget

import android.content.Context
import android.content.SharedPreferences

/** Cài đặt người dùng (lưu bằng SharedPreferences). */
object Prefs {
    const val LANG = "lang"                  // "en" | "vi" | "zh"
    const val MINI_CHARTS = "mini_charts"    // biểu đồ mini trong popup
    const val BUBBLE_EXTRA = "bubble_extra"  // mạng + nhiệt độ ở bong bóng
    const val STATUS_BAR = "status_bar"      // hiện trên thanh trạng thái
    const val STATUS_METRIC = "status_metric" // "cpu" | "ram" | "net" | "temp"

    fun sp(c: Context): SharedPreferences {
        val app = c.applicationContext ?: c
        return app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    }

    fun lang(c: Context): String = sp(c).getString(LANG, "vi") ?: "vi"
    fun miniCharts(c: Context): Boolean = sp(c).getBoolean(MINI_CHARTS, true)
    fun bubbleExtra(c: Context): Boolean = sp(c).getBoolean(BUBBLE_EXTRA, true)
    fun statusBar(c: Context): Boolean = sp(c).getBoolean(STATUS_BAR, false)
    fun statusMetric(c: Context): String = sp(c).getString(STATUS_METRIC, "cpu") ?: "cpu"
}
