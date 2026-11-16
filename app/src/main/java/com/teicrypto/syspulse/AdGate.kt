package com.teicrypto.syspulse

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.widget.Toast
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback

/**
 * Cổng quảng cáo có thưởng cho công tắc nâng cao trong Cài đặt.
 * - Bật công tắc: xem hết 1 quảng cáo có thưởng, hoặc dùng Premium (loại bỏ quảng cáo).
 * - Xem xong: 7 phút không phải xem lại (không hiện bộ đếm).
 * - Cần có mạng. Nếu có mạng mà HỆ THỐNG không có quảng cáo để phát: từ lần thất bại thứ MAX_FAILS liên tiếp
 *   sẽ tự mở khoá. Người dùng chủ động thoát/không xem thì KHÔNG được mở khoá.
 */
object AdGate {
    private const val KEY_UNTIL = "ad_free_until"
    private const val KEY_FAILS = "ad_fail_count"
    private const val FREE_MS = 7L * 60 * 1000
    /** Số lần liên tiếp hệ thống không có quảng cáo thì tự mở khoá (đổi số này nếu muốn). */
    private const val MAX_FAILS = 3

    private var ad: RewardedAd? = null
    private var loading = false

    fun online(c: Context): Boolean = try {
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (e: Throwable) { false }

    /** Còn trong 7 phút miễn quảng cáo (hoặc là Premium)? Hạn không bao giờ vượt quá 7 phút tính từ bây giờ. */
    fun isFree(c: Context): Boolean {
        if (PlayBilling.hasPremium(c)) return true // Premium: không bao giờ phải xem quảng cáo
        if (!Guard.trusted(c)) return false
        val until = Guard.getLong(c, KEY_UNTIL, 0L)
        val now = System.currentTimeMillis()
        return until > now && until - now <= FREE_MS
    }

    private fun markWatched(c: Context) {
        Guard.putLong(c, KEY_UNTIL, System.currentTimeMillis() + FREE_MS)
        Guard.putLong(c, KEY_FAILS, 0L)
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
        if (!online(a)) {
            Toast.makeText(a, R.string.ad_need_internet, Toast.LENGTH_LONG).show()
            onDenied()
            return
        }
        AlertDialog.Builder(a)
            .setTitle(R.string.ad_gate_title)
            .setMessage(R.string.ad_gate_msg)
            .setPositiveButton(R.string.ad_gate_watch) { _, _ -> watch(a, onGranted, onDenied) }
            .setNeutralButton(R.string.ad_gate_premium) { _, _ -> PlayBilling.launch(a); onDenied() }
            .setNegativeButton(R.string.ad_gate_cancel) { _, _ -> onDenied() }
            .setOnCancelListener { onDenied() }
            .show()
    }

    private fun watch(a: Activity, onGranted: () -> Unit, onDenied: () -> Unit) {
        val r = ad
        if (r != null) { showAd(a, r, onGranted, onDenied); return }
        // Chưa có sẵn quảng cáo: tải ngay theo yêu cầu của người dùng
        Toast.makeText(a, R.string.ad_loading, Toast.LENGTH_SHORT).show()
        RewardedAd.load(
            a.applicationContext,
            a.getString(R.string.admob_rewarded_id),
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(r: RewardedAd) { showAd(a, r, onGranted, onDenied) }
                override fun onAdFailedToLoad(e: LoadAdError) { systemNoAd(a, onGranted, onDenied) }
            }
        )
    }

    /** Hệ thống không có quảng cáo (không phải do người dùng). Đủ MAX_FAILS lần liên tiếp thì mở khoá. */
    private fun systemNoAd(a: Activity, onGranted: () -> Unit, onDenied: () -> Unit) {
        ad = null
        if (!online(a) || !Guard.trusted(a)) {
            Toast.makeText(a, R.string.ad_need_internet, Toast.LENGTH_LONG).show()
            onDenied()
            return
        }
        val fails = Guard.getLong(a, KEY_FAILS, 0L) + 1
        if (fails >= MAX_FAILS) {
            Guard.putLong(a, KEY_FAILS, 0L)
            Toast.makeText(a, R.string.ad_unlock_no_ad, Toast.LENGTH_LONG).show()
            onGranted()
        } else {
            Guard.putLong(a, KEY_FAILS, fails)
            Toast.makeText(a, a.getString(R.string.ad_not_ready_fmt, fails.toInt(), MAX_FAILS), Toast.LENGTH_LONG).show()
            onDenied()
        }
    }

    private fun showAd(a: Activity, r: RewardedAd, onGranted: () -> Unit, onDenied: () -> Unit) {
        var earned = false
        var failedToShow = false
        r.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                ad = null
                preload(a)
                if (failedToShow) return
                if (earned) {
                    markWatched(a)
                    onGranted()
                } else {
                    // Người dùng thoát giữa chừng: không mở khoá, không tính là lỗi hệ thống
                    Toast.makeText(a, R.string.ad_not_completed, Toast.LENGTH_SHORT).show()
                    onDenied()
                }
            }
            override fun onAdFailedToShowFullScreenContent(e: AdError) {
                failedToShow = true
                preload(a)
                systemNoAd(a, onGranted, onDenied)
            }
        }
        ad = null
        r.show(a) { earned = true }
    }
}
