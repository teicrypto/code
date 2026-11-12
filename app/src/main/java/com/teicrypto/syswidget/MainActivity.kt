package com.teicrypto.syswidget

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var collector: StatsCollector
    private lateinit var info: TextView
    private lateinit var adView: AdView
    private lateinit var cpuChart: LineChartView
    private lateinit var gpuChart: LineChartView
    private lateinit var ramChart: LineChartView
    private lateinit var tempChart: LineChartView
    private lateinit var netChart: LineChartView
    private var createdLang = ""

    // Áp dụng ngôn ngữ đã chọn trong Cài đặt
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.wrap(base))
    }

    private val tick = object : Runnable {
        override fun run() {
            val s = collector.collect()
            fun p(x: Int) = if (x >= 0) "$x%" else "N/A"
            fun row(label: String, v: String) = label.padEnd(9) + v
            info.text = listOf(
                row("CPU", "${p(s.cpuPct)}   ${s.cpuInfo}"),
                row("GPU", "${p(s.gpuPct)}   ${s.gpuInfo}"),
                row("RAM", "${p(s.ramPct)}   ${s.ramInfo}"),
                row(getString(R.string.label_storage), "${p(s.storagePct)}   ${s.storageInfo}"),
                row(getString(R.string.label_battery), "${p(s.battPct)}   ${s.battInfo}"),
                row(getString(R.string.label_temp), s.tempInfo),
                row(getString(R.string.label_network), s.netInfo),
                s.uptime
            ).joinToString("\n")

            cpuChart.add(s.cpuPct.coerceAtLeast(0).toFloat(), p(s.cpuPct))
            gpuChart.add(s.gpuPct.coerceAtLeast(0).toFloat(), p(s.gpuPct))
            ramChart.add(s.ramPct.toFloat(), "${p(s.ramPct)}  ${s.ramInfo}")
            tempChart.add(s.tempC.toFloat(), "${s.tempC.toInt()}°C")
            netChart.add(s.downBps.toFloat(), s.netInfo)
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createdLang = Prefs.lang(this)
        collector = StatsCollector(this)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            setBackgroundColor(Color.parseColor("#111418"))
        }

        root.addView(TextView(this).apply {
            text = "SysWidget"
            textSize = 24f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.main_howto)
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(0, pad / 2, 0, pad)
        })

        info = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setTextColor(Color.parseColor("#7CE0C3"))
            setPadding(0, pad, 0, pad)
        }
        root.addView(info)

        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(Button(this).apply {
            text = getString(R.string.btn_start_updates)
            setOnClickListener {
                startForegroundService(Intent(this@MainActivity, StatsService::class.java))
            }
        })
        row.addView(Button(this).apply {
            text = getString(R.string.btn_stop)
            setOnClickListener {
                // Nếu đang hiện trên thanh trạng thái thì tắt luôn ở đây để service thật sự dừng
                Prefs.sp(this@MainActivity).edit().putBoolean(Prefs.STATUS_BAR, false).apply()
                stopService(Intent(this@MainActivity, StatsService::class.java))
            }
        })
        root.addView(row)

        // Cửa sổ nổi kiểu Task Manager
        val row2 = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row2.addView(Button(this).apply {
            text = getString(R.string.btn_start_popup)
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.toast_grant_overlay),
                        Toast.LENGTH_LONG
                    ).show()
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    )
                } else {
                    startForegroundService(Intent(this@MainActivity, OverlayService::class.java))
                }
            }
        })
        row2.addView(Button(this).apply {
            text = getString(R.string.btn_stop)
            setOnClickListener { stopService(Intent(this@MainActivity, OverlayService::class.java)) }
        })
        root.addView(row2)
        root.addView(Button(this).apply {
            text = getString(R.string.btn_usage_access)
            setOnClickListener { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        })
        root.addView(Button(this).apply {
            text = getString(R.string.btn_settings)
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        })

        // Biểu đồ lịch sử (~3 phút gần nhất)
        cpuChart = LineChartView(this, "CPU", Color.parseColor("#7CE0C3"), 100f)
        gpuChart = LineChartView(this, "GPU", Color.parseColor("#F2A65A"), 100f)
        ramChart = LineChartView(this, "RAM", Color.parseColor("#6FA8FF"), 100f)
        tempChart = LineChartView(this, getString(R.string.chart_temp), Color.parseColor("#FF7A7A"), 80f, 20f)
        netChart = LineChartView(this, getString(R.string.chart_net), Color.parseColor("#C792EA"), null)
        for (c in listOf(cpuChart, gpuChart, ramChart, tempChart, netChart)) {
            root.addView(
                c,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, (96 * resources.displayMetrics.density).toInt()
                ).apply { topMargin = pad / 2 }
            )
        }

        // Nội dung cuộn được + banner AdMob cố định ở đáy màn hình
        MobileAds.initialize(this) {}
        adView = AdView(this).apply {
            setAdSize(AdSize.BANNER)
            adUnitId = getString(R.string.admob_banner_id)
            // Báo kết quả tải quảng cáo để dễ tìm lỗi (xóa đoạn này khi phát hành)
            adListener = object : AdListener() {
                override fun onAdLoaded() {
                    Toast.makeText(this@MainActivity, getString(R.string.ad_loaded), Toast.LENGTH_SHORT).show()
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.ad_error_fmt, error.code, error.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            loadAd(AdRequest.Builder().build())
        }
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#111418"))
        }
        outer.addView(
            ScrollView(this).apply { isFillViewport = true; addView(root) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        outer.addView(
            adView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_HORIZONTAL }
        )
        setContentView(outer)

        // Mỗi lần mở app (khởi động mới, không tính xoay màn hình) hiện 1 quảng cáo toàn màn hình
        if (savedInstanceState == null) loadInterstitial()

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun loadInterstitial() {
        InterstitialAd.load(
            this,
            getString(R.string.admob_interstitial_id),
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    if (!isFinishing && !isDestroyed) ad.show(this@MainActivity)
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.ad_inter_error_fmt, error.code, error.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
    }

    override fun onResume() {
        super.onResume()
        // Vừa đổi ngôn ngữ trong Cài đặt -> dựng lại màn hình
        if (Prefs.lang(this) != createdLang) {
            recreate()
            return
        }
        handler.post(tick)
        adView.resume()
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        adView.pause()
        super.onPause()
    }

    override fun onDestroy() {
        adView.destroy()
        super.onDestroy()
    }
}
