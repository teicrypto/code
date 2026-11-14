package com.teicrypto.syswidget

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.abs

/**
 * Popup nổi kiểu Task Manager.
 *  - Thu nhỏ: bong bóng hiện %CPU, nhiệt độ, GPU, FPS.
 *  - Mở rộng: 3 tab: Hiệu suất (biểu đồ, MHz từng nhân, phần cứng), Tiến trình (CPU/RAM từng app,
 *    cập nhật 2,5 giây), Kết nối (ADB: cách A qua máy tính, cách B ghép nối không dây, nhập mã ngay trong popup).
 * CPU/RAM/GPU/FPS chi tiết cần ADB tích hợp; chưa kết nối thì chỉ có số liệu cơ bản.
 */
class OverlayService : Service() {

    companion object {
        @Volatile var running = false
        private const val PERIOD = 2500L
    }

    private class ProcRow(
        val root: LinearLayout, val name: TextView, val cpu: TextView, val ram: TextView, val kill: TextView
    ) { var pkg = "" }

    private lateinit var wm: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var root: LinearLayout
    private lateinit var bubble: TextView
    private lateinit var panel: LinearLayout
    private lateinit var collector: StatsCollector
    private val handler = Handler(Looper.getMainLooper())
    private var expanded = false
    private var added = false
    private var tab = 0
    private var savedY = -1
    private var fetching = false
    private val density by lazy { resources.displayMetrics.density }

    private lateinit var cpuC: LineChartView
    private lateinit var gpuC: LineChartView
    private lateinit var ramC: LineChartView
    private lateinit var netC: LineChartView
    private lateinit var tempC: LineChartView
    private lateinit var coresText: TextView
    private lateinit var hwText: TextView

    private lateinit var procHint: TextView
    private lateinit var hdrCpu: TextView
    private lateinit var hdrRam: TextView
    private lateinit var procBox: LinearLayout
    private val procRows = ArrayList<ProcRow>()
    private var sortRam = false
    private var cpuMap: Map<String, Float> = emptyMap()
    private var ramMap: Map<String, Long> = emptyMap()
    private val labels = HashMap<String, String>()

    private lateinit var connStatus: TextView
    private lateinit var portEt: EditText
    private lateinit var codeEt: EditText
    private lateinit var connResult: TextView
    @Volatile private var pairing = false
    @Volatile private var finding = false

    private val tabViews = ArrayList<TextView>()
    private val tabContents = ArrayList<View>()

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, k ->
        if (k == Prefs.LANG) rebuild()
    }

    private fun dp(v: Int) = (v * density).toInt()
    private fun str(id: Int, vararg a: Any): String = LocaleHelper.wrap(this).getString(id, *a)

    // ---------------- Vòng lấy số liệu ----------------

    private val tick = object : Runnable {
        override fun run() {
            if (!added) return
            if (fetching) {
                handler.postDelayed(this, PERIOD)
                return
            }
            fetching = true
            val wantProc = expanded && tab == 1
            Thread {
                val ctx = applicationContext
                val s = collector.collect()
                var snap: PrivShell.Snap? = null
                var cpuByPkg: Map<String, Float>? = null
                if (PrivShell.connect(ctx)) {
                    snap = PrivShell.snapshot(ctx)
                    if (wantProc) cpuByPkg = PrivShell.cpuByPkg(ctx)
                }
                handler.post {
                    fetching = false
                    if (added) apply(s, snap, cpuByPkg)
                    if (added) handler.postDelayed(this, PERIOD)
                }
            }.start()
        }
    }

    private fun kick() {
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    private fun apply(s: Stats, snap: PrivShell.Snap?, cpuByPkg: Map<String, Float>?) {
        fun p(x: Int) = if (x >= 0) "$x%" else "N/A"
        val cpuPct = if (snap != null && snap.total >= 0) snap.total else s.cpuPct
        val cpuT = snap?.cpuTemp ?: s.tempC
        val gpuPct = snap?.gpuPct ?: s.gpuPct.takeIf { it >= 0 }
        val gpuT = snap?.gpuTemp
        val fps = snap?.fps

        val gpuPart = when {
            gpuT != null -> "${gpuT.toInt()}°C"
            gpuPct != null -> "$gpuPct%"
            else -> "--"
        }
        bubble.text = "CPU ${p(cpuPct)} · ${cpuT.toInt()}°C\nGPU $gpuPart · ${fps?.toString() ?: "--"} FPS"

        if (snap != null) {
            ramMap = snap.ramKb
        }
        if (cpuByPkg != null) cpuMap = cpuByPkg

        val connected = PrivShell.state() == PrivShell.State.READY
        updateConn(connected)
        if (connected && expanded) loadDeepHardware()

        if (!expanded) return

        // Hiệu suất
        val mhzAvg = snap?.mhz?.values?.takeIf { it.isNotEmpty() }?.average()?.toInt()
        cpuC.add(cpuPct.coerceAtLeast(0).toFloat(),
            p(cpuPct) + (mhzAvg?.let { "  ${it} MHz" } ?: ""))
        val gpuLabel = listOfNotNull(
            gpuPct?.let { "$it%" }, snap?.gpuMhz?.let { "$it MHz" }, gpuT?.let { "${it.toInt()}°C" }
        ).joinToString(" · ").ifEmpty { "N/A" }
        gpuC.add((gpuPct ?: 0).toFloat(), gpuLabel)
        ramC.add(s.ramPct.toFloat(), "${p(s.ramPct)}  ${s.ramInfo}")
        netC.add(s.downBps.toFloat(), s.netInfo)
        tempC.add(cpuT.toFloat(), "CPU ${cpuT.toInt()}°C" + (gpuT?.let { " · GPU ${it.toInt()}°C" } ?: ""))

        if (snap != null && snap.cores.isNotEmpty()) {
            val sb = StringBuilder()
            for (i in snap.cores.indices) {
                sb.append(String.format(Locale.US, "C%d %3d%% %4dMHz", i, snap.cores[i], snap.mhz[i] ?: 0))
                sb.append(if (i % 2 == 0) "   " else "\n")
            }
            coresText.text = sb.toString().trimEnd()
        } else {
            coresText.text = str(R.string.cores_need_adb)
        }

        if (tab == 1) renderProc(connected)
    }

    // ---------------- Vòng đời ----------------

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, str(R.string.overlay_no_permission), Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        running = true
        collector = StatsCollector(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        buildUi()
        kick()
        Prefs.sp(this).registerOnSharedPreferenceChangeListener(prefListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        finding = false
        handler.removeCallbacksAndMessages(null)
        Prefs.sp(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        if (added) {
            try { wm.removeView(root) } catch (e: Exception) { }
            added = false
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun rebuild() {
        if (!added) return
        val x = params.x
        val y = params.y
        try { wm.removeView(root) } catch (e: Exception) { }
        added = false
        buildUi()
        params.x = x
        params.y = y
        wm.updateViewLayout(root, params)
    }

    // ---------------- UI ----------------

    private fun rounded(color: String, radiusDp: Int) = GradientDrawable().apply {
        setColor(Color.parseColor(color))
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun tv(text: String, size: Float, color: String, bold: Boolean = false, mono: Boolean = false) =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(Color.parseColor(color))
            if (bold) setTypeface(null, Typeface.BOLD)
            if (mono) typeface = Typeface.MONOSPACE
        }

    private fun button(label: String, bg: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 12f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = rounded(bg, 8)
        setOnClickListener { onClick() }
    }

    private fun chart(title: String, color: String, max: Float?, min: Float = 0f) =
        LineChartView(this, title, Color.parseColor(color), max, min, false).also {
            it.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(92)
            ).apply { setMargins(0, dp(3), 0, dp(3)) }
        }

    private fun attachDrag(v: View, onTap: (() -> Unit)?) {
        v.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0
            var sy = 0
            var tx = 0f
            var ty = 0f
            var moved = false
            override fun onTouch(view: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        sx = params.x; sy = params.y; tx = e.rawX; ty = e.rawY; moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - tx).toInt()
                        val dy = (e.rawY - ty).toInt()
                        if (abs(dx) > dp(5) || abs(dy) > dp(5)) moved = true
                        if (moved) {
                            params.x = sx + dx
                            params.y = sy + dy
                            try { wm.updateViewLayout(root, params) } catch (ex: Exception) { }
                        }
                    }
                    MotionEvent.ACTION_UP -> if (!moved) onTap?.invoke()
                }
                return true
            }
        })
    }

    private var hwBasic = ""
    private var hwDeep = ""
    private var hwLoading = false
    private var deepLoading = false

    private fun renderHw() {
        if (!::hwText.isInitialized) return
        hwText.text = (if (hwBasic.isEmpty()) "…" else hwBasic) + (if (hwDeep.isNotEmpty()) "\n\n$hwDeep" else "")
    }

    private fun loadHardware() {
        if (hwBasic.isNotEmpty() || hwLoading) return
        hwLoading = true
        Thread {
            val s = try { HardwareInfo.basic(applicationContext, wm) } catch (e: Throwable) { "N/A" }
            handler.post { hwBasic = s; hwLoading = false; if (added) renderHw() }
        }.start()
    }

    private fun loadDeepHardware() {
        if (hwDeep.isNotEmpty() || deepLoading) return
        deepLoading = true
        Thread {
            val s = try { HardwareInfo.deep(applicationContext) } catch (e: Throwable) { null }
            handler.post { deepLoading = false; if (s != null) { hwDeep = s; if (added) renderHw() } }
        }.start()
    }

    private fun buildUi() {
        tabViews.clear()
        tabContents.clear()
        procRows.clear()
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        bubble = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = rounded("#EE111418", 18)
            text = "CPU … \nGPU …"
            visibility = if (expanded) View.GONE else View.VISIBLE
        }

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (expanded) View.VISIBLE else View.GONE
            background = rounded("#F5111418", 14)
            setPadding(dp(8), dp(6), dp(8), dp(8))
        }

        // Thanh tiêu đề (kéo được)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = tv(str(R.string.pop_title), 14f, "#FFFFFF", bold = true).apply {
            setPadding(dp(4), dp(6), dp(4), dp(6))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        attachDrag(title, null)
        header.addView(title)
        header.addView(button("—", "#2A2F36") { toggle() })
        header.addView(button("✕", "#6B2A2A") { stopSelf() }.apply {
            (layoutParams as? LinearLayout.LayoutParams)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(6) })
        panel.addView(header)

        // Tab
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val names = listOf(R.string.tab_perf, R.string.tab_proc, R.string.tab_hw, R.string.tab_conn)
        for ((i, n) in names.withIndex()) {
            val t = tv(str(n), 12.5f, "#8A94A0", bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(8), dp(4), dp(8))
                setOnClickListener { showTab(i) }
            }
            tabViews.add(t)
            tabs.addView(t, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        panel.addView(tabs)

        // --- Tab 0: Hiệu suất ---
        val perf = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cpuC = chart("CPU", "#7CE0C3", 100f)
        gpuC = chart("GPU", "#F2A65A", 100f)
        ramC = chart("RAM", "#6FA8FF", 100f)
        netC = chart(str(R.string.metric_net), "#C792EA", null)
        tempC = chart(str(R.string.chart_temp), "#FF7A7A", 80f, 20f)
        coresText = tv("", 10.5f, "#7CE0C3", mono = true).apply { setPadding(dp(6), dp(2), dp(6), dp(6)) }
        perf.addView(cpuC)
        perf.addView(coresText)
        perf.addView(gpuC)
        perf.addView(ramC)
        perf.addView(netC)
        perf.addView(tempC)
        tabContents.add(ScrollView(this).apply { addView(perf) })

        // --- Tab 1: Tiến trình ---
        val proc = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        procHint = tv("", 11.5f, "#F2A65A").apply { setPadding(dp(6), dp(6), dp(6), dp(6)) }
        proc.addView(procHint)
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        head.addView(tv(str(R.string.col_name), 11f, "#8A94A0", bold = true),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        hdrCpu = tv("", 11f, "#8A94A0", bold = true).apply {
            gravity = Gravity.END
            setOnClickListener { sortRam = false; renderProc(PrivShell.state() == PrivShell.State.READY) }
        }
        hdrRam = tv("", 11f, "#8A94A0", bold = true).apply {
            gravity = Gravity.END
            setOnClickListener { sortRam = true; renderProc(PrivShell.state() == PrivShell.State.READY) }
        }
        head.addView(hdrCpu, LinearLayout.LayoutParams(dp(58), LinearLayout.LayoutParams.WRAP_CONTENT))
        head.addView(hdrRam, LinearLayout.LayoutParams(dp(70), LinearLayout.LayoutParams.WRAP_CONTENT))
        head.addView(View(this), LinearLayout.LayoutParams(dp(30), 1))
        proc.addView(head)
        procBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (i in 0 until 30) {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(4), dp(4), dp(4))
                visibility = View.GONE
            }
            val n = tv("", 11.5f, "#FFFFFF").apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
            val c = tv("", 11.5f, "#FFFFFF", mono = true).apply { gravity = Gravity.END; setPadding(0, dp(2), dp(4), dp(2)) }
            val m = tv("", 11.5f, "#FFFFFF", mono = true).apply { gravity = Gravity.END }
            val k = tv("✕", 13f, "#FF7A7A", bold = true).apply { gravity = Gravity.CENTER }
            r.addView(n, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(c, LinearLayout.LayoutParams(dp(58), LinearLayout.LayoutParams.WRAP_CONTENT))
            r.addView(m, LinearLayout.LayoutParams(dp(70), LinearLayout.LayoutParams.WRAP_CONTENT))
            r.addView(k, LinearLayout.LayoutParams(dp(30), LinearLayout.LayoutParams.WRAP_CONTENT))
            val row = ProcRow(r, n, c, m, k)
            k.setOnClickListener {
                if (row.pkg.isNotEmpty()) {
                    (getSystemService(ACTIVITY_SERVICE) as ActivityManager).killBackgroundProcesses(row.pkg)
                    Toast.makeText(this, str(R.string.overlay_killed_fmt, labelOf(row.pkg)), Toast.LENGTH_SHORT).show()
                }
            }
            procRows.add(row)
            procBox.addView(r)
        }
        proc.addView(procBox)
        proc.addView(tv(str(R.string.proc_gpu_note), 10.5f, "#8A94A0").apply { setPadding(dp(6), dp(8), dp(6), dp(4)) })
        tabContents.add(ScrollView(this).apply { addView(proc) })

        // --- Tab 2: Phần cứng ---
        hwText = tv("…", 10.5f, "#C9D1D9", mono = true).apply { setPadding(dp(8), dp(8), dp(8), dp(8)) }
        tabContents.add(ScrollView(this).apply { addView(hwText) })
        renderHw()
        loadHardware()

        // --- Tab 3: Kết nối ---
        val conn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        connStatus = tv("", 12.5f, "#7CE0C3", bold = true)
        conn.addView(connStatus)

        conn.addView(tv(str(R.string.conn_pc_title), 13f, "#FFFFFF", bold = true).apply { setPadding(0, dp(12), 0, dp(2)) })
        conn.addView(tv(str(R.string.conn_pc_desc), 11.5f, "#C9D1D9"))
        conn.addView(button(str(R.string.btn_adb_connect), "#2A5A4A") { doConnect() }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        })

        conn.addView(tv(str(R.string.conn_wifi_title), 13f, "#FFFFFF", bold = true).apply { setPadding(0, dp(14), 0, dp(2)) })
        conn.addView(tv(str(R.string.conn_wifi_desc), 11.5f, "#C9D1D9"))
        val bRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))
        }
        bRow.addView(button(str(R.string.conn_start_pair), "#2A5A4A") {
            // Android ẩn cửa sổ nổi trên màn hình ghép nối, nên mã được nhập qua thông báo
            startForegroundService(Intent(this, PairingService::class.java))
            if (expanded) toggle() // thu nhỏ popup cho khỏi vướng
            openDevSettings()
        })
        conn.addView(bRow)

        fun field(hint: String, max: Int) = EditText(this).apply {
            this.hint = hint
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            textSize = 15f
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(max))
            background = rounded("#1A1F26", 8)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        portEt = field(str(R.string.hint_port), 5)
        codeEt = field(str(R.string.hint_code), 6)
        codeEt.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if ((s?.length ?: 0) == 6 && portEt.text.isNotEmpty()) doPair()
            }
        })
        val fRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fRow.addView(portEt, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        fRow.addView(codeEt, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(6) })
        conn.addView(fRow)
        conn.addView(button(str(R.string.btn_pair), "#2A5A4A") { doPair() }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        })
        connResult = tv("", 11.5f, "#F2A65A").apply { setPadding(0, dp(8), 0, 0) }
        conn.addView(connResult)
        tabContents.add(ScrollView(this).apply { addView(conn) })

        for (c in tabContents) {
            panel.addView(c, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(400)))
        }

        root.addView(bubble)
        root.addView(panel, LinearLayout.LayoutParams(dp(344), LinearLayout.LayoutParams.WRAP_CONTENT))

        val keepX = if (::params.isInitialized) params.x else dp(8)
        val keepY = if (::params.isInitialized) params.y else dp(120)
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = keepX
            y = keepY
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }
        attachDrag(bubble) { toggle() }

        wm.addView(root, params)
        added = true
        showTab(tab, relayout = false)
        updateConn(PrivShell.state() == PrivShell.State.READY)
    }

    private fun showTab(i: Int, relayout: Boolean = true) {
        tab = i
        for ((k, v) in tabContents.withIndex()) v.visibility = if (k == i) View.VISIBLE else View.GONE
        for ((k, t) in tabViews.withIndex()) {
            t.setTextColor(Color.parseColor(if (k == i) "#7CE0C3" else "#8A94A0"))
            t.background = if (k == i) rounded("#1F2A2A", 8) else null
        }
        applyFocus()
        if (relayout) kick()
    }

    /** Tab Kết nối cần bàn phím: bỏ NOT_FOCUSABLE và đưa popup lên cao để bàn phím không che. */
    private fun applyFocus() {
        val needInput = expanded && tab == 3
        if (needInput) {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            if (savedY < 0) { savedY = params.y; params.y = dp(24) }
        } else {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            if (savedY >= 0) { params.y = savedY; savedY = -1 }
        }
        if (added) try { wm.updateViewLayout(root, params) } catch (e: Exception) { }
    }

    private fun toggle() {
        expanded = !expanded
        panel.visibility = if (expanded) View.VISIBLE else View.GONE
        bubble.visibility = if (expanded) View.GONE else View.VISIBLE
        if (expanded) {
            val maxX = resources.displayMetrics.widthPixels - dp(350)
            params.x = params.x.coerceIn(0, maxOf(0, maxX))
        }
        applyFocus()
        kick()
    }

    // ---------------- Tiến trình ----------------

    private fun labelOf(pkg: String): String = labels.getOrPut(pkg) { PrivShell.label(packageManager, pkg) }

    private fun renderProc(connected: Boolean) {
        hdrCpu.text = "CPU" + if (!sortRam) " ▼" else ""
        hdrRam.text = "RAM" + if (sortRam) " ▼" else ""
        if (!connected && ramMap.isEmpty()) {
            procHint.visibility = View.VISIBLE
            procHint.text = str(R.string.proc_need_adb)
            for (r in procRows) r.root.visibility = View.GONE
            return
        }
        procHint.visibility = View.GONE
        val names = HashSet<String>().apply { addAll(ramMap.keys); addAll(cpuMap.keys) }
        val rows = names.filter { (ramMap[it] ?: 0L) > 0L || (cpuMap[it] ?: 0f) > 0f }
            .sortedWith(
                if (sortRam) compareByDescending<String> { ramMap[it] ?: 0L }
                else compareByDescending<String> { cpuMap[it] ?: 0f }.thenByDescending { ramMap[it] ?: 0L }
            )
            .take(procRows.size)
        for ((i, r) in procRows.withIndex()) {
            if (i >= rows.size) { r.root.visibility = View.GONE; continue }
            val pkg = rows[i]
            val cpu = cpuMap[pkg] ?: 0f
            val kb = ramMap[pkg] ?: 0L
            r.pkg = pkg
            r.name.text = labelOf(pkg)
            r.cpu.text = String.format(Locale.US, "%.1f%%", cpu)
            r.ram.text = if (kb > 0) String.format(Locale.US, "%d MB", kb / 1024) else "--"
            // Làm nóng ô theo mức dùng, giống Task Manager
            r.cpu.setBackgroundColor(Color.argb((cpu.coerceIn(0f, 60f) / 60f * 150).toInt(), 124, 224, 195))
            r.ram.setBackgroundColor(Color.argb((kb / 1024f).coerceIn(0f, 1200f).div(1200f).times(110).toInt(), 111, 168, 255))
            r.root.visibility = View.VISIBLE
        }
    }

    // ---------------- Kết nối ----------------

    private fun updateConn(connected: Boolean) {
        if (!::connStatus.isInitialized) return
        connStatus.text = str(
            when {
                connected -> R.string.adb_ready
                PrivShell.isPaired(this) -> R.string.adb_paired_disconnected
                else -> R.string.adb_disconnected
            }
        )
    }

    private fun openDevSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            try { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e2: Exception) { }
        }
    }

    private fun doConnect() {
        connResult.text = str(R.string.conn_searching)
        Thread {
            val ok = PrivShell.connect(applicationContext, force = true)
            handler.post {
                if (!added) return@post
                connResult.text = str(if (ok) R.string.connect_ok else R.string.connect_fail)
                updateConn(ok)
                if (ok) kick()
            }
        }.start()
    }

    /** Mở Gỡ lỗi không dây và tự tìm cổng ghép nối (mDNS) rồi điền vào ô cổng. */
    private fun startPairing() {
        openDevSettings()
        if (finding) return
        finding = true
        connResult.text = str(R.string.conn_searching)
        Thread {
            val port = NsdFinder.find(applicationContext, "_adb-tls-pairing._tcp", 180_000) { !finding || !added }
            finding = false
            handler.post {
                if (!added) return@post
                if (port != null) {
                    portEt.setText(port.toString())
                    connResult.text = str(R.string.conn_port_found_fmt, port)
                } else {
                    connResult.text = str(R.string.pair_no_port)
                }
            }
        }.start()
    }

    private fun doPair() {
        if (pairing) return
        val port = portEt.text.toString().toIntOrNull()
        val code = codeEt.text.toString()
        if (port == null || code.length != 6) {
            Toast.makeText(this, str(R.string.pair_need_port_code), Toast.LENGTH_SHORT).show()
            return
        }
        pairing = true
        connResult.text = str(R.string.conn_searching)
        Thread {
            val ctx = applicationContext
            val ok = PrivShell.pair(ctx, port, code) && PrivShell.connect(ctx, force = true)
            pairing = false
            handler.post {
                if (!added) return@post
                connResult.text = if (ok) str(R.string.pair_ok)
                else str(R.string.pair_fail_fmt, PrivShell.lastError ?: "?")
                if (ok) codeEt.setText("")
                updateConn(ok)
                if (ok) kick()
            }
        }.start()
    }

    // ---------------- Notification foreground ----------------

    private fun startAsForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("sys_stats", str(R.string.notif_channel), NotificationManager.IMPORTANCE_MIN)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "sys_stats")
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle(str(R.string.notif_overlay_title))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(2, n)
        }
    }
}
