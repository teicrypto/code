package com.teicrypto.syspulse

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/** Màn hình Tiến trình trong app: CPU/RAM từng ứng dụng (cần Shizuku/ADB). Có banner ở đáy; nút Shizuku thì không. */
class ProcessesActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var ad: AdBanner? = null
    private lateinit var hint: TextView
    private lateinit var hintBtn: TextView
    private lateinit var list: LinearLayout
    private lateinit var hdrCpu: TextView
    private lateinit var hdrRam: TextView
    private var sortRam = false
    private var running = false
    private var fetching = false
    private var cpuMap: Map<String, Float> = emptyMap()
    private var ramMap: Map<String, Long> = emptyMap()
    private val labels = HashMap<String, String>()

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.wrap(base))
    }

    override fun onContentChanged() {
        super.onContentChanged()
        Edge.apply(this)
    }

    private fun d(v: Int) = UiKit.dp(this, v)

    private fun tv(text: String, size: Float, color: String) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(Color.parseColor(color))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(16), d(24), d(16), d(16))
        }
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        bar.addView(tv("←", 26f, "#FFFFFF").apply {
            setPadding(d(8), d(8), d(24), d(8))
            setOnClickListener { finish() }
        })
        bar.addView(tv(getString(R.string.tab_proc), 24f, "#FFFFFF"))
        root.addView(bar)

        hint = tv(getString(R.string.proc_need_adb_app), 14f, "#F2A65A").apply { setPadding(0, d(12), 0, 0) }
        root.addView(hint)
        hintBtn = UiKit.pill(this, getString(R.string.btn_open_adb), "⚡") {
            startActivity(Intent(this, AdbSetupActivity::class.java))
        }
        root.addView(hintBtn)

        val head = LinearLayout(this).apply { setPadding(0, d(16), 0, d(4)) }
        head.addView(tv("", 13f, "#8A94A0"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        hdrCpu = tv("CPU", 13f, "#7CE0C3").apply {
            gravity = Gravity.END
            setOnClickListener { sortRam = false; render() }
        }
        hdrRam = tv("RAM", 13f, "#6FA8FF").apply {
            gravity = Gravity.END
            setOnClickListener { sortRam = true; render() }
        }
        head.addView(hdrCpu, LinearLayout.LayoutParams(d(72), LinearLayout.LayoutParams.WRAP_CONTENT))
        head.addView(hdrRam, LinearLayout.LayoutParams(d(88), LinearLayout.LayoutParams.WRAP_CONTENT))
        head.addView(View(this), LinearLayout.LayoutParams(d(44), 1))
        root.addView(head)

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
        root.addView(tv(getString(R.string.proc_gpu_note), 12f, "#8A94A0").apply { setPadding(0, d(12), 0, d(8)) })

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

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            if (fetching) { handler.postDelayed(this, 3000L); return }
            fetching = true
            Thread {
                val ctx = applicationContext
                var ram: Map<String, Long>? = null
                var cpu: Map<String, Float>? = null
                val ok = PrivShell.connect(ctx)
                if (ok) {
                    ram = PrivShell.snapshot(ctx)?.ramKb
                    cpu = PrivShell.cpuByPkg(ctx)
                }
                handler.post {
                    fetching = false
                    if (ram != null) ramMap = ram
                    if (cpu != null) cpuMap = cpu
                    if (running) { render(ok); handler.postDelayed(this, 3000L) }
                }
            }.start()
        }
    }

    private var lastOk = false

    private fun labelOf(pkg: String) = labels.getOrPut(pkg) { PrivShell.label(packageManager, pkg) }

    private fun render(ok: Boolean = lastOk) {
        lastOk = ok
        hdrCpu.text = "CPU" + if (!sortRam) " ▼" else ""
        hdrRam.text = "RAM" + if (sortRam) " ▼" else ""
        val has = ramMap.isNotEmpty() || cpuMap.isNotEmpty()
        hint.visibility = if (ok || has) View.GONE else View.VISIBLE
        hintBtn.visibility = if (ok || has) View.GONE else View.VISIBLE
        list.removeAllViews()
        val names = HashSet<String>().apply { addAll(ramMap.keys); addAll(cpuMap.keys) }
        val rows = names.filter { (ramMap[it] ?: 0L) > 0L || (cpuMap[it] ?: 0f) > 0f }
            .sortedWith(
                if (sortRam) compareByDescending<String> { ramMap[it] ?: 0L }
                else compareByDescending<String> { cpuMap[it] ?: 0f }.thenByDescending { ramMap[it] ?: 0L }
            )
            .take(40)
        for (pkg in rows) {
            val cpu = cpuMap[pkg] ?: 0f
            val kb = ramMap[pkg] ?: 0L
            val r = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, d(6), 0, d(6))
            }
            r.addView(tv(labelOf(pkg), 14f, "#FFFFFF").apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(tv(String.format(Locale.US, "%.1f%%", cpu), 14f, "#C9D1D9").apply {
                gravity = Gravity.END
                setBackgroundColor(Color.argb((cpu.coerceIn(0f, 60f) / 60f * 150).toInt(), 124, 224, 195))
            }, LinearLayout.LayoutParams(d(72), LinearLayout.LayoutParams.WRAP_CONTENT))
            r.addView(tv(if (kb > 0) String.format(Locale.US, "%d MB", kb / 1024) else "--", 14f, "#C9D1D9").apply {
                gravity = Gravity.END
                setBackgroundColor(Color.argb(((kb / 1024f).coerceIn(0f, 1200f) / 1200f * 110).toInt(), 111, 168, 255))
            }, LinearLayout.LayoutParams(d(88), LinearLayout.LayoutParams.WRAP_CONTENT))
            r.addView(tv("✕", 16f, "#FF7A7A").apply {
                gravity = Gravity.CENTER
                setOnClickListener {
                    (getSystemService(ACTIVITY_SERVICE) as ActivityManager).killBackgroundProcesses(pkg)
                    Toast.makeText(this@ProcessesActivity, getString(R.string.overlay_killed_fmt, labelOf(pkg)), Toast.LENGTH_SHORT).show()
                }
            }, LinearLayout.LayoutParams(d(44), LinearLayout.LayoutParams.WRAP_CONTENT))
            list.addView(r)
        }
    }

    override fun onResume() {
        super.onResume()
        ad?.resume()
        running = true
        render()
        handler.post(tick)
    }

    override fun onPause() {
        running = false
        handler.removeCallbacks(tick)
        ad?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        ad?.destroy()
        super.onDestroy()
    }
}
