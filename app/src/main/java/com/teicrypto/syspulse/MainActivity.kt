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
import com.google.android.gms.ads.AdRequest
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
    private lateinit var accText: TextView
    private lateinit var accBtn: Button
    private val authListener = com.google.firebase.auth.FirebaseAuth.AuthStateListener { runOnUiThread { refreshAccount() } }

    private fun refreshAccount() {
        if (!::accText.isInitialized) return
        val u = Account.user
        if (u == null) {
            accText.text = getString(R.string.acc_signed_out_hint)
            accBtn.text = getString(R.string.acc_signin) + " / " + getString(R.string.acc_register)
            accBtn.setOnClickListener { startActivity(Intent(this, AuthActivity::class.java)) }
        } else {
            accText.text = (u.email ?: u.uid) + " · " + Account.planLabel(this, Account.cached)
            accBtn.text = getString(R.string.acc_open_profile)
            accBtn.setOnClickListener { startActivity(Intent(this, ProfileActivity::class.java)) }
            Account.loadPlan { _, _ -> runOnUiThread { if (Account.user != null) accText.text = (Account.user?.email ?: "") + " · " + Account.planLabel(this, Account.cached) } }
        }
    }

    override fun onStart() {
        super.onStart()
        Account.auth.addAuthStateListener(authListener)
    }

    override fun onStop() {
        Account.auth.removeAuthStateListener(authListener)
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

        // Thẻ tài khoản: trạng thái đăng nhập đồng bộ qua AuthStateListener
        accText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#7CE0C3"))
            setPadding(0, pad / 2, 0, 0)
        }
        accBtn = Button(this)
        root.addView(accText)
        root.addView(accBtn)

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
        // Banner AdMob ở đáy màn hình chính
        MobileAds.initialize(this) {}
        adView = AdView(this).apply {
            setAdSize(AdSize.BANNER)
            adUnitId = getString(R.string.admob_banner_id)
            loadAd(AdRequest.Builder().build())
        }
        outer.addView(adView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER_HORIZONTAL })
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
        refreshState()
        refreshAccount()
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
        adView?.destroy()
        super.onDestroy()
    }
}
