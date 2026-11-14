package com.teicrypto.syswidget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ghép nối ADB qua thông báo (cách ổn định nhất, không bị Android ẩn như cửa sổ nổi):
 *  1. Dịch vụ tìm cổng ghép nối (mDNS) khi bạn mở "Ghép nối thiết bị bằng mã ghép nối".
 *  2. Thông báo hiện ô nhập mã 6 số, bạn gõ ngay trong thanh thông báo. Hộp thoại ghép nối
 *     không bị đóng vì bạn không phải rời khỏi màn hình Gỡ lỗi không dây.
 */
class PairingService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var nm: NotificationManager
    private val cancel = AtomicBoolean(false)
    private var started = false

    @Volatile private var port = -1

    private fun str(id: Int, vararg args: Any): String = LocaleHelper.wrap(this).getString(id, *args)

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, str(R.string.pair_notif_channel), NotificationManager.IMPORTANCE_HIGH)
        )
        val n = build(str(R.string.pair_notif_wait_title), str(R.string.pair_notif_wait_text), true)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CODE) {
            val code = (RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_CODE)?.toString()
                ?: intent.getStringExtra(EXTRA_CODE)).orEmpty().filter { it.isDigit() }
            if (code.isNotEmpty()) doPair(code)
            return START_NOT_STICKY
        }
        if (!started) {
            started = true
            startDiscovery()
            handler.postDelayed({ stopSelf() }, 3 * 60_000L) // tự dừng sau 3 phút
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cancel.set(true)
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startDiscovery() {
        Thread {
            val p = NsdFinder.find(this, "_adb-tls-pairing._tcp", 3 * 60_000L) { cancel.get() }
            if (p != null && !cancel.get()) {
                port = p
                handler.post {
                    nm.notify(
                        NOTIF_ID,
                        build(str(R.string.pair_notif_ready_title), str(R.string.pair_notif_enter), true)
                    )
                }
            }
        }.start()
    }

    private fun doPair(code: String) {
        // Có thể gõ mã trước khi tìm thấy cổng: chờ tối đa vài giây
        var p = port
        // Cập nhật ngay để ô nhập không quay mãi
        nm.notify(NOTIF_ID, build(str(R.string.pair_notif_ready_title), "…", false))
        Thread {
            var waited = 0
            while (port <= 0 && waited < 15_000 && !cancel.get()) { Thread.sleep(500); waited += 500 }
            p = port
            val paired = p > 0 && PrivShell.pair(this, p, code)
            val ok = paired && PrivShell.connect(this, force = true)
            val msg = when {
                ok -> str(R.string.pair_ok)
                p <= 0 -> str(R.string.pair_no_port)
                else -> str(R.string.pair_fail_fmt, PrivShell.lastError ?: "?")
            }
            handler.post {
                nm.notify(NOTIF_ID, build(str(R.string.pair_notif_ready_title), msg, !ok))
                if (ok) handler.postDelayed({ stopSelf() }, 2500)
            }
        }.start()
    }

    private fun build(title: String, text: String, withInput: Boolean): Notification {
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(false)
            .setOnlyAlertOnce(true)
        if (withInput) {
            val intent = Intent(this, PairingService::class.java).setAction(ACTION_CODE)
            // RemoteInput cần PendingIntent có thể ghi đè (mutable), intent phải tường minh
            val pi = PendingIntent.getService(
                this, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            val ri = RemoteInput.Builder(KEY_CODE).setLabel(str(R.string.pair_notif_label)).build()
            b.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_app),
                    str(R.string.pair_notif_action),
                    pi
                ).addRemoteInput(ri).build()
            )
        }
        if (withInput) {
            // Phương án dự phòng: một số ROM không hiện ô nhập trả lời trong thông báo -> mở hộp nhập riêng
            val open = PendingIntent.getActivity(
                this, 2,
                Intent(this, CodeDialogActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            b.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_app), str(R.string.pair_open_dialog), open
                ).build()
            )
        }
        return b.build()
    }

    companion object {
        private const val CHANNEL = "sys_pair"
        private const val NOTIF_ID = 3
        const val ACTION_CODE = "com.teicrypto.syswidget.PAIR_CODE"
        private const val KEY_CODE = "code"
        const val EXTRA_CODE = "code_text"
        const val ACTION_CODE_PUBLIC = ACTION_CODE
    }
}
