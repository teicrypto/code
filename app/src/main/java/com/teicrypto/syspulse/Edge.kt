package com.teicrypto.syspulse

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.WindowInsets

/**
 * Android 15+ (targetSdk 35/36) bắt buộc giao diện tràn viền: nội dung nằm dưới thanh trạng thái/điều hướng.
 * Hàm này đệm nội dung theo kích thước các thanh hệ thống để không bị che.
 */
object Edge {
    @Suppress("DEPRECATION")
    fun apply(a: Activity) {
        a.window.setBackgroundDrawable(ColorDrawable(Color.parseColor("#111418")))
        val c = a.findViewById<View>(android.R.id.content) ?: return
        c.setOnApplyWindowInsetsListener { v, i ->
            v.setPadding(i.systemWindowInsetLeft, i.systemWindowInsetTop, i.systemWindowInsetRight, i.systemWindowInsetBottom)
            i.consumeSystemWindowInsets()
        }
        c.requestApplyInsets()
    }
}
