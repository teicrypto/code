package com.teicrypto.syspulse

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import java.io.File
import java.util.Locale

data class Stats(
    val cpuPct: Int,
    val cpuInfo: String,
    val gpuPct: Int,          // -1 = không đọc được
    val gpuInfo: String,
    val ramPct: Int,
    val ramInfo: String,
    val storagePct: Int,
    val storageInfo: String,
    val battPct: Int,
    val battInfo: String,
    val netInfo: String,
    val tempInfo: String,
    val uptime: String,
    val downBps: Double,      // tốc độ tải xuống (byte/giây)
    val upBps: Double,        // tốc độ tải lên (byte/giây)
    val tempC: Double         // nhiệt độ SoC, nếu không đọc được thì dùng nhiệt độ pin
)

/** Tốc độ dạng rút gọn cho icon/bong bóng: 1.2M, 350K, 0 */
fun shortSpeed(bps: Double): String = when {
    bps >= 1048576 -> String.format(Locale.US, "%.1fM", bps / 1048576)
    bps >= 1024 -> String.format(Locale.US, "%.0fK", bps / 1024)
    else -> "0"
}

/**
 * Thu thập thông số hệ thống. Android 8+ chặn app thường đọc /proc/stat và nhiều file /sys,
 * nên CPU/GPU có cơ chế fallback; nếu máy không cho đọc thì hiện "N/A".
 */
class StatsCollector(private val ctx: Context) {

    private var lastIdle = 0L
    private var lastTotal = 0L
    private var lastRx = TrafficStats.getTotalRxBytes()
    private var lastTx = TrafficStats.getTotalTxBytes()
    private var lastNetTime = SystemClock.elapsedRealtime()

    private fun read(path: String): String? = try {
        File(path).readText().trim().ifEmpty { null }
    } catch (e: Exception) {
        null
    }

    private fun firstNumber(s: String?): Long? =
        s?.let { Regex("-?\\d+").find(it)?.value?.toLongOrNull() }

    // ---------- CPU ----------
    private fun cpuUsageFromProcStat(): Int? {
        val line = read("/proc/stat")?.lineSequence()?.firstOrNull { it.startsWith("cpu ") } ?: return null
        val p = line.split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
        if (p.size < 5) return null
        val idle = p[3] + p[4]
        val total = p.sum()
        val dIdle = idle - lastIdle
        val dTotal = total - lastTotal
        lastIdle = idle
        lastTotal = total
        if (dTotal <= 0 || dTotal == total) return null // lần đầu chưa có delta
        return (100 - dIdle * 100 / dTotal).toInt().coerceIn(0, 100)
    }

    /** Fallback: tải ước lượng = tần số hiện tại / tần số tối đa của từng nhân. */
    private fun cpuFreqInfo(): Pair<Int, String>? {
        val cores = Runtime.getRuntime().availableProcessors()
        var sumRatio = 0.0
        var n = 0
        var maxCur = 0L
        for (i in 0 until cores) {
            val base = "/sys/devices/system/cpu/cpu$i/cpufreq"
            val cur = firstNumber(read("$base/scaling_cur_freq")) ?: continue
            val max = firstNumber(read("$base/cpuinfo_max_freq")) ?: continue
            if (max <= 0) continue
            sumRatio += cur.toDouble() / max
            n++
            if (cur > maxCur) maxCur = cur
        }
        if (n == 0) return null
        return Pair((sumRatio / n * 100).toInt().coerceIn(0, 100), "${maxCur / 1000} MHz")
    }

    // ---------- GPU ----------
    private fun gpuPercent(): Int? {
        read("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage")?.let { firstNumber(it) }?.let { return it.toInt().coerceIn(0, 100) }
        read("/sys/class/kgsl/kgsl-3d0/gpubusy")?.let {
            val n = Regex("\\d+").findAll(it).map { m -> m.value.toLong() }.toList()
            if (n.size >= 2 && n[1] > 0) return (n[0] * 100 / n[1]).toInt().coerceIn(0, 100)
        }
        read("/sys/kernel/gpu/gpu_busy")?.let { firstNumber(it) }?.let { return it.toInt().coerceIn(0, 100) }
        // Mali
        File("/sys/devices/platform").listFiles()?.forEach { d ->
            if (d.name.contains("mali", true)) {
                read("${d.path}/utilization")?.let { firstNumber(it) }?.let { return it.toInt().coerceIn(0, 100) }
            }
        }
        File("/sys/class/devfreq").listFiles()?.forEach { d ->
            val n = d.name.lowercase()
            if (n.contains("mali") || n.contains("gpu") || n.contains("kgsl")) {
                read("${d.path}/load")?.let { firstNumber(it) }?.let { return it.toInt().coerceIn(0, 100) }
            }
        }
        return null
    }

    private fun gpuFreq(): String? {
        val hz = firstNumber(read("/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq"))
            ?: firstNumber(read("/sys/class/kgsl/kgsl-3d0/gpuclk"))
        if (hz != null && hz > 0) return "${hz / 1_000_000} MHz"
        File("/sys/class/devfreq").listFiles()?.forEach { d ->
            val n = d.name.lowercase()
            if (n.contains("mali") || n.contains("gpu")) {
                firstNumber(read("${d.path}/cur_freq"))?.let { return "${it / 1_000_000} MHz" }
            }
        }
        return null
    }

    // ---------- Nhiệt độ ----------
    private fun socTemp(): Double? {
        File("/sys/class/thermal").listFiles()?.forEach { z ->
            if (!z.name.startsWith("thermal_zone")) return@forEach
            val type = read("${z.path}/type")?.lowercase() ?: return@forEach
            if (type.contains("cpu") || type.contains("soc") || type.contains("tsens") || type.contains("gpu")) {
                val raw = firstNumber(read("${z.path}/temp")) ?: return@forEach
                val c = if (raw > 1000) raw / 1000.0 else raw.toDouble()
                if (c in 15.0..110.0) return c
            }
        }
        return null
    }

    // ---------- Helpers ----------
    private fun fmtBytes(b: Long): String {
        val gb = b / 1073741824.0
        return if (gb >= 1) String.format(Locale.US, "%.1f GB", gb)
        else String.format(Locale.US, "%d MB", b / 1048576)
    }

    private fun fmtSpeed(bps: Double): String = when {
        bps >= 1048576 -> String.format(Locale.US, "%.1f MB/s", bps / 1048576)
        bps >= 1024 -> String.format(Locale.US, "%.0f KB/s", bps / 1024)
        else -> String.format(Locale.US, "%.0f B/s", bps)
    }

    fun collect(): Stats {
        val lc = LocaleHelper.wrap(ctx) // chuỗi theo ngôn ngữ đã chọn

        // CPU
        val fromProc = cpuUsageFromProcStat()
        val freq = cpuFreqInfo()
        val cpuPct = fromProc ?: freq?.first ?: -1
        val cpuInfo = buildString {
            append(lc.getString(if (fromProc != null) R.string.cpu_real else R.string.cpu_est))
            append(" · ")
            append(lc.getString(R.string.cores_fmt, Runtime.getRuntime().availableProcessors()))
            freq?.let { append(" · ${it.second}") }
        }

        // GPU
        val gpuPct = gpuPercent() ?: -1
        val gpuInfo = gpuFreq() ?: if (gpuPct < 0) lc.getString(R.string.gpu_unreadable) else ""

        // RAM
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val used = mi.totalMem - mi.availMem
        val ramPct = (used * 100 / mi.totalMem).toInt()
        val ramInfo = "${fmtBytes(used)} / ${fmtBytes(mi.totalMem)}"

        // Bộ nhớ trong
        val sf = StatFs(Environment.getDataDirectory().path)
        val sTotal = sf.totalBytes
        val sUsed = sTotal - sf.availableBytes
        val storagePct = (sUsed * 100 / sTotal).toInt()
        val storageInfo = "${fmtBytes(sUsed)} / ${fmtBytes(sTotal)}"

        // Pin
        val bi: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bi?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = bi?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val battPct = if (level >= 0) level * 100 / scale else -1
        val status = bi?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val battTemp = (bi?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        val volt = (bi?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0) / 1000.0
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val curUa = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        // Đơn vị µA ở đa số máy; một số máy trả mA
        val curMa = if (Math.abs(curUa) > 20000) curUa / 1000 else curUa
        val battInfo = String.format(
            Locale.US, "%s · %.1f°C · %.2f V · %d mA",
            lc.getString(if (charging) R.string.charging else R.string.discharging),
            battTemp, volt, Math.abs(curMa)
        )

        // Mạng
        val now = SystemClock.elapsedRealtime()
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        val dt = (now - lastNetTime) / 1000.0
        val down = if (dt > 0 && rx >= lastRx) (rx - lastRx) / dt else 0.0
        val up = if (dt > 0 && tx >= lastTx) (tx - lastTx) / dt else 0.0
        lastRx = rx; lastTx = tx; lastNetTime = now
        val netInfo = "↓ ${fmtSpeed(down)}   ↑ ${fmtSpeed(up)}"

        // Nhiệt độ: ưu tiên SoC, không có thì dùng nhiệt độ pin
        val soc = socTemp()
        val tempC = soc ?: battTemp
        val tempInfo = if (soc != null) String.format(Locale.US, "SoC %.0f°C", soc)
        else lc.getString(R.string.temp_batt_fmt, battTemp)

        // Uptime
        val sec = SystemClock.elapsedRealtime() / 1000
        val uptime = lc.getString(R.string.uptime_fmt, sec / 3600, sec % 3600 / 60)

        return Stats(
            cpuPct, cpuInfo, gpuPct, gpuInfo, ramPct, ramInfo, storagePct, storageInfo,
            battPct, battInfo, netInfo, tempInfo, uptime, down, up, tempC
        )
    }
}
