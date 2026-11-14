package com.teicrypto.syspulse

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class SysWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        // Vẽ ngay 1 lần, rồi cố bật service để cập nhật liên tục
        render(context, manager, ids, StatsCollector(context).collect())
        startService(context)
    }

    override fun onEnabled(context: Context) {
        startService(context)
    }

    override fun onDisabled(context: Context) {
        // Giữ service nếu đang bật hiển thị trên thanh trạng thái
        if (!Prefs.statusBar(context)) {
            context.stopService(Intent(context, StatsService::class.java))
        }
    }

    private fun startService(context: Context) {
        try {
            context.startForegroundService(Intent(context, StatsService::class.java))
        } catch (e: Exception) {
            // Android 12+ có thể chặn khởi chạy nền; mở app và bấm "Bật" là được
        }
    }

    companion object {
        fun render(context: Context, manager: AppWidgetManager, ids: IntArray, s: Stats) {
            if (ids.isEmpty()) return
            val lc = LocaleHelper.wrap(context)
            val v = RemoteViews(context.packageName, R.layout.widget_stats)

            fun bar(textId: Int, barId: Int, subId: Int, label: String, pct: Int, sub: String) {
                v.setTextViewText(textId, if (pct >= 0) "$label  $pct%" else "$label  N/A")
                v.setProgressBar(barId, 100, pct.coerceAtLeast(0), false)
                v.setTextViewText(subId, sub)
            }

            bar(R.id.tvCpu, R.id.pbCpu, R.id.tvCpuSub, "CPU", s.cpuPct, s.cpuInfo)
            bar(R.id.tvGpu, R.id.pbGpu, R.id.tvGpuSub, "GPU", s.gpuPct, s.gpuInfo)
            bar(R.id.tvRam, R.id.pbRam, R.id.tvRamSub, "RAM", s.ramPct, s.ramInfo)
            bar(R.id.tvStorage, R.id.pbStorage, R.id.tvStorageSub, lc.getString(R.string.label_storage), s.storagePct, s.storageInfo)
            bar(R.id.tvBatt, R.id.pbBatt, R.id.tvBattSub, lc.getString(R.string.label_battery), s.battPct, s.battInfo)
            v.setTextViewText(R.id.tvNet, s.netInfo)
            v.setTextViewText(R.id.tvFooter, "${s.tempInfo} · ${s.uptime}")

            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            v.setOnClickPendingIntent(R.id.root, open)

            manager.updateAppWidget(ids, v)
        }
    }
}
