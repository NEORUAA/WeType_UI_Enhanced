package com.xposed.wetypehook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager

/** Opens the existing settings screen when the system dispatches our dialer code. */
class SecretCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_SECRET_CODE) return
        val data = intent.data ?: return
        if (data.scheme != "android_secret_code" || data.host != SECRET_CODE) return
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }

    companion object {
        internal const val SECRET_CODE = "938973"
    }
}
