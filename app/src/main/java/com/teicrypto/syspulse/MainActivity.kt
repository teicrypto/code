package com.teicrypto.syspulse

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
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds

/**
 * Màn hình chính chỉ để hướng dẫn cấp quyền và bật popup Task Manager.
 * Mọi thông số, biểu đồ và kết nối ADB nằm trong popup.
 */
class MainActivity : Activity() {

    override fun onContentChanged() {
        super.onContentChanged()
        Edge.apply(this)
    }

    private var adView: AdView? = null
    private lateinit var adBox: android.widget.FrameLayout
    private lateinit var premText: TextView
    private lateinit var premBtn: Button
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val billingListener = Runnable { refreshPremium() }

    private fun refreshPremium() {
        if (!::premBtn.isInitialized) return
        val prem = PlayBilling.hasPremium(this)
        if (prem) {
            premText.text = getString(R.string.premium_active)
            premBtn.text = getString(R.string.premium_manage)
            premBtn.setOnClickListener { PlayBilling.manage(this) }
        } else {
            premText.text = getString(R.string.premium_benefit)
            val price = PlayBilling.priceText()
            premBtn.text = if (price != null) getString(R.string.premium_upgrade_fmt, price) else getString(R.string.premium_upgrade)
            premBtn.setOnClickListener { PlayBilling.launch(this) }
        }
        adBox.visibility = if (prem) android.view.View.GONE else android.view.View.VISIBLE
    }
    override fun onStart() {
        super.onStart()
        PlayBilling.addListener(billingListener)
    }

    override fun onStop() {
        PlayBilling.removeListener(billingListener)
        super.onStop()
    }

    private lateinit var permStatus: TextView
    private lateinit var permBtn: Button
    private lateinit var openBtn: Button
    private var createdLang = ""
    private var waitingPermission = false

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.wrap(base))
    }

    private fun refreshState() {
        val ok = Settings.canDrawOverlays(this)
        permStatus.text = getString(if (ok) R.string.onb_overlay_ok else R.string.onb_overlay_need)
        permStatus.setTextColor(Color.parseColor(if (ok) "#7CE0C3" else "#F2A65A"))
        permBtn.visibility = if (ok) android.view.View.GONE else android.view.View.VISIBLE
        openBtn.isEnabled = ok
        openBtn.alpha = if (ok) 1f else 0.4f
    }

    private fun startPopup() {
        if (!Settings.canDrawOverlays(this)) return
        startForegroundService(Intent(this, OverlayService::class.java))
        moveTaskToBack(true) // về màn hình chính để thấy popup
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createdLang = Prefs.lang(this)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            setBackgroundColor(Color.parseColor("#111418"))
        }
        root.addView(TextView(this).apply {
            text = "SysPulse"
            textSize = 26f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.onb_intro)
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(0, pad / 2, 0, pad)
        })

        // Chọn ngôn ngữ ngay ở màn hình đầu
        root.addView(TextView(this).apply {
            text = getString(R.string.settings_language)
            textSize = 13f
            setTextColor(Color.LTGRAY)
        })
        val langRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        for ((code, label) in listOf("vi" to "Tiếng Việt", "en" to "English", "zh" to "中文")) {
            langRow.addView(Button(this).apply {
                text = label
                isEnabled = Prefs.lang(this@MainActivity) != code
                setOnClickListener {
                    Prefs.sp(this@MainActivity).edit().putString(Prefs.LANG, code).apply()
                    recreate()
                }
            })
        }
        root.addView(langRow)

        // Gói Premium (Google Play Billing)
        premText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#F2C94C"))
            setPadding(0, pad / 2, 0, 0)
        }
        premBtn = Button(this)
        root.addView(premText)
        root.addView(premBtn)

        permStatus = TextView(this).apply {
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, pad / 2, 0, pad / 2)
        }
        root.addView(permStatus)

        permBtn = Button(this).apply {
            text = getString(R.string.onb_overlay_btn)
            setOnClickListener {
                waitingPermission = true
                Toast.makeText(this@MainActivity, getString(R.string.toast_grant_overlay), Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
        root.addView(permBtn)

        openBtn = Button(this).apply {
            text = getString(R.string.onb_open_popup)
            setOnClickListener { startPopup() }
        }
        root.addView(openBtn)

        // Hai màn hình trong app (có banner ở đáy)
        root.addView(Button(this).apply {
            text = getString(R.string.tab_proc)
            setOnClickListener { startActivity(Intent(this@MainActivity, ProcessesActivity::class.java)) }
        })
        root.addView(Button(this).apply {
            text = getString(R.string.tab_hw)
            setOnClickListener { startActivity(Intent(this@MainActivity, DeviceInfoActivity::class.java)) }
        })

        root.addView(Button(this).apply {
            text = getString(R.string.btn_open_adb)
            setOnClickListener { startActivity(Intent(this@MainActivity, AdbSetupActivity::class.java)) }
        })

        root.addView(Button(this).apply {
            text = getString(R.string.onb_stop_popup)
            setOnClickListener { stopService(Intent(this@MainActivity, OverlayService::class.java)) }
        })

        root.addView(TextView(this).apply {
            text = getString(R.string.onb_tip)
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(0, pad, 0, pad)
        })

        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(Button(this).apply {
            text = getString(R.string.btn_start_updates)
            setOnClickListener { startForegroundService(Intent(this@MainActivity, StatsService::class.java)) }
        })
        row.addView(Button(this).apply {
            text = getString(R.string.btn_stop)
            setOnClickListener {
                Prefs.sp(this@MainActivity).edit().putBoolean(Prefs.STATUS_BAR, false).apply()
                stopService(Intent(this@MainActivity, StatsService::class.java))
            }
        })
        row.addView(Button(this).apply {
            text = getString(R.string.btn_settings)
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        })
        root.addView(row)

        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#111418"))
        }
        outer.addView(
            ScrollView(this).apply { isFillViewport = true; addView(root) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        // Banner AdMob luôn nằm ở đáy màn hình chính (giữ sẵn chỗ 50dp, ẩn khi là Premium)
        MobileAds.initialize(this) {}
        adBox = android.widget.FrameLayout(this)
        val av = AdView(this).apply {
            setAdSize(AdSize.BANNER)
            adUnitId = getString(R.string.admob_banner_id)
        }
        av.adListener = object : AdListener() {
            override fun onAdFailedToLoad(e: LoadAdError) {
                // Chưa có quảng cáo (vd đơn vị mới): thử lại sau 60 giây
                handler.postDelayed({
                    if (!isDestroyed && !PlayBilling.hasPremium(this@MainActivity)) av.loadAd(AdRequest.Builder().build())
                }, 60_000L)
            }
        }
        adView = av
        adBox.addView(av, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER
        ))
        outer.addView(adBox, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (56 * resources.displayMetrics.density).toInt()
        ))
        av.loadAd(AdRequest.Builder().build())
        PlayBilling.init(this)
        setContentView(outer)


        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        if (Prefs.lang(this) != createdLang) {
            recreate()
            return
        }
        adView?.resume()
        PlayBilling.refresh()
        refreshPremium()
        refreshState()
        // Vừa cấp quyền xong: tự bật popup
        if (waitingPermission && Settings.canDrawOverlays(this)) {
            waitingPermission = false
            startPopup()
        }
    }

    override fun onPause() {
        adView?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        adView?.destroy()
        super.onDestroy()
    }
}
