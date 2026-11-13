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
        if (!force && !isPaired(ctx)) return false
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastAttempt < 20_000) return false
        lastAttempt = now

        val saved = Prefs.sp(ctx).getInt(KEY_PORT, 0)
        if (saved > 0 && tryConnect(ctx, saved)) return true

        val port = NsdFinder.find(ctx, CONNECT_TYPE, 6000) { false }
        if (port == null) {
            lastError = "no connect port (Wi-Fi / Wireless debugging off?)"
            return false
        }
        return tryConnect(ctx, port)
    }

    /** Chạy một lệnh shell, trả về toàn bộ output. Blocking. */
    private fun exec(ctx: Context, cmd: String): String? {
        if (!connect(ctx)) return null
        return try {
            val stream = SysAdbManager.getInstance(ctx.applicationContext).openStream("shell:$cmd")
            val text = stream.openInputStream().bufferedReader().readText()
            try {
                stream.close()
            } catch (e: Throwable) {
            }
            text
        } catch (e: Throwable) {
            connected = false // lần sau tự kết nối lại
            lastError = e.message ?: e.javaClass.simpleName
            null
        }
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
    private fun label(pm: PackageManager, name: String): String = try {
        pm.getApplicationInfo(name.substringBefore(':'), 0).loadLabel(pm).toString()
    } catch (e: Exception) {
        name
    }

    fun format(pm: PackageManager, a: AppCpu): String =
        String.format(Locale.US, "%5.1f%%  %s", a.total, label(pm, a.name))

    fun format(pm: PackageManager, a: AppRam): String =
        String.format(Locale.US, "%5d MB  %s", a.kb / 1024, label(pm, a.name))
}
