package com.teicrypto.syswidget

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Hộp nhập mã 6 số dạng cửa sổ nhỏ nổi, mở từ thông báo ghép nối. Đủ 6 số là tự gửi đi. */
class CodeDialogActivity : Activity() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.wrap(base))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        val d = resources.displayMetrics.density
        val pad = (20 * d).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        box.addView(TextView(this).apply {
            text = getString(R.string.pair_notif_action)
            textSize = 18f
        })
        val et = EditText(this).apply {
            hint = getString(R.string.hint_code)
            textSize = 24f
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(6))
            setHintTextColor(Color.GRAY)
        }
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if ((s?.length ?: 0) == 6) {
                    startService(
                        Intent(this@CodeDialogActivity, PairingService::class.java)
                            .setAction(PairingService.ACTION_CODE)
                            .putExtra(PairingService.EXTRA_CODE, s.toString())
                    )
                    finish()
                }
            }
        })
        box.addView(et)
        setContentView(box)
        et.requestFocus()
    }
}
