package com.teicrypto.syswidget

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.wifi.WifiManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.view.WindowManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Thông tin phần cứng chi tiết. `basic` không cần quyền đặc biệt; `deep` cần ADB tích hợp. */
object HardwareInfo {

    @Volatile private var gl: Triple<String, String, String>? = null

    private val corePart = mapOf(
        0xd03 to "Cortex-A53", 0xd04 to "Cortex-A35", 0xd05 to "Cortex-A55", 0xd07 to "Cortex-A57",
        0xd08 to "Cortex-A72", 0xd09 to "Cortex-A73", 0xd0a to "Cortex-A75", 0xd0b to "Cortex-A76",
        0xd0d to "Cortex-A77", 0xd41 to "Cortex-A78", 0xd44 to "Cortex-X1", 0xd46 to "Cortex-A510",
        0xd47 to "Cortex-A710", 0xd48 to "Cortex-X2", 0xd4d to "Cortex-A715", 0xd4e to "Cortex-X3",
        0xd80 to "Cortex-A520", 0xd81 to "Cortex-A720", 0xd82 to "Cortex-X4", 0xd85 to "Cortex-X925"
    )

    private fun read(path: String): String? = try {
        File(path).readText().trim().ifEmpty { null }
    } catch (e: Throwable) {
        null
    }

    private fun gb(b: Long) = String.format(Locale.US, "%.2f GB", b / 1073741824.0)

    private fun glStrings(): Triple<String, String, String>? {
        gl?.let { return it }
        return try {
            val d = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val v = IntArray(2)
            EGL14.eglInitialize(d, v, 0, v, 1)
            val attr = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE
            )
            val cfg = arrayOfNulls<EGLConfig>(1)
            val n = IntArray(1)
            EGL14.eglChooseConfig(d, attr, 0, cfg, 0, 1, n, 0)
            val c = EGL14.eglCreateContext(
                d, cfg[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
            )
            val s = EGL14.eglCreatePbufferSurface(
                d, cfg[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
            )
            EGL14.eglMakeCurrent(d, s, s, c)
            val r = Triple(
                GLES20.glGetString(GLES20.GL_RENDERER) ?: "?",
                GLES20.glGetString(GLES20.GL_VENDOR) ?: "?",
                GLES20.glGetString(GLES20.GL_VERSION) ?: "?"
            )
            EGL14.eglMakeCurrent(d, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(d, s)
            EGL14.eglDestroyContext(d, c)
            gl = r
            r
        } catch (e: Throwable) {
            null
        }
    }

    private fun section(sb: StringBuilder, title: String) {
        if (sb.isNotEmpty()) sb.append('\n')
        sb.append("── ").append(title).append(" ──\n")
    }

    private fun kv(sb: StringBuilder, k: String, v: String?) {
        if (!v.isNullOrBlank()) sb.append(k).append(": ").append(v).append('\n')
    }

    /** Chạy ở luồng nền (có truy vấn EGL, camera, cảm biến). */
    fun basic(ctx: Context, wm: WindowManager): String {
        val sb = StringBuilder()
        val pm = ctx.packageManager

        section(sb, "Device")
        kv(sb, "Model", "${Build.MANUFACTURER} ${Build.MODEL}")
        kv(sb, "Brand / Device", "${Build.BRAND} / ${Build.DEVICE}")
        kv(sb, "Board", Build.BOARD)
        kv(sb, "Hardware", Build.HARDWARE)
        kv(sb, "Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        kv(sb, "Security patch", Build.VERSION.SECURITY_PATCH)
        kv(sb, "Build", Build.DISPLAY)
        kv(sb, "Build time", try { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(Build.TIME)) } catch (e: Exception) { null })
        kv(sb, "Bootloader", Build.BOOTLOADER)
        kv(sb, "Kernel", System.getProperty("os.version"))
        kv(sb, "Fingerprint", Build.FINGERPRINT)

        section(sb, "CPU")
        if (Build.VERSION.SDK_INT >= 31) kv(sb, "SoC", "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
        kv(sb, "ABI", Build.SUPPORTED_ABIS.joinToString(", "))
        val cores = Runtime.getRuntime().availableProcessors()
        kv(sb, "Cores", cores.toString())
        val cpuinfo = read("/proc/cpuinfo")
        if (cpuinfo != null) {
            val parts = cpuinfo.lineSequence().filter { it.startsWith("CPU part") }
                .mapNotNull { it.substringAfter(':').trim().removePrefix("0x").toIntOrNull(16) }.toList()
            if (parts.isNotEmpty()) {
                val groups = parts.groupingBy { corePart[it] ?: "0x" + it.toString(16) }.eachCount()
                kv(sb, "Core types", groups.entries.joinToString(", ") { "${it.value}x ${it.key}" })
            }
            cpuinfo.lineSequence().firstOrNull { it.startsWith("Hardware") }
                ?.let { kv(sb, "Chip", it.substringAfter(':').trim()) }
            cpuinfo.lineSequence().firstOrNull { it.startsWith("Features") }
                ?.let { kv(sb, "Features", it.substringAfter(':').trim()) }
        }
        val freqs = (0 until cores).mapNotNull { i ->
            val mx = read("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")?.toLongOrNull() ?: return@mapNotNull null
            val mn = read("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_min_freq")?.toLongOrNull()
            "C$i ${(mn ?: 0) / 1000}-${mx / 1000} MHz"
        }
        if (freqs.isNotEmpty()) kv(sb, "Freq range", freqs.joinToString("\n  ", "\n  "))

        section(sb, "GPU")
        glStrings()?.let {
            kv(sb, "Renderer", it.first)
            kv(sb, "Vendor", it.second)
            kv(sb, "OpenGL ES", it.third)
        }
        try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            kv(sb, "GLES (reported)", am.deviceConfigurationInfo.glEsVersion)
        } catch (e: Throwable) { }
        pm.systemAvailableFeatures.firstOrNull { it.name == "android.hardware.vulkan.version" }?.let {
            val v = it.version
            kv(sb, "Vulkan", "${v shr 22}.${(v shr 12) and 0x3ff}")
        }

        section(sb, "Memory")
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        kv(sb, "RAM total", gb(mi.totalMem))
        kv(sb, "RAM available", gb(mi.availMem))
        read("/proc/meminfo")?.lineSequence()?.forEach { l ->
            val n = l.substringBefore(':')
            if (n == "SwapTotal" || n == "SwapFree" || n == "Cached" || n == "Buffers") {
                val kb = l.substringAfter(':').trim().substringBefore(' ').toLongOrNull()
                if (kb != null) kv(sb, n, gb(kb * 1024))
            }
        }
        kv(sb, "Low-RAM device", if (am.isLowRamDevice) "yes" else "no")

        section(sb, "Storage")
        try {
            val sf = StatFs(Environment.getDataDirectory().path)
            kv(sb, "Internal total", gb(sf.totalBytes))
            kv(sb, "Internal free", gb(sf.availableBytes))
        } catch (e: Throwable) { }

        section(sb, "Display")
        try {
            val dm = ctx.resources.displayMetrics
            @Suppress("DEPRECATION")
            val disp = wm.defaultDisplay
            kv(sb, "Resolution", "${dm.widthPixels} x ${dm.heightPixels}")
            kv(sb, "Density", "${dm.densityDpi} dpi (x${dm.density})")
            val diag = Math.hypot((dm.widthPixels / dm.xdpi).toDouble(), (dm.heightPixels / dm.ydpi).toDouble())
            kv(sb, "Size (approx)", String.format(Locale.US, "%.1f inch", diag))
            kv(sb, "Refresh", String.format(Locale.US, "%.0f Hz", disp.refreshRate))
            val modes = disp.supportedModes.map { String.format(Locale.US, "%.0f", it.refreshRate) }.distinct()
            kv(sb, "Supported Hz", modes.joinToString(", "))
            kv(sb, "Wide color", if (disp.isWideColorGamut) "yes" else "no")
            val hdr = disp.hdrCapabilities?.supportedHdrTypes?.map {
                when (it) { 1 -> "Dolby Vision"; 2 -> "HDR10"; 3 -> "HLG"; 4 -> "HDR10+"; else -> "$it" }
            }
            kv(sb, "HDR", if (hdr.isNullOrEmpty()) "no" else hdr.joinToString(", "))
        } catch (e: Throwable) { }

        section(sb, "Battery")
        try {
            val bi: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (bi != null) {
                kv(sb, "Technology", bi.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY))
                kv(sb, "Health", when (bi.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)) {
                    BatteryManager.BATTERY_HEALTH_GOOD -> "good"
                    BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
                    BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
                    BatteryManager.BATTERY_HEALTH_COLD -> "cold"
                    BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over voltage"
                    else -> "unknown"
                })
                kv(sb, "Voltage", "${bi.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)} mV")
                kv(sb, "Temperature", "${bi.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0} °C")
                kv(sb, "Plugged", when (bi.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
                    BatteryManager.BATTERY_PLUGGED_AC -> "AC"
                    BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                    BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
                    else -> "no"
                })
                if (Build.VERSION.SDK_INT >= 34) {
                    val cyc = bi.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1)
                    if (cyc >= 0) kv(sb, "Cycle count", cyc.toString())
                }
            }
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val cc = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            if (cc > 0) kv(sb, "Charge counter", "${cc / 1000} mAh")
        } catch (e: Throwable) { }

        section(sb, "Cameras")
        try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            for (id in cm.cameraIdList) {
                val c = cm.getCameraCharacteristics(id)
                val face = when (c.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "front"
                    CameraCharacteristics.LENS_FACING_BACK -> "back"
                    else -> "ext"
                }
                val px = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                val mp = if (px != null) String.format(Locale.US, "%.1f MP", px.width * px.height / 1e6) else "?"
                val fl = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
                val ap = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull()
                kv(sb, "#$id $face", "$mp" + (ap?.let { " f/$it" } ?: "") + (fl?.let { " ${it}mm" } ?: ""))
            }
        } catch (e: Throwable) { }

        section(sb, "Connectivity")
        try {
            val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            kv(sb, "Wi-Fi 5 GHz", if (wifi.is5GHzBandSupported) "yes" else "no")
            if (Build.VERSION.SDK_INT >= 30) kv(sb, "Wi-Fi 6 GHz", if (wifi.is6GHzBandSupported) "yes" else "no")
        } catch (e: Throwable) { }
        val feats = listOf(
            "NFC" to PackageManager.FEATURE_NFC,
            "Bluetooth LE" to PackageManager.FEATURE_BLUETOOTH_LE,
            "GPS" to PackageManager.FEATURE_LOCATION_GPS,
            "Wi-Fi Direct" to PackageManager.FEATURE_WIFI_DIRECT,
            "USB host" to PackageManager.FEATURE_USB_HOST,
            "Fingerprint" to PackageManager.FEATURE_FINGERPRINT,
            "Telephony" to PackageManager.FEATURE_TELEPHONY,
            "IR" to PackageManager.FEATURE_CONSUMER_IR
        )
        kv(sb, "Features", feats.filter { pm.hasSystemFeature(it.second) }.joinToString(", ") { it.first })

        section(sb, "Sensors")
        try {
            val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val list = sm.getSensorList(Sensor.TYPE_ALL)
            kv(sb, "Count", list.size.toString())
            list.map { it.name }.distinct().take(40).forEach { sb.append("  ").append(it).append('\n') }
        } catch (e: Throwable) { }

        return sb.toString().trimEnd()
    }

    private val DEEP_CMD = listOf(
        "echo ==PROP; for k in ro.board.platform ro.hardware.chipname ro.soc.model ro.soc.manufacturer ro.hardware.egl ro.hardware.vulkan gsm.version.baseband ro.product.first_api_level ro.boot.hardware.sku ro.build.version.incremental ro.product.cpu.abilist; do echo \$k=\$(getprop \$k); done",
        "echo ==GOV; for f in /sys/devices/system/cpu/cpu[0-9]*/cpufreq/scaling_governor; do echo \$f \$(cat \$f); done 2>/dev/null",
        "echo ==AVAIL; for f in /sys/devices/system/cpu/cpu[0-9]*/cpufreq/scaling_available_frequencies; do echo \$f \$(cat \$f); done 2>/dev/null",
        "echo ==GPU; for d in /sys/class/devfreq/*mali* /sys/class/devfreq/*gpu*; do echo \$d; echo avail \$(cat \$d/available_frequencies); echo gov \$(cat \$d/governor); done 2>/dev/null",
        "echo ==BAT; for k in cycle_count charge_full charge_full_design capacity health current_now voltage_now temp; do echo \$k=\$(cat /sys/class/power_supply/battery/\$k 2>/dev/null); done",
        "echo ==DISK; df -h /data 2>/dev/null | tail -n 1",
        "echo ==DISP; dumpsys display | grep -m1 -E 'mBaseDisplayInfo|DisplayDeviceInfo'",
        "echo ==THERMN; ls /sys/class/thermal | grep -c thermal_zone"
    ).joinToString("\n")

    /** Thông tin chỉ shell mới đọc được. Cần đã kết nối ADB. Blocking. */
    fun deep(ctx: Context): String? {
        val text = PrivShell.run(ctx, DEEP_CMD) ?: return null
        val sec = HashMap<String, MutableList<String>>()
        var cur = ""
        for (l in text.lineSequence()) {
            if (l.startsWith("==")) { cur = l.trim(); sec[cur] = ArrayList(); continue }
            if (l.isNotBlank()) sec[cur]?.add(l.trim())
        }
        val sb = StringBuilder()
        section(sb, "Deep (ADB)")
        val prop = sec["==PROP"].orEmpty().associate { it.substringBefore('=') to it.substringAfter('=', "") }
        kv(sb, "Platform", prop["ro.board.platform"])
        kv(sb, "Chip name", prop["ro.hardware.chipname"])
        kv(sb, "SoC", listOf(prop["ro.soc.manufacturer"], prop["ro.soc.model"]).filter { !it.isNullOrBlank() }.joinToString(" "))
        kv(sb, "EGL / Vulkan driver", listOf(prop["ro.hardware.egl"], prop["ro.hardware.vulkan"]).filter { !it.isNullOrBlank() }.joinToString(" / "))
        kv(sb, "Baseband", prop["gsm.version.baseband"])
        kv(sb, "First API level", prop["ro.product.first_api_level"])
        kv(sb, "SKU", prop["ro.boot.hardware.sku"])
        kv(sb, "Incremental", prop["ro.build.version.incremental"])

        val gov = sec["==GOV"].orEmpty().map { it.substringAfterLast(' ') }.distinct()
        kv(sb, "CPU governor", gov.joinToString(", "))
        val avail = sec["==AVAIL"].orEmpty().map { it.substringAfter(' ').trim() }.distinct()
        avail.forEach { a ->
            val mhz = a.split(' ').mapNotNull { it.toLongOrNull() }.map { it / 1000 }
            if (mhz.isNotEmpty()) sb.append("CPU steps (MHz): ").append(mhz.joinToString(", ")).append('\n')
        }

        val g = sec["==GPU"].orEmpty()
        g.firstOrNull { it.startsWith("/sys") }?.let { kv(sb, "GPU devfreq", it.substringAfterLast('/')) }
        g.firstOrNull { it.startsWith("avail ") }?.let { l ->
            val mhz = l.removePrefix("avail ").split(' ').mapNotNull { it.toLongOrNull() }
                .map { if (it > 1_000_000) it / 1_000_000 else it / 1000 }
            if (mhz.isNotEmpty()) kv(sb, "GPU steps (MHz)", mhz.joinToString(", "))
        }
        g.firstOrNull { it.startsWith("gov ") }?.let { kv(sb, "GPU governor", it.removePrefix("gov ")) }

        val bat = sec["==BAT"].orEmpty().associate { it.substringBefore('=') to it.substringAfter('=', "") }
        bat["cycle_count"]?.takeIf { it.isNotBlank() }?.let { kv(sb, "Battery cycles", it) }
        val full = bat["charge_full"]?.toLongOrNull()
        val design = bat["charge_full_design"]?.toLongOrNull()
        if (full != null && design != null && design > 0) {
            kv(sb, "Battery capacity", "${full / 1000} / ${design / 1000} mAh (${full * 100 / design}%)")
        }
        kv(sb, "Battery sysfs health", bat["health"])

        sec["==DISK"]?.firstOrNull()?.let { kv(sb, "Data partition", it) }
        sec["==THERMN"]?.firstOrNull()?.let { kv(sb, "Thermal zones", it) }
        return sb.toString().trimEnd()
    }
}
