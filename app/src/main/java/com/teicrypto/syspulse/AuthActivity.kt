package com.teicrypto.syspulse

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialManagerCallback
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.GoogleAuthProvider

/** Đăng nhập / đăng ký bằng Email + Mật khẩu hoặc Google. */
class AuthActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var register = false
    private var busy = false

    private lateinit var heading: TextView
    private lateinit var email: EditText
    private lateinit var pass: EditText
    private lateinit var pass2: EditText
    private lateinit var submit: TextView
    private lateinit var switchTv: TextView
    private lateinit var forgot: TextView
    private lateinit var msg: TextView

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.wrap(base))
    }

    private fun input(hint: String, password: Boolean, mail: Boolean = false) = EditText(this).apply {
        this.hint = hint
        setHintTextColor(Color.GRAY)
        setTextColor(Color.WHITE)
        textSize = 16f
        inputType = when {
            password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            mail -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            else -> InputType.TYPE_CLASS_TEXT
        }
        background = UiKit.round(this@AuthActivity, "#1E2633", 14)
        val p = UiKit.dp(this@AuthActivity, 16)
        setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = UiKit.dp(this@AuthActivity, 12) }
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
            text = getString(R.string.acc_title)
            textSize = 24f
            setTextColor(Color.WHITE)
        })
        root.addView(bar)

        val card = UiKit.card(this)
        heading = UiKit.title(this, "")
        card.addView(heading)
        email = input(getString(R.string.acc_email), false, true)
        pass = input(getString(R.string.acc_password), true)
        pass2 = input(getString(R.string.acc_confirm), true)
        card.addView(email)
        card.addView(pass)
        card.addView(pass2)

        forgot = TextView(this).apply {
            text = getString(R.string.acc_forgot)
            textSize = 14f
            setTextColor(Color.parseColor(UiKit.ACCENT))
            setPadding(0, d(14), 0, d(4))
            setOnClickListener { resetPassword() }
        }
        card.addView(forgot)

        submit = UiKit.pill(this, "", "") { submitForm() }
        card.addView(submit)

        msg = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#F2A65A"))
            setPadding(0, d(12), 0, 0)
        }
        card.addView(msg)
        root.addView(card)

        val g = UiKit.card(this)
        g.addView(UiKit.pill(this, getString(R.string.acc_google), "G") { googleSignIn() })
        switchTv = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.parseColor(UiKit.ACCENT))
            setPadding(0, d(20), 0, d(4))
            setOnClickListener { register = !register; render() }
        }
        g.addView(switchTv)
        root.addView(g)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(UiKit.BG))
            isFillViewport = true
            addView(root)
        })
        render()
    }

    private fun render() {
        heading.text = getString(if (register) R.string.acc_register else R.string.acc_signin)
        submit.text = getString(if (register) R.string.acc_register else R.string.acc_signin)
        switchTv.text = getString(if (register) R.string.acc_switch_to_login else R.string.acc_switch_to_register)
        pass2.visibility = if (register) android.view.View.VISIBLE else android.view.View.GONE
        forgot.visibility = if (register) android.view.View.GONE else android.view.View.VISIBLE
        msg.text = ""
    }

    private fun setBusy(b: Boolean) {
        busy = b
        submit.alpha = if (b) 0.5f else 1f
    }

    private fun fail(text: String) {
        setBusy(false)
        msg.text = text
    }

    private fun success() {
        Account.user?.let { Account.ensureUserDoc(it) }
        setResult(RESULT_OK)
        finish()
    }

    private fun submitForm() {
        if (busy) return
        val e = email.text.toString().trim()
        val p = pass.text.toString()
        if (e.isEmpty() || p.isEmpty()) { msg.text = getString(R.string.acc_err_empty); return }
        if (register) {
            if (p.length < 6) { msg.text = getString(R.string.acc_err_weak); return }
            if (p != pass2.text.toString()) { msg.text = getString(R.string.acc_err_mismatch); return }
        }
        setBusy(true)
        msg.text = ""
        val task = if (register) Account.auth.createUserWithEmailAndPassword(e, p)
        else Account.auth.signInWithEmailAndPassword(e, p)
        task.addOnCompleteListener { t ->
            if (t.isSuccessful) {
                if (register) Account.user?.sendEmailVerification()
                success()
            } else fail(Account.errorText(this, t.exception))
        }
    }

    private fun resetPassword() {
        val e = email.text.toString().trim()
        if (e.isEmpty()) { msg.text = getString(R.string.acc_enter_email); return }
        Account.auth.sendPasswordResetEmail(e).addOnCompleteListener { t ->
            msg.text = if (t.isSuccessful) getString(R.string.acc_reset_sent) else Account.errorText(this, t.exception)
        }
    }

    private fun googleSignIn() {
        if (busy) return
        // default_web_client_id chỉ có khi google-services.json có OAuth client (cần SHA-1 trong Firebase)
        val id = resources.getIdentifier("default_web_client_id", "string", packageName)
        if (id == 0) {
            msg.text = getString(R.string.acc_err_google_cfg)
            return
        }
        setBusy(true)
        msg.text = ""
        val option = GetSignInWithGoogleOption.Builder(getString(id)).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        CredentialManager.create(this).getCredentialAsync(
            this, request, CancellationSignal(), { r -> handler.post(r) },
            object : CredentialManagerCallback<GetCredentialResponse, GetCredentialException> {
                override fun onResult(result: GetCredentialResponse) {
                    val c = result.credential
                    if (c is CustomCredential && c.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                        try {
                            val token = GoogleIdTokenCredential.createFrom(c.data).idToken
                            Account.auth.signInWithCredential(GoogleAuthProvider.getCredential(token, null))
                                .addOnCompleteListener { t ->
                                    if (t.isSuccessful) success() else fail(Account.errorText(this@AuthActivity, t.exception))
                                }
                        } catch (ex: Exception) {
                            fail(getString(R.string.acc_err_generic_fmt, ex.message ?: "?"))
                        }
                    } else {
                        fail(getString(R.string.acc_err_generic_fmt, "credential"))
                    }
                }

                override fun onError(e: GetCredentialException) {
                    fail(getString(R.string.acc_err_generic_fmt, e.message ?: e.javaClass.simpleName))
                }
            }
        )
    }
}
