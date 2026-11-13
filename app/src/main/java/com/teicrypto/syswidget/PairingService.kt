package com.teicrypto.syswidget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ghép nối ADB:
 *  1. Dịch vụ tìm cổng ghép nối (mDNS) khi bạn mở "Ghép nối thiết bị bằng mã ghép nối".
 *  2. Một ô nhập mã NỔI hiện ở đầu màn hình (nếu đã cấp quyền hiển thị trên ứng dụng khác),
 *     nằm ngay trên hộp thoại ghép nối nên bạn nhìn mã và gõ luôn.
 *  3. Dự phòng: thông báo có ô nhập mã (RemoteInput) cũng hoạt động song song.
 */
class PairingService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var nm: NotificationManager
    private val cancel = AtomicBoolean(false)
    private var started = false

    @Volatile private var port = -1

    // Ô nhập mã nổi
    private var wm: WindowManager? = null
    private var overlay: View? = null
    private var statusTv: TextView? = null
    private var codeRow: View? = null
    private var codeEdit: EditText? = null

    private val density by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * density).toInt()

    private fun str(id: Int, vararg args: Any): String = LocaleHelper.wrap(this).getString(id, *args)

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, str(R.string.pair_notif_channel), NotificationManager.IMPORTANCE_HIGH)
        )
        val n = build(str(R.string.pair_notif_wait_title), str(R.string.pair_notif_wait_text), false)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
        showOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CODE) {
            val code = RemoteInput.getResultsFromIntent(intent)
                ?.getCharSequence(KEY_CODE)?.toString()?.filter { it.isDigit() }.orEmpty()
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
        removeOverlay()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------- Ô nhập mã nổi ----------------

    private fun showOverlay() {
        if (!Settings.canDrawOverlays(this)) return
        try {
            val w = getSystemService(WINDOW_SERVICE) as WindowManager
            wm = w

            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#F2111418"))
                    cornerRadius = dp(16).toFloat()
                }
            }

            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val status = TextView(this).apply {
                text = str(R.string.pair_overlay_wait)
                textSize = 12f
                setTextColor(Color.WHITE)
            }
            statusTv = status
            header.addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            header.addView(TextView(this).apply {
                text = "✕"
                textSize = 18f
                setTextColor(Color.LTGRAY)
                setPadding(dp(14), 0, 0, 0)
                setOnClickListener { stopSelf() }
            })
            root.addView(header)

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, 0)
                visibility = View.GONE
            }
            val edit = EditText(this).apply {
                hint = str(R.string.hint_code)
                inputType = InputType.TYPE_CLASS_NUMBER
                filters = arrayOf(InputFilter.LengthFilter(6))
                setTextColor(Color.WHITE)
                setHintTextColor(Color.GRAY)
                textSize = 18f
            }
            codeEdit = edit
            row.addView(edit, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply {
                text = str(R.string.pair_overlay_btn)
                textSize = 13f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(10), dp(16), dp(10))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#2A5A4A"))
                    cornerRadius = dp(10).toFloat()
                }
                setOnClickListener {
                    val c = codeEdit?.text?.toString()?.filter { it.isDigit() }.orEmpty()
                    if (c.isNotEmpty()) doPair(c)
                }
            })
            codeRow = row
            root.addView(row)

            // Không đặt FLAG_NOT_FOCUSABLE để gõ được bàn phím; NOT_TOUCH_MODAL để chạm ngoài khung
            // vẫn đi xuống cửa sổ phía dưới (hộp thoại ghép nối).
            val lp = WindowManager.LayoutParams(
                dp(320),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dp(70)
            }
            w.addView(root, lp)
            overlay = root
        } catch (e: Throwable) {
            overlay = null // không hiện được khung nổi: vẫn còn thông báo dự phòng
        }
    }

    private fun removeOverlay() {
        val v = overlay ?: return
        try {
            wm?.removeView(v)
        } catch (e: Throwable) {
        }
        overlay = null
    }

    private fun setOverlayStatus(text: String, showInput: Boolean) {
        statusTv?.text = text
        codeRow?.visibility = if (showInput) View.VISIBLE else View.GONE
    }

    // ---------------- Tìm cổng + ghép nối ----------------

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
                    setOverlayStatus(str(R.string.pair_overlay_enter), true)
                }
            }
        }.start()
    }

    private fun doPair(code: String) {
        val p = port
        // Cập nhật ngay để ô nhập không quay mãi
        nm.notify(NOTIF_ID, build(str(R.string.pair_notif_ready_title), "…", false))
        setOverlayStatus(str(R.string.pair_overlay_busy), false)
        Thread {
            val paired = p > 0 && PrivShell.pair(this, p, code)
            val ok = paired && PrivShell.connect(this, force = true)
            val msg = when {
                ok -> str(R.string.pair_ok)
                p <= 0 -> str(R.string.pair_no_port)
                else -> str(R.string.pair_fail_fmt, PrivShell.lastError ?: "?")
            }
            handler.post {
                nm.notify(NOTIF_ID, build(str(R.string.pair_notif_ready_title), msg, !ok && p > 0))
                setOverlayStatus(msg, !ok && p > 0)
                if (ok) handler.postDelayed({ stopSelf() }, 2500)
            }
        }.start()
    }

    // ---------------- Thông báo (dự phòng) ----------------

    private fun build(title: String, text: String, withInput: Boolean): Notification {
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
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
        return b.build()
    }

    companion object {
        private const val CHANNEL = "sys_pair"
        private const val NOTIF_ID = 3
        private const val ACTION_CODE = "com.teicrypto.syswidget.PAIR_CODE"
        private const val KEY_CODE = "code"
    }
}
