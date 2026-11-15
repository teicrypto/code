package com.teicrypto.syspulse

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.firebase.auth.FirebaseAuth

/** Trang hồ sơ: email, UID, trạng thái xác thực, gói hiện tại. Tự thoát nếu đăng xuất. */
class ProfileActivity : Activity() {

    override fun onContentChanged() {
        super.onContentChanged()
        Edge.apply(this)
    }

    private lateinit var emailTv: TextView
    private lateinit var uidTv: TextView
    private lateinit var verifyTv: TextView
    private lateinit var verifyBtn: TextView
    private lateinit var planTv: TextView
    private lateinit var expTv: TextView
    private lateinit var msg: TextView

    private val listener = FirebaseAuth.AuthStateListener { a ->
        if (a.currentUser == null) finish() else runOnUiThread { render() }
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.wrap(base))
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.parseColor("#8A94A0"))
        setPadding(0, UiKit.dp(this@ProfileActivity, 16), 0, 0)
    }

    private fun value(size: Float = 17f) = TextView(this).apply {
        textSize = size
        setTextColor(Color.WHITE)
        setTextIsSelectable(true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = { v: Int -> UiKit.dp(this, v) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(16), d(40), d(16), d(24))
        }
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        bar.addView(TextView(this).apply {
            text = "←"
            textSize = 26f
            setTextColor(Color.WHITE)
            setPadding(d(8), d(8), d(24), d(8))
            setOnClickListener { finish() }
        })
        bar.addView(TextView(this).apply {
            text = getString(R.string.acc_open_profile)
            textSize = 24f
            setTextColor(Color.WHITE)
        })
        root.addView(bar)

        val card = UiKit.card(this)
        card.addView(label(getString(R.string.acc_email)))
        emailTv = value()
        card.addView(emailTv)
        verifyTv = value(14f)
        card.addView(verifyTv)
        verifyBtn = UiKit.pill(this, getString(R.string.acc_send_verify), "✉") {
            Account.user?.sendEmailVerification()?.addOnCompleteListener { t ->
                msg.text = if (t.isSuccessful) getString(R.string.acc_verify_sent) else Account.errorText(this, t.exception)
            }
        }
        card.addView(verifyBtn)
        card.addView(label(getString(R.string.acc_uid)))
        uidTv = value(14f)
        card.addView(uidTv)
        card.addView(UiKit.pill(this, getString(R.string.adb_copy), "⧉") {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("uid", Account.user?.uid ?: ""))
            Toast.makeText(this, R.string.adb_copied, Toast.LENGTH_SHORT).show()
        })
        root.addView(card)

        val plan = UiKit.card(this)
        plan.addView(label(getString(R.string.acc_plan)))
        planTv = value(22f)
        plan.addView(planTv)
        expTv = value(14f)
        plan.addView(expTv)
        plan.addView(UiKit.pill(this, getString(R.string.acc_refresh), "↻") { loadPlan() })
        msg = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#F2A65A"))
            setPadding(0, d(12), 0, 0)
        }
        plan.addView(msg)
        root.addView(plan)

        val out = UiKit.card(this)
        out.addView(UiKit.pill(this, getString(R.string.acc_signout), "⎋") {
            Account.signOut()
            Toast.makeText(this, R.string.acc_logged_out, Toast.LENGTH_SHORT).show()
            finish()
        })
        out.addView(UiKit.pill(this, getString(R.string.acc_delete), "🗑") {
            AlertDialog.Builder(this)
                .setTitle(R.string.acc_delete_title)
                .setMessage(R.string.acc_delete_msg)
                .setPositiveButton(R.string.acc_delete_confirm) { _, _ ->
                    Account.deleteAccount { ok, needRelogin, e ->
                        runOnUiThread {
                            when {
                                ok -> {
                                    Toast.makeText(this, R.string.acc_deleted, Toast.LENGTH_LONG).show()
                                    Account.signOut()
                                    finish()
                                }
                                needRelogin -> msg.text = getString(R.string.acc_delete_relogin)
                                else -> msg.text = Account.errorText(this, e)
                            }
                        }
                    }
                }
                .setNegativeButton(R.string.ad_gate_cancel, null)
                .show()
        })
        root.addView(out)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(UiKit.BG))
            isFillViewport = true
            addView(root)
        })
    }

    private fun render() {
        val u = Account.user ?: return
        emailTv.text = u.email ?: "-"
        uidTv.text = u.uid
        verifyTv.text = getString(if (u.isEmailVerified) R.string.acc_verified else R.string.acc_not_verified)
        verifyTv.setTextColor(Color.parseColor(if (u.isEmailVerified) "#7CE0C3" else "#F2A65A"))
        verifyBtn.visibility = if (u.isEmailVerified) android.view.View.GONE else android.view.View.VISIBLE
        showPlan(Account.cached)
    }

    private fun showPlan(p: Account.Plan?) {
        planTv.text = if (p == null) getString(R.string.acc_loading) else Account.planLabel(this, p)
        val exp = p?.expiresAtMs
        expTv.text = if (p?.effective == Account.MONTHLY && exp != null)
            getString(R.string.acc_expires_fmt, Account.formatDate(exp)) else ""
    }

    private fun loadPlan() {
        msg.text = ""
        showPlan(null)
        Account.loadPlan { p, error ->
            runOnUiThread {
                showPlan(p)
                if (error) msg.text = getString(R.string.acc_err_network)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Account.auth.addAuthStateListener(listener)
    }

    override fun onResume() {
        super.onResume()
        render()
        loadPlan()
    }

    override fun onStop() {
        Account.auth.removeAuthStateListener(listener)
        super.onStop()
    }
}
