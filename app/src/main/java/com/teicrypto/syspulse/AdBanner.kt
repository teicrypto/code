package com.teicrypto.syspulse

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds

/** Banner cố định ở đáy một màn hình trong app (Activity thật, không phải cửa sổ nổi). Ẩn khi là Premium. */
class AdBanner private constructor(private val a: Activity, private val box: FrameLayout, private val view: AdView) {
    private val handler = Handler(Looper.getMainLooper())

    init {
        view.adListener = object : AdListener() {
            override fun onAdFailedToLoad(e: LoadAdError) {
                // Chưa có quảng cáo: thử lại sau 60 giây
                handler.postDelayed({
                    if (!a.isDestroyed && !PlayBilling.hasPremium(a)) view.loadAd(AdRequest.Builder().build())
                }, 60_000L)
            }
        }
        view.loadAd(AdRequest.Builder().build())
    }

    fun resume() {
        view.resume()
        box.visibility = if (PlayBilling.hasPremium(a)) View.GONE else View.VISIBLE
    }

    fun pause() = view.pause()

    fun destroy() {
        handler.removeCallbacksAndMessages(null)
        view.destroy()
    }

    companion object {
        /** Thêm khung banner (cao 56dp) vào cuối `outer` (LinearLayout dọc). */
        fun attach(a: Activity, outer: LinearLayout): AdBanner {
            MobileAds.initialize(a) {}
            PlayBilling.init(a)
            val box = FrameLayout(a)
            val av = AdView(a).apply {
                setAdSize(AdSize.BANNER)
                adUnitId = a.getString(R.string.admob_banner_id)
            }
            box.addView(av, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER
            ))
            outer.addView(box, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (56 * a.resources.displayMetrics.density).toInt()
            ))
            return AdBanner(a, box, av)
        }
    }
}
