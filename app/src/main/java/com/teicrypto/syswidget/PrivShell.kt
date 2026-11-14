package com.teicrypto.syswidget

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import java.util.Locale

data class AppCpu(val name: String, val total: Float, val user: Float, val kernel: Float)
data class AppRam(val name: String, val kb: Long)

/**
 * Shell quyền cao (quyền "shell" của adb) chạy ngay trong app, qua Gỡ lỗi không dây:
 * app tự ghép nối với adbd của chính máy này, rồi chạy `dumpsys cpuinfo` / `dumpsys meminfo`
 * để biết từng app đang ăn bao nhiêu CPU và RAM.
 */
object PrivShell {

    enum class State { DISCONNECTED, READY }

    private const val HOST = "127.0.0.1"
    private const val CONNECT_TYPE = "_adb-tls-connect._tcp"
    private const val KEY_PAIRED = "adb_paired"
    private const val KEY_PORT = "adb_last_port"
    private const val TCPIP_PORT = 5555

    @Volatile private var connected = false
    @Volatile var lastError: String? = null
        private set
    private var lastAttempt = 0L

    fun state(): State = if (connected) State.READY else State.DISCONNECTED

    fun isPaired(ctx: Context): Boolean = Prefs.sp(ctx).getBoolean(KEY_PAIRED, false)

    /** Ghép nối bằng cổng + mã 6 số của hộp thoại "Ghép nối thiết bị bằng mã ghép nối". Blocking. */
    fun pair(ctx: Context, port: Int, code: String): Boolean = try {
        val r: Any? = SysAdbManager.getInstance(ctx.applicationContext).pair(HOST, port, code)
        if (r == false) {
            lastError = "pair() = false"
            false
        } else {
            Prefs.sp(ctx).edit().putBoolean(KEY_PAIRED, true).apply()
            true
        }
    } catch (e: Throwable) {
        lastError = e.message ?: e.javaClass.simpleName
        false
    }

    /** Kết nối thẳng tới một cổng; thành công thì nhớ cổng này cho lần sau. */
    private fun tryConnect(ctx: Context, port: Int): Boolean {
        return try {
            val r: Any? = SysAdbManager.getInstance(ctx.applicationContext).connect(HOST, port)
            connected = r != false
            if (connected) Prefs.sp(ctx).edit().putInt(KEY_PORT, port).apply()
            connected
        } catch (e: Throwable) {
            lastError = e.message ?: e.javaClass.simpleName
            connected = false
            false
        }
    }

    /**
     * Kết nối tới adbd của máy. Blocking.
     * Thử cổng đã lưu ở lần kết nối trước (nhanh, không cần mDNS), không được thì tự tìm cổng mới.
     * Không ép (force = false): chỉ thử khi đã từng ghép nối, và cách nhau tối thiểu 20 giây.
     */
    @Synchronized
    fun connect(ctx: Context, force: Boolean = false): Boolean {
        if (connected) return true
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastAttempt < 20_000) return false
        lastAttempt = now

        // Cách A: đã chạy `adb tcpip 5555` từ máy tính -> nối thẳng cổng 5555
        if (tryConnect(ctx, TCPIP_PORT)) return true
        if (!force && !isPaired(ctx)) return false

        val saved = Prefs.sp(ctx).getInt(KEY_PORT, 0)
        if (saved > 0 && saved != TCPIP_PORT && tryConnect(ctx, saved)) return true

        // Cổng kết nối do adbd công bố trong thuộc tính hệ thống (nếu ROM cho đọc)
        val prop = tlsPortProp()
        if (prop != null && prop != saved && tryConnect(ctx, prop)) return true

        val port = NsdFinder.find(ctx, CONNECT_TYPE, if (force) 12_000L else 6000L) { false }
        if (port == null) {
            lastError = "no connect port (Wi-Fi / Wireless debugging off?)"
            return false
        }
        return tryConnect(ctx, port)
    }

    private fun tlsPortProp(): Int? = try {
        val p = ProcessBuilder("getprop", "service.adb.tls.port").redirectErrorStream(true).start()
        val s = p.inputStream.bufferedReader().readText().trim()
        p.waitFor()
        s.toIntOrNull()?.takeIf { it > 0 }
    } catch (e: Throwable) {
        null
    }

    /** Chạy lệnh shell từ bên ngoài. Blocking. */
    fun run(ctx: Context, cmd: String): String? = exec(ctx, cmd)

    /** Chạy một lệnh shell, trả về toàn bộ output. Blocking. */
    private fun exec(ctx: Context, cmd: String): String? {
        for (attempt in 0..1) {
            if (!(if (attempt == 0) connect(ctx) else connect(ctx, force = true))) continue
            try {
                val stream = SysAdbManager.getInstance(ctx.applicationContext).openStream("shell:$cmd")
                val text = stream.openInputStream().bufferedReader().readText()
                try {
                    stream.close()
                } catch (e: Throwable) {
                }
                return text
            } catch (e: Throwable) {
                connected = false // thử lại 1 lần với kết nối mới
                lastError = e.message ?: e.javaClass.simpleName
            }
        }
        return null
    }

    // "  12% 1234/com.app: 8% user + 4% kernel"  (có thể có dấu +/- đầu dòng)
    private val cpuRe = Regex(
        """^\s*[+-]?([\d.]+)%\s+\d+/(\S+?):\s+([\d.]+)%\s+user(?:\s*\+\s*([\d.]+)%\s+kernel)?"""
    )

    // "    240,563K: com.android.systemui (pid 1234 / activities)"
    private val ramRe = Regex("""^\s*([\d,.]+)K:\s+(\S+)""")

    fun topCpu(ctx: Context, limit: Int): List<AppCpu>? {
        val text = exec(ctx, "dumpsys cpuinfo") ?: return null
        return text.lineSequence()
            .mapNotNull { cpuRe.find(it) }
            .map {
                AppCpu(
                    name = it.groupValues[2],
                    total = it.groupValues[1].toFloatOrNull() ?: 0f,
                    user = it.groupValues[3].toFloatOrNull() ?: 0f,
                    kernel = it.groupValues[4].toFloatOrNull() ?: 0f
                )
            }
            .sortedByDescending { it.total }
            .take(limit)
            .toList()
    }

    fun topRam(ctx: Context, limit: Int): List<AppRam>? {
        val text = exec(ctx, "dumpsys meminfo") ?: return null
        val out = ArrayList<AppRam>()
        var inSection = false
        for (line in text.lineSequence()) {
            if (!inSection) {
                if (line.startsWith("Total PSS by process")) inSection = true
                continue
            }
            if (line.isBlank()) break
            val m = ramRe.find(line) ?: continue
            val kb = m.groupValues[1].filter { it.isDigit() }.toLongOrNull() ?: continue
            out.add(AppRam(m.groupValues[2], kb))
        }
        return out.sortedByDescending { it.kb }.take(limit)
    }

    /** Tên hiển thị: tên app nếu tra được, không thì tên tiến trình. */
    fun label(pm: PackageManager, name: String): String = try {
        pm.getApplicationInfo(name.substringBefore(':'), 0).loadLabel(pm).toString()
    } catch (e: Exception) {
        name
    }

    fun format(pm: PackageManager, a: AppCpu): String =
        String.format(Locale.US, "%5.1f%%  %s", a.total, label(pm, a.name))

    fun format(pm: PackageManager, a: AppRam): String =
        String.format(Locale.US, "%5d MB  %s", a.kb / 1024, label(pm, a.name))

    // ---------------- Ảnh chụp hệ thống (1 lệnh shell) ----------------

    class Snap(
        val total: Int,                 // % CPU tổng, -1 nếu chưa có mẫu trước
        val cores: List<Int>,           // % từng nhân
        val mhz: Map<Int, Int>,         // MHz từng nhân
        val cpuTemp: Double?,
        val gpuTemp: Double?,
        val gpuPct: Int?,
        val gpuMhz: Int?,
        val ramKb: Map<String, Long>,   // RSS theo gói
        val fps: Int?,
        val fgPkg: String?
    )

    private val prevStat = HashMap<String, LongArray>()
    private var lastFrames = -1L
    private var lastFramesPkg = ""
    private var lastFramesT = 0L

    private val SNAP_CMD = listOf(
        "echo ==STAT; grep '^cpu' /proc/stat",
        "echo ==FREQ; for f in /sys/devices/system/cpu/cpu[0-9]*/cpufreq/scaling_cur_freq; do echo \$f \$(cat \$f); done 2>/dev/null",
        "echo ==THERM; for z in /sys/class/thermal/thermal_zone*; do echo \$(cat \$z/type) \$(cat \$z/temp); done 2>/dev/null",
        "echo ==GPU; cat /sys/kernel/ged/hal/gpu_utilization 2>/dev/null",
        "echo ==GPUF; for d in /sys/class/devfreq/*mali* /sys/class/devfreq/*gpu*; do echo \$(cat \$d/cur_freq) \$(cat \$d/load); done 2>/dev/null",
        "echo ==RAM; ps -A -o RSS,NAME 2>/dev/null",
        "echo ==FG; p=\$(dumpsys activity activities | grep -m1 ResumedActivity | sed -E 's/.* ([A-Za-z0-9_.]+)\\/.*/\\1/'); echo \$p; dumpsys gfxinfo \$p | grep 'Total frames rendered'"
    ).joinToString("\n")

    private val pkgRe = Regex("^[A-Za-z0-9_.]+$")
    private val framesRe = Regex("""Total frames rendered:\s*(\d+)""")

    /** Số liệu CPU từng nhân, MHz, nhiệt CPU/GPU, GPU, RAM từng app, FPS app đang mở. Blocking. */
    @Synchronized
    fun snapshot(ctx: Context): Snap? {
        val text = exec(ctx, SNAP_CMD) ?: return null
        val sec = HashMap<String, MutableList<String>>()
        var cur = ""
        for (line in text.lineSequence()) {
            if (line.startsWith("==")) { cur = line.trim(); sec[cur] = ArrayList(); continue }
            sec[cur]?.add(line)
        }

        var total = -1
        val coreMap = java.util.TreeMap<Int, Int>()
        for (l in sec["==STAT"].orEmpty()) {
            val f = l.trim().split(Regex("\\s+"))
            if (f.size < 6 || !f[0].startsWith("cpu")) continue
            val v = f.drop(1).mapNotNull { it.toLongOrNull() }
            if (v.size < 5) continue
            val idle = v[3] + v[4]
            val tot = v.sum()
            val prev = prevStat[f[0]]
            prevStat[f[0]] = longArrayOf(idle, tot)
            if (prev == null) continue
            val dt = tot - prev[1]
            if (dt <= 0) continue
            val pct = (100 - (idle - prev[0]) * 100 / dt).toInt().coerceIn(0, 100)
            if (f[0] == "cpu") total = pct else f[0].removePrefix("cpu").toIntOrNull()?.let { coreMap[it] = pct }
        }

        val mhz = HashMap<Int, Int>()
        val idxRe = Regex("""/cpu(\d+)/""")
        for (l in sec["==FREQ"].orEmpty()) {
            val idx = idxRe.find(l)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            val khz = l.trim().substringAfterLast(' ').toLongOrNull() ?: continue
            mhz[idx] = (khz / 1000).toInt()
        }

        var cpuT: Double? = null
        var gpuT: Double? = null
        for (l in sec["==THERM"].orEmpty()) {
            val parts = l.trim().split(' ')
            if (parts.size < 2) continue
            val raw = parts.last().toLongOrNull() ?: continue
            val c = if (raw > 1000) raw / 1000.0 else raw.toDouble()
            if (c !in 10.0..120.0) continue
            val type = parts.dropLast(1).joinToString(" ").lowercase()
            if (type.contains("gpu")) { if (gpuT == null || c > gpuT) gpuT = c }
            else if (type.contains("cpu") || type.contains("soc") || type.contains("tsens") || type.contains("big") || type.contains("little")) {
                if (cpuT == null || c > cpuT) cpuT = c
            }
        }

        var gpuPct: Int? = sec["==GPU"].orEmpty().firstOrNull()?.let { l ->
            Regex("\\d+").find(l)?.value?.toIntOrNull()?.takeIf { it in 0..100 }
        }
        var gpuMhz: Int? = null
        for (l in sec["==GPUF"].orEmpty()) {
            val n = l.trim().split(' ').mapNotNull { it.toLongOrNull() }
            if (n.isNotEmpty() && n[0] > 0) gpuMhz = (if (n[0] > 1_000_000) n[0] / 1_000_000 else n[0] / 1000).toInt()
            if (gpuPct == null && n.size >= 2 && n[1] in 0..100) gpuPct = n[1].toInt()
        }

        val ram = HashMap<String, Long>()
        for (l in sec["==RAM"].orEmpty()) {
            val m = Regex("""^\s*(\d+)\s+(\S+)""").find(l) ?: continue
            val name = m.groupValues[2].substringBefore(':')
            if (!name.contains('.')) continue
            ram[name] = (ram[name] ?: 0L) + (m.groupValues[1].toLongOrNull() ?: 0L)
        }

        val fg = sec["==FG"].orEmpty()
        val pkg = fg.firstOrNull()?.trim()?.takeIf { pkgRe.matches(it) }
        var fps: Int? = null
        val frames = fg.drop(1).firstNotNullOfOrNull { framesRe.find(it)?.groupValues?.get(1)?.toLongOrNull() }
        val now = SystemClock.elapsedRealtime()
        if (pkg != null && frames != null) {
            if (pkg == lastFramesPkg && lastFrames >= 0 && now > lastFramesT) {
                fps = ((frames - lastFrames) * 1000.0 / (now - lastFramesT)).toInt().coerceIn(0, 240)
            }
            lastFrames = frames; lastFramesPkg = pkg; lastFramesT = now
        } else {
            lastFrames = -1
        }

        return Snap(total, coreMap.values.toList(), mhz, cpuT, gpuT, gpuPct, gpuMhz, ram, fps, pkg)
    }

    /** %CPU theo gói (gộp các tiến trình con). */
    fun cpuByPkg(ctx: Context): Map<String, Float>? {
        val l = topCpu(ctx, 300) ?: return null
        val m = HashMap<String, Float>()
        for (a in l) {
            val k = a.name.substringBefore(':')
            m[k] = (m[k] ?: 0f) + a.total
        }
        return m
    }
}
