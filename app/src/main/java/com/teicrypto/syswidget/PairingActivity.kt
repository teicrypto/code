package com.teicrypto.syswidget

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Bước ghép nối giống Shizuku:
 *  1) Chưa có quyền thông báo -> thẻ đỏ + nút "Tùy chọn thông báo".
 *  2) Có quyền -> bật dịch vụ ghép nối rồi chuyển sang Tùy chọn nhà phát triển để lấy mã;
 *     kéo thanh thông báo xuống bấm "Nhập mã ghép nối" và gõ mã.
 */
class PairingActivity : Activity() {

    private lateinit var warn: LinearLayout
    private lateinit var help: TextView
    private var launched = false

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.wrap(base))
    }

    private fun notifOk(): Boolean =
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).areNotificationsEnabled()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = { v: Int -> UiKit.dp(this, v) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(16), d(40), d(16), d(24))
        }
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        bar.addView(TextView(this).apply {
            text = "←"
            textSize = 26f
            setTextColor(Color.WHITE)
            setPadding(d(8), d(8), d(24), d(8))
            setOnClickListener { finish() }
        })
        bar.addView(TextView(this).apply {
            text = getString(R.string.pairing_title)
            textSize = 24f
            setTextColor(Color.WHITE)
        })
        root.addView(bar)

        // Thẻ cảnh báo đỏ khi chưa cho phép thông báo
        warn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UiKit.round(this@PairingActivity, "#8B1C1A", 28)
            setPadding(d(28), d(28), d(28), d(28))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = d(24) }
        }
        warn.addView(TextView(this).apply {
            text = getString(R.string.pairing_need_notif)
            textSize = 16f
            setTextColor(Color.parseColor("#FFE3E0"))
            setLineSpacing(0f, 1.2f)
        })
        warn.addView(TextView(this).apply {
            text = "↗   " + getString(R.string.pairing_notif_options)
            textSize = 16f
            setTextColor(Color.parseColor("#5C0F0D"))
            background = UiKit.round(this@PairingActivity, "#F0B8B4", 30)
            setPadding(d(24), d(16), d(28), d(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = d(16) }
            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                )
            }
        })
        root.addView(warn)

        help = UiKit.body(this, getString(R.string.pairing_help)).apply {
            setPadding(d(8), d(24), d(8), 0)
            visibility = View.GONE
        }
        root.addView(help)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(UiKit.BG))
            isFillViewport = true
            addView(root)
        })

        // Android 13+: hỏi quyền thông báo nếu chưa có
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        proceed()
    }

    override fun onResume() {
        super.onResume()
        proceed()
    }

    private fun proceed() {
        if (!notifOk()) {
            warn.visibility = View.VISIBLE
            help.visibility = View.GONE
            return
        }
        warn.visibility = View.GONE
        help.visibility = View.VISIBLE
        if (launched) return
        launched = true
        startForegroundService(Intent(this, PairingService::class.java))
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        } catch (e: Exception) {
            try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (e2: Exception) { }
        }
    }
}
