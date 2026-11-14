package com.teicrypto.syspulse

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon

/**
 * Thông báo ghép nối độc lập với dịch vụ: do màn hình Ghép nối đăng, nằm yên trong thanh thông báo
 * (không tự mất) cho đến khi ghép nối xong. Nút nhập mã gửi tới PairReceiver.
 */
object PairNotif {
    const val CHANNEL = "sys_pair"
    const val ID = 3
    const val ACTION_CODE = "com.teicrypto.syspulse.PAIR_CODE"
    const val KEY_CODE = "code"
    const val EXTRA_CODE = "code_text"

    private fun str(ctx: Context, id: Int, vararg a: Any): String = LocaleHelper.wrap(ctx).getString(id, *a)

    private fun nm(ctx: Context) = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun build(ctx: Context, title: String, text: String, withInput: Boolean): Notification {
        nm(ctx).createNotificationChannel(
            NotificationChannel(CHANNEL, str(ctx, R.string.pair_notif_channel), NotificationManager.IMPORTANCE_HIGH)
        )
        val b = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
        if (withInput) {
            // Ô nhập trả lời trong thông báo (RemoteInput cần PendingIntent mutable + intent tường minh)
            val pi = PendingIntent.getBroadcast(
                ctx, 1,
                Intent(ctx, PairReceiver::class.java).setAction(ACTION_CODE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            val ri = RemoteInput.Builder(KEY_CODE).setLabel(str(ctx, R.string.pair_notif_label)).build()
            b.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(ctx, R.drawable.ic_app), str(ctx, R.string.pair_notif_action), pi
                ).addRemoteInput(ri).build()
            )
        }
        return b.build()
    }

    fun post(ctx: Context, title: String, text: String, withInput: Boolean) {
        nm(ctx).notify(ID, build(ctx, title, text, withInput))
    }

    fun cancel(ctx: Context) = nm(ctx).cancel(ID)

    /** Gửi mã cho dịch vụ ghép nối. */
    fun submit(ctx: Context, code: String) {
        ctx.startForegroundService(
            Intent(ctx, PairingService::class.java).setAction(ACTION_CODE).putExtra(EXTRA_CODE, code)
        )
    }
}
