package com.teicrypto.syspulse

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.widget.Toast
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback

/**
 * Cổng quảng cáo có thưởng cho công tắc trong Cài đặt.
 * - Bật công tắc: phải xem hết 1 quảng cáo có thưởng (người dùng đồng ý trước qua hộp thoại).
 * - Sau khi xem xong: 30 phút không phải xem lại (không hiện bộ đếm).
 * - Tắt công tắc: không cần quảng cáo.
 */
object AdGate {
    private const val KEY_UNTIL = "ad_free_until"
    private const val FREE_MS = 30L * 60 * 1000

    private var ad: RewardedAd? = null
    private var loading = false

    /** Còn trong 30 phút miễn quảng cáo? Chống chỉnh đồng hồ lùi: hạn không bao giờ vượt quá 30 phút tính từ bây giờ. */
    fun isFree(c: Context): Boolean {
        val until = Prefs.sp(c).getLong(KEY_UNTIL, 0L)
        val now = System.currentTimeMillis()
        return until > now && until - now <= FREE_MS
    }

    private fun markWatched(c: Context) {
        Prefs.sp(c).edit().putLong(KEY_UNTIL, System.currentTimeMillis() + FREE_MS).apply()
    }

    fun preload(c: Context) {
        if (ad != null || loading) return
        loading = true
        RewardedAd.load(
            c.applicationContext,
            c.getString(R.string.admob_rewarded_id),
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(r: RewardedAd) { ad = r; loading = false }
                override fun onAdFailedToLoad(e: LoadAdError) { ad = null; loading = false }
            }
        )
    }

    /** Gọi khi người dùng muốn bật một công tắc. onGranted chạy khi được phép bật. */
    fun require(a: Activity, onGranted: () -> Unit, onDenied: () -> Unit = {}) {
        if (isFree(a)) { onGranted(); return }
        AlertDialog.Builder(a)
            .setTitle(R.string.ad_gate_title)
            .setMessage(R.string.ad_gate_msg)
            .setPositiveButton(R.string.ad_gate_watch) { _, _ -> show(a, onGranted, onDenied) }
            .setNegativeButton(R.string.ad_gate_cancel) { _, _ -> onDenied() }
            .setOnCancelListener { onDenied() }
            .show()
    }

    private fun show(a: Activity, onGranted: () -> Unit, onDenied: () -> Unit) {
        val r = ad
        if (r == null) {
            Toast.makeText(a, R.string.ad_not_ready, Toast.LENGTH_SHORT).show()
            preload(a)
            onDenied()
            return
        }
        var earned = false
        r.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                ad = null
                preload(a)
                if (earned) {
                    markWatched(a)
                    onGranted()
                } else {
                    Toast.makeText(a, R.string.ad_not_completed, Toast.LENGTH_SHORT).show()
                    onDenied()
                }
            }
            override fun onAdFailedToShowFullScreenContent(e: AdError) {
                ad = null
                preload(a)
                Toast.makeText(a, R.string.ad_not_ready, Toast.LENGTH_SHORT).show()
                onDenied()
            }
        }
        r.show(a) { earned = true }
    }
}
