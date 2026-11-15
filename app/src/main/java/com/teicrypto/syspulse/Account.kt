package com.teicrypto.syspulse

import android.content.Context
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import java.text.DateFormat
import java.util.Date

/**
 * Hệ thống tài khoản: trạng thái đăng nhập + gói (Free / Premium Monthly / Premium Lifetime).
 * Gói chỉ ĐỌC từ Firestore `entitlements/{uid}`; quy tắc bảo mật không cho client ghi vào đó.
 */
object Account {
    const val FREE = "free"
    const val MONTHLY = "premium_monthly"
    const val LIFETIME = "premium_lifetime"

    class Plan(val plan: String, val status: String, val expiresAtMs: Long?) {
        /** Gói đang hiệu lực (hiển thị). Quyền thật sự sẽ do máy chủ xác nhận khi có thanh toán. */
        val effective: String
            get() = when {
                plan == LIFETIME && status == "active" -> LIFETIME
                plan == MONTHLY && (status == "active" || status == "grace") &&
                    (expiresAtMs ?: 0L) > System.currentTimeMillis() -> MONTHLY
                else -> FREE
            }
        val isPremium: Boolean get() = effective != FREE
    }

    @Volatile var cached: Plan? = null

    val auth: FirebaseAuth get() = FirebaseAuth.getInstance()
    val user: FirebaseUser? get() = auth.currentUser
    val isSignedIn: Boolean get() = user != null

    fun planLabel(ctx: Context, p: Plan?): String = ctx.getString(
        when (p?.effective) {
            LIFETIME -> R.string.plan_lifetime
            MONTHLY -> R.string.plan_monthly
            else -> R.string.plan_free
        }
    )

    fun formatDate(ms: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))

    /** Tạo hồ sơ users/{uid} lần đầu, các lần sau chỉ cập nhật lastLoginAt. */
    fun ensureUserDoc(u: FirebaseUser) {
        val ref = FirebaseFirestore.getInstance().collection("users").document(u.uid)
        ref.get().addOnSuccessListener { d ->
            if (!d.exists()) {
                ref.set(
                    mapOf(
                        "email" to (u.email ?: ""),
                        "displayName" to (u.displayName ?: ""),
                        "createdAt" to FieldValue.serverTimestamp(),
                        "lastLoginAt" to FieldValue.serverTimestamp()
                    )
                )
            } else {
                ref.update("lastLoginAt", FieldValue.serverTimestamp())
            }
        }
    }

    /** Đọc gói từ Firestore. Không có tài liệu = Free. error = true nếu không đọc được (cache cũ vẫn được dùng). */
    fun loadPlan(cb: (Plan, Boolean) -> Unit) {
        val u = user
        if (u == null) {
            cached = null
            cb(Plan(FREE, "active", null), false)
            return
        }
        FirebaseFirestore.getInstance().collection("entitlements").document(u.uid).get()
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val d = task.result
                    val p = if (d != null && d.exists()) {
                        Plan(
                            d.getString("plan") ?: FREE,
                            d.getString("status") ?: "active",
                            d.getTimestamp("expiresAt")?.toDate()?.time
                        )
                    } else Plan(FREE, "active", null)
                    cached = p
                    cb(p, false)
                } else {
                    cb(cached ?: Plan(FREE, "active", null), true)
                }
            }
    }

    /** Xóa tài khoản: xóa hồ sơ Firestore rồi xóa người dùng Firebase. needRelogin = true nếu phải đăng nhập lại mới xóa được. */
    fun deleteAccount(cb: (ok: Boolean, needRelogin: Boolean, error: Exception?) -> Unit) {
        val u = user ?: run { cb(true, false, null); return }
        FirebaseFirestore.getInstance().collection("users").document(u.uid).delete()
            .addOnCompleteListener {
                // Dù xóa hồ sơ thất bại (chưa có hồ sơ/ mạng) vẫn thử xóa người dùng
                u.delete().addOnCompleteListener { t ->
                    if (t.isSuccessful) {
                        cached = null
                        cb(true, false, null)
                    } else {
                        val e = t.exception
                        cb(false, e is com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException, e)
                    }
                }
            }
    }

    fun signOut() {
        cached = null
        auth.signOut()
    }

    /** Đổi lỗi Firebase thành chuỗi dễ hiểu. */
    fun errorText(ctx: Context, e: Exception?): String {
        if (e is FirebaseNetworkException) return ctx.getString(R.string.acc_err_network)
        val code = (e as? FirebaseAuthException)?.errorCode
        return when (code) {
            "ERROR_INVALID_EMAIL" -> ctx.getString(R.string.acc_err_email)
            "ERROR_WRONG_PASSWORD", "ERROR_USER_NOT_FOUND", "ERROR_INVALID_CREDENTIAL",
            "ERROR_INVALID_LOGIN_CREDENTIALS" -> ctx.getString(R.string.acc_err_invalid)
            "ERROR_EMAIL_ALREADY_IN_USE" -> ctx.getString(R.string.acc_err_exists)
            "ERROR_WEAK_PASSWORD" -> ctx.getString(R.string.acc_err_weak)
            else -> ctx.getString(R.string.acc_err_generic_fmt, e?.message ?: code ?: "?")
        }
    }
}
