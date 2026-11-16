package com.teicrypto.syspulse

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Lớp chống chỉnh sửa ở phía ứng dụng (nâng độ khó, không phải bất khả xâm phạm):
 * 1) Kiểm tra chữ ký APK: nếu bản build được cấu hình CERT_SHA256 mà chữ ký không khớp (APK bị đóng gói lại/crack
 *    rồi ký bằng khóa khác) thì Premium và các mốc miễn quảng cáo bị vô hiệu.
 * 2) Các giá trị nhạy cảm lưu trong SharedPreferences (Premium, mốc 7 phút, bộ đếm lỗi) được ký HMAC gắn với
 *    máy + gói app, nên sửa tay file cài đặt (máy root) sẽ bị bỏ qua.
 * Giới hạn: kẻ tấn công am hiểu có thể vá mã trong APK. Muốn chắc chắn hơn cần xác minh phía máy chủ (Play Integrity).
 */
object Guard {
    @Volatile private var trustedCache: Boolean? = null

    private fun hex(b: ByteArray) = b.joinToString("") { "%02X".format(it) }

    @Suppress("DEPRECATION")
    private fun certHashes(c: Context): List<String> = try {
        val pm = c.packageManager
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            val info = pm.getPackageInfo(c.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            info.signingInfo?.apkContentsSigners?.toList().orEmpty()
        } else {
            pm.getPackageInfo(c.packageName, PackageManager.GET_SIGNATURES).signatures?.toList().orEmpty()
        }
        sigs.map { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }
    } catch (e: Throwable) { emptyList() }

    /** true nếu chữ ký APK khớp danh sách mong đợi (hoặc chưa cấu hình danh sách). */
    fun trusted(c: Context): Boolean {
        trustedCache?.let { return it }
        val expected = BuildConfig.CERT_SHA256.split(',')
            .map { it.trim().replace(":", "").uppercase() }.filter { it.isNotEmpty() }
        val ok = expected.isEmpty() || certHashes(c).any { it in expected }
        trustedCache = ok
        return ok
    }

    private fun mac(c: Context, data: String): String {
        val id = Settings.Secure.getString(c.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
        val key = MessageDigest.getInstance("SHA-256").digest("syspulse|${c.packageName}|$id".toByteArray())
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(key, "HmacSHA256"))
        return hex(m.doFinal(data.toByteArray()))
    }

    fun putLong(c: Context, k: String, v: Long) {
        Prefs.sp(c).edit().putLong(k, v).putString(k + "_s", mac(c, "$k=$v")).apply()
    }

    fun getLong(c: Context, k: String, def: Long): Long {
        val sp = Prefs.sp(c)
        if (!sp.contains(k)) return def
        val v = sp.getLong(k, def)
        return if (sp.getString(k + "_s", null) == mac(c, "$k=$v")) v else def
    }

    fun putBool(c: Context, k: String, v: Boolean) = putLong(c, k, if (v) 1L else 0L)
    fun getBool(c: Context, k: String, def: Boolean): Boolean = getLong(c, k, if (def) 1L else 0L) == 1L
}
