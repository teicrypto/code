package com.teicrypto.syswidget

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/** Màn hình Cài đặt: ngôn ngữ, biểu đồ mini, bong bóng, thanh trạng thái. */
class SettingsActivity : Activity() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.wrap(base))
    }

    private fun metricLabel(key: String): String = when (key) {
        "ram" -> getString(R.string.metric_ram)
        "net" -> getString(R.string.metric_net)
        "temp" -> getString(R.string.metric_temp)
        else -> getString(R.string.metric_cpu)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            setBackgroundColor(Color.parseColor("#111418"))
        }

        fun section(text: String) = TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(Color.parseColor("#7CE0C3"))
            setTypeface(null, Typeface.BOLD)
            setPadding(0, pad, 0, pad / 4)
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.settings_title)
            textSize = 24f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
        })

        // ---- Ngôn ngữ ----
        root.addView(section(getString(R.string.settings_language)))
        val group = RadioGroup(this)
        val current = Prefs.lang(this)
        for ((code, name) in listOf("en" to "English", "vi" to "Tiếng Việt", "zh" to "中文")) {
            group.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = name
                tag = code
                setTextColor(Color.WHITE)
                isChecked = code == current
            })
        }
        group.setOnCheckedChangeListener { g, checkedId ->
            val code = g.findViewById<RadioButton>(checkedId)?.tag as? String
            if (code != null && code != Prefs.lang(this)) {
                Prefs.sp(this).edit().putString(Prefs.LANG, code).apply()
                recreate()
            }
        }
        root.addView(group)

        // ---- Công tắc ----
        fun toggle(textRes: Int, key: String, def: Boolean, onChange: ((Boolean) -> Unit)? = null) =
            Switch(this).apply {
                text = getString(textRes)
                setTextColor(Color.WHITE)
                textSize = 15f
                setPadding(0, pad / 2, 0, pad / 2)
                isChecked = Prefs.sp(this@SettingsActivity).getBoolean(key, def)
                setOnCheckedChangeListener { _, on ->
                    Prefs.sp(this@SettingsActivity).edit().putBoolean(key, on).apply()
                    onChange?.invoke(on)
                }
            }

        root.addView(section(getString(R.string.settings_section_popup)))
        root.addView(toggle(R.string.settings_mini_charts, Prefs.MINI_CHARTS, true))
        root.addView(toggle(R.string.settings_bubble_extra, Prefs.BUBBLE_EXTRA, true))

        // ---- Thanh trạng thái ----
        root.addView(section(getString(R.string.settings_section_status)))

        val metricBtn = Button(this)
        fun updateMetricText() {
            metricBtn.text = getString(R.string.settings_status_metric_fmt, metricLabel(Prefs.statusMetric(this)))
        }
        updateMetricText()
        metricBtn.setOnClickListener {
            val keys = listOf("cpu", "ram", "net", "temp")
            val labels = keys.map { metricLabel(it) }.toTypedArray()
            AlertDialog.Builder(this)
                .setSingleChoiceItems(labels, keys.indexOf(Prefs.statusMetric(this))) { dialog, which ->
                    Prefs.sp(this).edit().putString(Prefs.STATUS_METRIC, keys[which]).apply()
                    updateMetricText()
                    dialog.dismiss()
                }
                .show()
        }

        root.addView(toggle(R.string.settings_status_bar, Prefs.STATUS_BAR, false) { on ->
            if (on) {
                if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
                }
                startForegroundService(Intent(this, StatsService::class.java))
            }
            // Khi tắt: service tự cập nhật lại thông báo (hoặc tự dừng nếu không còn widget)
        })
        root.addView(metricBtn)
        root.addView(TextView(this).apply {
            text = getString(R.string.settings_status_hint)
            textSize = 12f
            setTextColor(Color.parseColor("#8A94A0"))
            setPadding(0, pad / 2, 0, 0)
        })

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.parseColor("#111418"))
            addView(root)
        })
    }
}
