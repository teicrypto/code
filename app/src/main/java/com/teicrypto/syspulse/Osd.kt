package com.teicrypto.syspulse

import android.content.Context

/** Danh sách chỉ số hiển thị trên popup thu nhỏ (kiểu MSI Afterburner OSD) và cài đặt bật/tắt từng chỉ số. */
object Osd {
    class Def(
        val id: String,
        val label: Int,
        val def: Boolean,
        val color: String,
        val graph: Boolean = false,   // có biểu đồ nhỏ đi kèm khi bật "Hiện biểu đồ"
        val gMax: Float? = 100f,
        val gMin: Float = 0f
    )

    const val GRAPHS = "osd_graphs"
    const val INTERVAL = "osd_interval"

    val all = listOf(
        Def("cpu_pct", R.string.m_cpu_pct, true, "#7CE0C3", true),
        Def("cpu_temp", R.string.m_cpu_temp, true, "#FF7A7A"),
        Def("cpu_mhz", R.string.m_cpu_mhz, false, "#7CE0C3"),
        Def("cpu_cores", R.string.m_cpu_cores, false, "#7CE0C3"),
        Def("cpu_core_mhz", R.string.m_cpu_core_mhz, false, "#7CE0C3"),
        Def("gpu_pct", R.string.m_gpu_pct, true, "#F2A65A", true),
        Def("gpu_temp", R.string.m_gpu_temp, true, "#FF7A7A"),
        Def("gpu_mhz", R.string.m_gpu_mhz, false, "#F2A65A"),
        Def("ram", R.string.m_ram, true, "#6FA8FF", true),
        Def("fps", R.string.m_fps, true, "#FFE066", true, 120f),
        Def("frametime", R.string.m_frametime, false, "#FFE066"),
        Def("batt", R.string.m_batt, false, "#9BE564", true),
        Def("batt_temp", R.string.m_batt_temp, false, "#FF7A7A"),
        Def("power", R.string.m_power, false, "#9BE564"),
        Def("net_down", R.string.m_net_down, false, "#C792EA", true, null),
        Def("net_up", R.string.m_net_up, false, "#C792EA"),
        Def("storage", R.string.m_storage, false, "#6FA8FF"),
        Def("clock", R.string.m_clock, false, "#FFFFFF"),
        Def("uptime", R.string.m_uptime, false, "#FFFFFF")
    )

    fun key(id: String) = "osd_$id"
    fun on(ctx: Context, d: Def) = Prefs.sp(ctx).getBoolean(key(d.id), d.def)
    fun graphs(ctx: Context) = Prefs.sp(ctx).getBoolean(GRAPHS, false)
    fun interval(ctx: Context) = Prefs.sp(ctx).getInt(INTERVAL, 1000)
}
