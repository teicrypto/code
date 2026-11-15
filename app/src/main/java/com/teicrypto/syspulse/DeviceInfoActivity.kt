package com.teicrypto.syspulse

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Màn hình Thông tin máy trong app (phần cứng chi tiết). Có banner ở đáy. */
class DeviceInfoActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var ad: AdBanner? = null
    private lateinit var body: TextView
    private var basic = ""
    private var deep = ""
    private var loading = false

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.wrap(base))
    }

    override fun onContentChanged() {
        super.onContentChanged()
        Edge.apply(this)
    }

    private fun d(v: Int) = UiKit.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(16), d(24), d(16), d(16))
        }
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        bar.addView(TextView(this).apply {
            text = "←"; textSize = 26f; setTextColor(Color.WHITE)
            setPadding(d(8), d(8), d(24), d(8))
            setOnClickListener { finish() }
        })
        bar.addView(TextView(this).apply {
            text = getString(R.string.tab_hw); textSize = 24f; setTextColor(Color.WHITE)
        })
        root.addView(bar)

        val row = LinearLayout(this)
        row.addView(UiKit.pill(this, getString(R.string.hw_refresh), "↻") { basic = ""; deep = ""; load() })
        row.addView(UiKit.pill(this, getString(R.string.adb_copy), "⧉") {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("device", body.text))
            Toast.makeText(this, R.string.adb_copied, Toast.LENGTH_SHORT).show()
        }.apply { (layoutParams as LinearLayout.LayoutParams).leftMargin = d(12) })
        root.addView(row)

        body = TextView(this).apply {
            text = "…"
            textSize = 12.5f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#C9D1D9"))
            setTextIsSelectable(true)
            setPadding(0, d(16), 0, d(8))
        }
        root.addView(body)

        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(UiKit.BG))
        }
        outer.addView(
            ScrollView(this).apply { isFillViewport = true; addView(root) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        ad = AdBanner.attach(this, outer)
        setContentView(outer)
    }

    private fun show() {
        body.text = (if (basic.isEmpty()) "…" else basic) + (if (deep.isNotEmpty()) "\n\n$deep" else "")
    }

    private fun load() {
        if (loading) return
        loading = true
        show()
        Thread {
            val ctx = applicationContext
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val b = try { HardwareInfo.basic(ctx, wm) } catch (e: Throwable) { "N/A" }
            handler.post { basic = b; show() }
            // Phần sâu chỉ có khi đã kết nối Shizuku/ADB
            val dp = try { if (PrivShell.connect(ctx)) HardwareInfo.deep(ctx) else null } catch (e: Throwable) { null }
            handler.post { loading = false; if (dp != null) { deep = dp; show() } }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        ad?.resume()
        if (basic.isEmpty()) load()
    }

    override fun onPause() {
        ad?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        ad?.destroy()
        super.onDestroy()
    }
}
