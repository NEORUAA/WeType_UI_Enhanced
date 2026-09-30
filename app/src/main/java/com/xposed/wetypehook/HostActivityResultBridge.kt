package com.xposed.wetypehook

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import java.util.concurrent.ConcurrentHashMap

/**
 * 宿主进程内文件选择结果的回传桥。
 *
 * 设置界面在宿主进程里以 ComponentDialog 渲染，而 ComponentDialog 只实现了
 * Lifecycle/ViewModelStore/SavedStateRegistryOwner，不是 ActivityResultRegistryOwner，
 * 因此 androidx 的 launcher 无法启动系统文件选择器。改为借宿主 Activity 的
 * startActivityForResult 发起，并由 MainHook 里对 Activity.onActivityResult 的钩子
 * 把结果送回这里注册的一次性回调。
 */
object HostActivityResultBridge {
    /** 避开宿主应用自身使用的低位 requestCode 区间。 */
    private const val REQUEST_CODE_MIN = 0x6A00
    private const val REQUEST_CODE_MAX = 0x6AFF

    private val lock = Any()
    private val callbacks = ConcurrentHashMap<Int, (Int, Uri?) -> Unit>()
    private var nextCode = REQUEST_CODE_MIN

    fun register(callback: (resultCode: Int, uri: Uri?) -> Unit): Int = synchronized(lock) {
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
        callback(resultCode, data?.data)
        return true
    }
}

/** 设置界面拿到的 Context 可能是 ContextThemeWrapper，逐层解包定位真实 Activity。 */
tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}
