package com.xposed.wetypehook

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import java.util.concurrent.ConcurrentHashMap

/**
 * One-shot activity results for settings embedded in the host's ComponentDialog.
 * The dialog has no ActivityResultRegistryOwner, so the host activity starts the
 * request and MainHook forwards its result here. URI callers retain a small wrapper.
 */
object HostActivityResultBridge {
    /** 避开宿主应用自身使用的低位 requestCode 区间。 */
    private const val REQUEST_CODE_MIN = 0x6A00
    private const val REQUEST_CODE_MAX = 0x6AFF

    private val lock = Any()
    private val callbacks = ConcurrentHashMap<Int, (Int, Intent?) -> Unit>()
    private var nextCode = REQUEST_CODE_MIN

    fun register(callback: (resultCode: Int, uri: Uri?) -> Unit): Int =
        registerResult { resultCode, data -> callback(resultCode, data?.data) }

    fun registerResult(callback: (resultCode: Int, data: Intent?) -> Unit): Int = synchronized(lock) {
        var code = nextCode
        while (callbacks.containsKey(code)) {
            code++
            if (code > REQUEST_CODE_MAX) code = REQUEST_CODE_MIN
        }
        nextCode = if (code >= REQUEST_CODE_MAX) REQUEST_CODE_MIN else code + 1
        callbacks[code] = callback
        code
    }

    /** @return true 表示该 requestCode 归本桥处理，回调已被消费。 */
    fun dispatch(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        val callback = callbacks.remove(requestCode) ?: return false
        callback(resultCode, data)
        return true
    }

    fun unregister(requestCode: Int) {
        callbacks.remove(requestCode)
    }
}

/** 设置界面拿到的 Context 可能是 ContextThemeWrapper，逐层解包定位真实 Activity。 */
tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}
