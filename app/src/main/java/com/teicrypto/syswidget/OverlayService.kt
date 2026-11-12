package com.teicrypto.syswidget

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageStatsManager
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
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/**
 * Cửa sổ nổi kiểu "Task Manager":
 *  - Bong bóng nhỏ hiện CPU/RAM (+ mạng và nhiệt độ nếu bật), kéo thả được, chạm để mở rộng.
 *  - Mở rộng: thông số hệ thống, biểu đồ mini (nếu bật) và danh sách app dùng gần đây, có nút "Tắt".
 * Lưu ý: Android 8+ không cho app thường xem CPU/RAM của TỪNG app khác,
 * nên danh sách dựa trên "thời điểm dùng gần nhất" (UsageStats).
 */
class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var root: LinearLayout
    private lateinit var bubble: TextView
    private lateinit var panel: LinearLayout
    private lateinit var header: TextView
    private lateinit var list: LinearLayout
    private lateinit var collector: StatsCollector
    private val handler = Handler(Looper.getMainLooper())
    private var expanded = false
    private var added = false
    private var posX = 0
    private var posY = 0
    private val density by lazy { resources.displayMetrics.density }

    // Biểu đồ mini (null khi tắt trong Cài đặt)
    private var cpuC: LineChartView? = null
    private var ramC: LineChartView? = null
    private var netC: LineChartView? = null
    private var tempC: LineChartView? = null

    // Giữ tham chiếu mạnh, vì SharedPreferences chỉ giữ listener bằng weak reference
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> rebuild() }

    private fun dp(v: Int) = (v * density).toInt()

    private fun str(id: Int, vararg args: Any): String = LocaleHelper.wrap(this).getString(id, *args)

    private val tick = object : Runnable {
        override fun run() {
            val s = collector.collect()
            fun p(x: Int) = if (x >= 0) "$x%" else "N/A"
            val line1 = "CPU ${p(s.cpuPct)} · RAM ${p(s.ramPct)}"
            bubble.text = if (Prefs.bubbleExtra(this@OverlayService))
                "$line1\n↓${shortSpeed(s.downBps)}/s · ${s.tempC.toInt()}°C"
            else line1

            cpuC?.add(s.cpuPct.coerceAtLeast(0).toFloat(), p(s.cpuPct))
            ramC?.add(s.ramPct.toFloat(), p(s.ramPct))
            netC?.add(s.downBps.toFloat(), "${shortSpeed(s.downBps)}/s")
            tempC?.add(s.tempC.toFloat(), "${s.tempC.toInt()}°C")

            if (expanded) {
                fun row(label: String, v: String) = label.padEnd(8) + v
                header.text = listOf(
                    row("CPU", "${p(s.cpuPct)}  ${s.cpuInfo}"),
                    row("GPU", "${p(s.gpuPct)}  ${s.gpuInfo}"),
                    row("RAM", "${p(s.ramPct)}  ${s.ramInfo}"),
                    row(str(R.string.label_temp), s.tempInfo),
                    row(str(R.string.label_battery), "${p(s.battPct)}  ${s.battInfo}"),
                    row(str(R.string.label_network), s.netInfo),
                    s.uptime
                ).joinToString("\n")
            }
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        startAsForeground() // phải gọi trước, kể cả khi sắp tự dừng
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, str(R.string.overlay_no_permission), Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        collector = StatsCollector(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        posX = dp(8)
        posY = dp(120)
        buildUi()
        handler.post(tick)
        Prefs.sp(this).registerOnSharedPreferenceChangeListener(prefListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        Prefs.sp(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        if (added) {
            try { wm.removeView(root) } catch (e: Exception) { }
            added = false
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Dựng lại giao diện khi đổi cài đặt (ngôn ngữ, biểu đồ mini, bong bóng...). */
    private fun rebuild() {
        if (!added) return
        try { wm.removeView(root) } catch (e: Exception) { }
        added = false
        buildUi()
    }

    // ---------------- UI ----------------

    private fun rounded(color: String, radiusDp: Int) = GradientDrawable().apply {
        setColor(Color.parseColor(color))
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun mini(title: String, color: String, max: Float?, min: Float) =
        LineChartView(this, title, Color.parseColor(color), max, min, true)

    private fun chartRow(a: LineChartView, b: LineChartView): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (c in listOf(a, b)) {
            row.addView(
                c,
                LinearLayout.LayoutParams(0, dp(64), 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }
            )
        }
        return row
    }

    private fun buildUi() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        bubble = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = rounded("#EE111418", 18)
            text = "…"
        }

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (expanded) View.VISIBLE else View.GONE
            background = rounded("#F2111418", 16)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        header = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 10.5f
            setTextColor(Color.parseColor("#7CE0C3"))
        }
        panel.addView(header)

        // Biểu đồ mini
        val showCharts = Prefs.miniCharts(this)
        if (showCharts) {
            val c1 = mini("CPU", "#7CE0C3", 100f, 0f)
            val c2 = mini("RAM", "#6FA8FF", 100f, 0f)
            val c3 = mini(str(R.string.metric_net), "#C792EA", null, 0f)
            val c4 = mini(str(R.string.chart_temp), "#FF7A7A", 80f, 20f)
            cpuC = c1; ramC = c2; netC = c3; tempC = c4
            val grid = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(6), 0, 0)
            }
            grid.addView(chartRow(c1, c2))
            grid.addView(chartRow(c3, c4))
            panel.addView(grid)
        } else {
            cpuC = null; ramC = null; netC = null; tempC = null
        }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(4))
        }
        actions.addView(chip(str(R.string.overlay_refresh), "#2A2F36") { refreshList() })
        actions.addView(chip(str(R.string.overlay_close), "#6B2A2A") { stopSelf() })
        panel.addView(actions)

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(list) }
        panel.addView(
            scroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(if (showCharts) 200 else 260))
        )

        root.addView(bubble)
        root.addView(panel, LinearLayout.LayoutParams(dp(300), LinearLayout.LayoutParams.WRAP_CONTENT))

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = posX
            y = posY
        }

        bubble.setOnTouchListener(object : View.OnTouchListener {
            var startX = 0
            var startY = 0
            var touchX = 0f
            var touchY = 0f
            var moved = false

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x; startY = params.y
                        touchX = e.rawX; touchY = e.rawY
                        moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - touchX).toInt()
                        val dy = (e.rawY - touchY).toInt()
                        if (abs(dx) > dp(5) || abs(dy) > dp(5)) moved = true
                        if (moved) {
                            params.x = startX + dx
                            params.y = startY + dy
                            posX = params.x
                            posY = params.y
                            wm.updateViewLayout(root, params)
                        }
                    }
                    MotionEvent.ACTION_UP -> if (!moved) toggle()
                }
                return true
            }
        })

        wm.addView(root, params)
        added = true
        if (expanded) refreshList()
    }

    private fun chip(label: String, bg: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 11f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(6), dp(10), dp(6))
        background = rounded(bg, 10)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = dp(6) }
        setOnClickListener { onClick() }
    }

    private fun toggle() {
        expanded = !expanded
        panel.visibility = if (expanded) View.VISIBLE else View.GONE
        if (expanded) {
            val maxX = resources.displayMetrics.widthPixels - dp(310)
            params.x = params.x.coerceIn(0, maxOf(0, maxX))
            posX = params.x
            refreshList()
        }
        wm.updateViewLayout(root, params)
    }

    // ---------------- Danh sách app ----------------

    private fun hasUsageAccess(): Boolean {
        val ops = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        val uid = android.os.Process.myUid()
        val mode = if (Build.VERSION.SDK_INT >= 29)
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, uid, packageName)
        else
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, uid, packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun ago(ms: Long): String {
        val m = ms / 60000
        return when {
            m < 1 -> str(R.string.ago_now)
            m < 60 -> str(R.string.ago_min_fmt, m)
            else -> str(R.string.ago_hour_fmt, m / 60)
        }
    }

    private fun refreshList() {
        list.removeAllViews()

        if (!hasUsageAccess()) {
            list.addView(TextView(this).apply {
                text = str(R.string.overlay_need_usage)
                textSize = 11f
                setTextColor(Color.LTGRAY)
                setPadding(0, dp(6), 0, dp(6))
            })
            list.addView(chip(str(R.string.overlay_open_settings), "#2A5A4A") {
                startActivity(
                    Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            })
            return
        }

        val now = System.currentTimeMillis()
        val dayAgo = now - 24L * 3600 * 1000
        val usm = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, dayAgo, now) ?: emptyList()
        val pm = packageManager

        val rows = stats
            .filter {
                it.lastTimeUsed > dayAgo &&
                    it.packageName != packageName &&
                    pm.getLaunchIntentForPackage(it.packageName) != null
            }
            .groupBy { it.packageName }
            .map { (pkg, l) -> pkg to l.maxOf { it.lastTimeUsed } }
            .sortedByDescending { it.second }
            .take(15)

        if (rows.isEmpty()) {
            list.addView(TextView(this).apply {
                text = str(R.string.overlay_no_apps)
                textSize = 11f
                setTextColor(Color.LTGRAY)
            })
            return
        }

        for ((pkg, last) in rows) {
            val label = try {
                pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
            } catch (e: Exception) {
                pkg
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            row.addView(TextView(this).apply {
                text = "$label\n${ago(now - last)}"
                textSize = 11.5f
                setTextColor(Color.WHITE)
                maxLines = 2
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(chip(str(R.string.overlay_kill), "#6B2A2A") {
                val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
                am.killBackgroundProcesses(pkg)
                Toast.makeText(this, str(R.string.overlay_killed_fmt, label), Toast.LENGTH_SHORT).show()
                list.removeView(row)
            })
            list.addView(row)
        }
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
