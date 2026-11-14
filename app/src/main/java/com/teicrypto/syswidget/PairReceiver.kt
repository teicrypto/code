package com.teicrypto.syswidget

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Nhận mã 6 số từ ô nhập trong thông báo. */
class PairReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(PairNotif.KEY_CODE)
            ?.toString().orEmpty()
        if (code.any { it.isDigit() }) PairNotif.submit(context, code)
    }
}
