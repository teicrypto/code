package com.teicrypto.syspulse

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Màn hình kết nối qua Shizuku: trạng thái, mở/cài Shizuku, cấp quyền. */
class AdbSetupActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var statusCard: LinearLayout
    private lateinit var openBtn: TextView
    private lateinit var grantBtn: TextView

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.wrap(base))
    }

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
            text = getString(R.string.adb_title)
            textSize = 24f
            setTextColor(Color.WHITE)
        })
        root.addView(bar)

        statusCard = UiKit.card(this)
        val sRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        sRow.addView(UiKit.icon(this, "!"))
        status = UiKit.title(this, "").apply { setPadding(d(24), 0, 0, 0) }
        sRow.addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        statusCard.addView(sRow)
        root.addView(statusCard)

        val card = UiKit.card(this)
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(UiKit.icon(this, "◈"))
        row.addView(UiKit.title(this, getString(R.string.shz_card_title)).apply { setPadding(d(24), 0, 0, 0) })
        card.addView(row)
        card.addView(UiKit.body(this, getString(R.string.shz_card_desc)).apply { setPadding(0, d(24), 0, d(8)) })
        openBtn = UiKit.pill(this, "", "↗") { openShizuku() }
        grantBtn = UiKit.pill(this, getString(R.string.shz_btn_grant), "✓") {
            ShizukuBridge.requestPermission()
        }
        card.addView(openBtn)
        card.addView(grantBtn)
        card.addView(UiKit.pill(this, getString(R.string.adb_btn_guide), "☰") { showGuide() })
        root.addView(card)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(UiKit.BG))
            isFillViewport = true
            addView(root)
        })
    }

    private fun openShizuku() {
        val launch = packageManager.getLaunchIntentForPackage(ShizukuBridge.PKG)
        try {
            if (launch != null) startActivity(launch)
            else startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")))
        } catch (e: Exception) { }
    }

    private fun showGuide() {
        AlertDialog.Builder(this)
            .setTitle(R.string.adb_btn_guide)
            .setMessage(R.string.shz_guide_body)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun refresh() {
        val installed = ShizukuBridge.installed(this)
        val running = ShizukuBridge.running()
        val granted = ShizukuBridge.granted()
        status.text = when {
            granted -> getString(R.string.shz_status_ready, if (ShizukuBridge.uid() == 0) "root" else "shell")
            running -> getString(R.string.shz_status_perm)
            installed -> getString(R.string.shz_status_stopped)
            else -> getString(R.string.shz_status_none)
        }
        statusCard.background = UiKit.round(this, if (granted) "#12301F" else UiKit.CARD, 24)
        openBtn.text = "↗   " + getString(if (installed) R.string.shz_btn_open else R.string.shz_btn_download)
        grantBtn.visibility = if (running && !granted) android.view.View.VISIBLE else android.view.View.GONE
    }

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }
}
