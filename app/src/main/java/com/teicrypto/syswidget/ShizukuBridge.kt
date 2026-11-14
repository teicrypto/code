package com.teicrypto.syswidget

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/** Cầu nối tới Shizuku: chạy lệnh shell bằng quyền của Shizuku (shell hoặc root). */
object ShizukuBridge {
    const val PKG = "moe.shizuku.privileged.api"

    fun installed(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(PKG, 0)
        true
    } catch (e: Throwable) {
        false
    }

    fun running(): Boolean = try { Shizuku.pingBinder() } catch (e: Throwable) { false }

    fun granted(): Boolean = try {
        running() && !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) {
        false
    }

    /** 0 = root, 2000 = shell, -1 = không rõ. */
    fun uid(): Int = try { Shizuku.getUid() } catch (e: Throwable) { -1 }

    fun requestPermission() {
        try { Shizuku.requestPermission(100) } catch (e: Throwable) { PrivShell.log("shizuku request: $e") }
    }

    /** Shizuku 13+ để newProcess ở dạng private, gọi qua reflection. */
    private fun newProcess(cmd: Array<String>): Process {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        m.isAccessible = true
        return m.invoke(null, cmd, null, null) as Process
    }

    /** Chạy một lệnh `sh -c`, trả về stdout. Blocking. */
    fun exec(cmd: String): String? = try {
        val p = newProcess(arrayOf("sh", "-c", cmd))
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out
    } catch (e: Throwable) {
        PrivShell.lastErrorSet("shizuku exec: ${e.javaClass.simpleName}: ${e.cause?.message ?: e.message}")
        null
    }
}
