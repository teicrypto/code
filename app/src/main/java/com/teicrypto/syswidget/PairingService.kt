package com.teicrypto.syswidget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Chạy ngắn khi người dùng gửi mã: tìm cổng ghép nối (hộp thoại ghép nối đang mở), ghép nối, kết nối.
 * Thông báo nhập mã do PairNotif đăng riêng nên không phụ thuộc vòng đời của dịch vụ này.
 */
class PairingService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var working = false
    @Volatile private var manualPort: Int? = null

    private fun str(id: Int, vararg args: Any): String = LocaleHelper.wrap(this).getString(id, *args)

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("sys_pair_work", str(R.string.pair_notif_channel) + " (…)", NotificationManager.IMPORTANCE_MIN)
        )
        val n = Notification.Builder(this, "sys_pair_work")
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle(str(R.string.pair_notif_ready_title))
            .setContentText("…")
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(4, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(4, n)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Nhập "mã" (6 số) hoặc "cổng mã" nếu muốn tự chỉ định cổng ghép nối trong hộp thoại hệ thống
        val nums = Regex("\\d+").findAll(intent?.getStringExtra(PairNotif.EXTRA_CODE).orEmpty()).map { it.value }.toList()
        val code = nums.lastOrNull().orEmpty()
        manualPort = if (nums.size >= 2) nums[0].toIntOrNull() else null
        if (code.isEmpty() || working) {
            if (!working) stopSelf()
            return START_NOT_STICKY
        }
        working = true
        doPair(code)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun doPair(code: String) {
        PairNotif.post(this, str(R.string.pair_notif_ready_title), "…", false)
        Thread {
            // Hộp thoại ghép nối phải đang mở thì mới tìm thấy cổng
            val port = manualPort ?: NsdFinder.find(this, "_adb-tls-pairing._tcp", 8000) { false }
            PrivShell.log("pairing port=$port (manual=${manualPort != null})")
            val paired = port != null && PrivShell.pair(this, port, code)
            // Ghép nối xong adbd cần vài giây để công bố cổng kết nối: thử lại vài lần
            var ok = false
            if (paired) {
                for (i in 0 until 3) {
                    ok = PrivShell.connect(this, force = true)
                    if (ok) break
                    Thread.sleep(2000)
                }
            }
            val msg = when {
                ok -> str(R.string.pair_ok)
                port == null -> str(R.string.pair_no_port)
                !paired -> str(R.string.pair_fail_fmt, PrivShell.lastError ?: "?")
                else -> str(R.string.pair_ok_noconn_fmt, PrivShell.lastError ?: "?")
            }
            handler.post {
                if (ok) {
                    PairNotif.post(this, str(R.string.pair_notif_ready_title), msg, false)
                    handler.postDelayed({ PairNotif.cancel(this); stopSelf() }, 2500)
                } else {
                    // Chưa ghép nối được: hiện lại ô nhập. Đã ghép nối nhưng chưa nối: chỉ báo lỗi.
                    PairNotif.post(this, str(R.string.pair_notif_ready_title), msg, !paired)
                    stopSelf()
                }
            }
        }.start()
    }
}
