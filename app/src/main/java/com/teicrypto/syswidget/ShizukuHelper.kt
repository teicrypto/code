package com.teicrypto.syswidget

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.util.Locale

data class AppCpu(val name: String, val total: Float, val user: Float, val kernel: Float)

/**
 * Lấy danh sách app ăn CPU bằng cách chạy `dumpsys cpuinfo` qua Shizuku (quyền shell).
 * Cần cài app Shizuku, khởi động nó (Gỡ lỗi không dây hoặc root) rồi cấp quyền cho SysWidget.
 */
object ShizukuHelper {

    enum class State { NOT_RUNNING, NO_PERMISSION, READY }

    private const val REQUEST_CODE = 4242

    fun state(): State = try {
        when {
            !Shizuku.pingBinder() -> State.NOT_RUNNING
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> State.READY
            else -> State.NO_PERMISSION
        }
    } catch (e: Throwable) {
        State.NOT_RUNNING
    }

    fun requestPermission() {
        try {
            Shizuku.requestPermission(REQUEST_CODE)
        } catch (e: Throwable) {
            // Shizuku chưa chạy: giao diện đã hiện hướng dẫn
        }
    }

    // Từ Shizuku API 13, newProcess là private nên gọi qua reflection.
    private fun newProcess(cmd: Array<String>): Process {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        m.isAccessible = true
        return m.invoke(null, cmd, null, null) as Process
    }

    // "  12% 1234/com.app: 8% user + 4% kernel"  (có thể có dấu +/- đầu dòng)
    private val lineRe = Regex(
        """^\s*[+-]?([\d.]+)%\s+\d+/(\S+?):\s+([\d.]+)%\s+user(?:\s*\+\s*([\d.]+)%\s+kernel)?"""
    )

    /** Chạy blocking, gọi từ luồng nền. Trả về null nếu lỗi. */
    fun topCpu(limit: Int): List<AppCpu>? {
        if (state() != State.READY) return null
        return try {
            val p = newProcess(arrayOf("sh", "-c", "dumpsys cpuinfo"))
            val text = p.inputStream.bufferedReader().use { it.readText() }
            p.waitFor()
            text.lineSequence()
                .mapNotNull { lineRe.find(it) }
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
        } catch (e: Throwable) {
            null
        }
    }

    /** Tên hiển thị: tên app nếu tra được, không thì tên tiến trình. */
    private fun label(pm: PackageManager, name: String): String = try {
        pm.getApplicationInfo(name.substringBefore(':'), 0).loadLabel(pm).toString()
    } catch (e: Exception) {
        name
    }

    fun format(pm: PackageManager, a: AppCpu): String =
        String.format(Locale.US, "%5.1f%%  %s", a.total, label(pm, a.name))
}
