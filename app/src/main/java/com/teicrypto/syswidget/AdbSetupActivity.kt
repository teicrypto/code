package com.teicrypto.syswidget

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Màn hình thiết lập ADB tích hợp, bố cục giống Shizuku: trạng thái, Gỡ lỗi không dây, máy tính. */
class AdbSetupActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var statusCard: LinearLayout
    private var busy = false

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

        // Thanh tiêu đề
        val bar = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
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

        // Thẻ trạng thái
        statusCard = UiKit.card(this)
        val sRow = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        sRow.addView(UiKit.icon(this, "!"))
        status = UiKit.title(this, "").apply { setPadding(d(24), 0, 0, 0) }
        sRow.addView(status)
        statusCard.addView(sRow)
        root.addView(statusCard)

        // Thẻ: Gỡ lỗi không dây
        val wifi = UiKit.card(this)
        val wRow = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        wRow.addView(UiKit.icon(this, "◈"))
        wRow.addView(UiKit.title(this, getString(R.string.adb_card_wifi_title)).apply { setPadding(d(24), 0, 0, 0) })
        wifi.addView(wRow)
        wifi.addView(UiKit.body(this, getString(R.string.adb_card_wifi_desc)).apply { setPadding(0, d(24), 0, d(8)) })
        wifi.addView(UiKit.pill(this, getString(R.string.adb_btn_guide), "↗") { showGuide() })
        wifi.addView(UiKit.pill(this, getString(R.string.adb_btn_pairing), "∞") {
            startActivity(Intent(this, PairingActivity::class.java))
        })
        wifi.addView(UiKit.pill(this, getString(R.string.adb_btn_start), "▶") { doStart() })
        root.addView(wifi)

        // Thẻ: qua máy tính
        val pc = UiKit.card(this)
        val pRow = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        pRow.addView(UiKit.icon(this, "⌁"))
        pRow.addView(UiKit.title(this, getString(R.string.adb_card_pc_title)).apply { setPadding(d(24), 0, 0, 0) })
        pc.addView(pRow)
        pc.addView(UiKit.body(this, getString(R.string.adb_card_pc_desc)).apply { setPadding(0, d(24), 0, d(8)) })
        pc.addView(UiKit.pill(this, getString(R.string.adb_btn_cmd), "‹›") { showCommand() })
        root.addView(pc)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(UiKit.BG))
            isFillViewport = true
            addView(root)
        })
    }

    private fun showGuide() {
        AlertDialog.Builder(this)
            .setTitle(R.string.adb_btn_guide)
            .setMessage(R.string.adb_guide_body)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showCommand() {
        val cmd = "adb tcpip 5555"
        AlertDialog.Builder(this)
            .setTitle(R.string.adb_btn_cmd)
            .setMessage(getString(R.string.adb_cmd_body, cmd))
            .setPositiveButton(R.string.adb_copy) { _, _ ->
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("adb", cmd))
                Toast.makeText(this, R.string.adb_copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }

    private fun doStart() {
        if (busy) return
        busy = true
        Toast.makeText(this, R.string.conn_searching, Toast.LENGTH_SHORT).show()
        Thread {
            val ok = PrivShell.connect(applicationContext, force = true)
            runOnUiThread {
                busy = false
                Toast.makeText(this, if (ok) R.string.connect_ok else R.string.connect_fail, Toast.LENGTH_LONG).show()
                refresh()
            }
        }.start()
    }

    private fun refresh() {
        val on = PrivShell.state() == PrivShell.State.READY
        status.text = getString(if (on) R.string.adb_status_on else R.string.adb_status_off)
        statusCard.background = UiKit.round(this, if (on) "#12301F" else UiKit.CARD, 24)
    }

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            // Đã ghép nối mà chưa nối: tự thử nối nền
            if (PrivShell.isPaired(this@AdbSetupActivity) && PrivShell.state() != PrivShell.State.READY && !busy) {
                busy = true
                Thread {
                    PrivShell.connect(applicationContext)
                    runOnUiThread { busy = false }
                }.start()
            }
            handler.postDelayed(this, 1500)
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
