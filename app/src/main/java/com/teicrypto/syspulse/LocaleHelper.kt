package com.teicrypto.syspulse

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** Trả về Context dùng ngôn ngữ đã chọn trong Cài đặt (không cần appcompat). */
object LocaleHelper {
    fun wrap(base: Context): Context {
        val locale = when (Prefs.lang(base)) {
            "vi" -> Locale.forLanguageTag("vi")
            "zh" -> Locale.SIMPLIFIED_CHINESE
            else -> Locale.ENGLISH
        }
        Locale.setDefault(locale)
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(locale)
        return base.createConfigurationContext(cfg)
    }
}
