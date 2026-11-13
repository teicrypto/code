package com.teicrypto.syswidget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/**
 * Foreground service: cập nhật widget và (nếu bật) thông số trên thanh trạng thái
 * mỗi 2 giây khi màn hình đang sáng.
 */
class StatsService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var collector: StatsCollector
    private lateinit var nm: NotificationManager
    private var barWasOn = false

    private val tick = object : Runnable {
        override fun run() {
            val mgr = AppWidgetManager.getInstance(this@StatsService)
            val ids = mgr.getAppWidgetIds(ComponentName(this@StatsService, SysWidgetProvider::class.java))
            val showBar = Prefs.statusBar(this@StatsService)

            // Không còn widget và cũng không hiện trên thanh trạng thái -> dừng
            if (ids.isEmpty() && !showBar) {
                stopSelf()
                return
            }

            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (pm.isInteractive) { // tắt màn hình thì khỏi vẽ, đỡ tốn pin
                val s = collector.collect()
                if (ids.isNotEmpty()) SysWidgetProvider.render(this@StatsService, mgr, ids, s)
                if (showBar) {
                    nm.notify(NOTIF_ID, buildNotification(s))
                    barWasOn = true
                } else if (barWasOn) {
                    // Vừa tắt hiển thị: trả thông báo về kênh im lặng, không hiện icon
                    nm.notify(NOTIF_ID, buildNotification(null))
                    barWasOn = false
                }
            }
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        collector = StatsCollector(this)
        nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        startAsForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handler.removeCallbacks(tick)
        handler.post(tick)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun pct(x: Int) = if (x >= 0) "$x%" else "N/A"

    /** Chữ hiện trong icon trên thanh trạng thái theo chỉ số người dùng chọn. */
    private fun iconText(s: Stats): String = when (Prefs.statusMetric(this)) {
        "ram" -> "${s.ramPct}%"
        "net" -> shortSpeed(s.downBps)
        "temp" -> "${s.tempC.toInt()}°"
        else -> if (s.cpuPct >= 0) "${s.cpuPct}%" else "–"
    }

    private fun buildNotification(s: Stats?): Notification {
        val lc = LocaleHelper.wrap(this)
        val showBar = s != null && Prefs.statusBar(this)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val b = Notification.Builder(this, if (showBar) CH_BAR else CH_MIN)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)

        if (showBar && s != null) {
            val title = "CPU ${pct(s.cpuPct)} · RAM ${pct(s.ramPct)} · ${s.tempC.toInt()}°C"
            val body = "${s.netInfo} · GPU ${pct(s.gpuPct)}"
            b.setSmallIcon(StatusIcon.make(iconText(s)))
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText(body + "\n" + s.battInfo))
        } else {
            b.setSmallIcon(R.drawable.ic_app)
                .setContentTitle(lc.getString(R.string.notif_widget_title))
        }
        return b.build()
    }

    private fun startAsForeground() {
        val lc = LocaleHelper.wrap(this)
        nm.createNotificationChannel(
            NotificationChannel(CH_MIN, lc.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_MIN)
        )
        // IMPORTANCE_LOW: im lặng nhưng vẫn hiện icon trên thanh trạng thái
        nm.createNotificationChannel(
            NotificationChannel(CH_BAR, lc.getString(R.string.notif_channel_bar), NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
        )
        val n = buildNotification(null)
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    companion object {
        private const val CH_MIN = "sys_stats"
        private const val CH_BAR = "sys_stats_bar"
        private const val NOTIF_ID = 1
        private const val INTERVAL_MS = 2000L
    }
}
