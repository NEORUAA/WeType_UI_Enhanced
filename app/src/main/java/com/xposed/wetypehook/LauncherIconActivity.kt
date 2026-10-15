package com.xposed.wetypehook

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle

internal object LauncherIconContract {
    const val MODULE_PACKAGE = "com.xposed.wetypehook"
    const val ACTION_QUERY = "$MODULE_PACKAGE.action.QUERY_LAUNCHER_ICON"
    const val ACTION_SET = "$MODULE_PACKAGE.action.SET_LAUNCHER_ICON"
    const val EXTRA_HIDDEN = "$MODULE_PACKAGE.extra.LAUNCHER_ICON_HIDDEN"

    fun intent(hidden: Boolean? = null): Intent = Intent(
        if (hidden == null) ACTION_QUERY else ACTION_SET
    ).apply {
        component = ComponentName(MODULE_PACKAGE, LauncherIconActivity::class.java.name)
        if (hidden != null) putExtra(EXTRA_HIDDEN, hidden)
    }
}

/** Performs module-owned launcher changes for an authenticated activity-result caller. */
class LauncherIconActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        try {
            if (callingPackage != "com.tencent.wetype" &&
                callingPackage != LauncherIconContract.MODULE_PACKAGE
            ) return
            val alias = ComponentName(
                LauncherIconContract.MODULE_PACKAGE,
                "${LauncherIconContract.MODULE_PACKAGE}.LauncherAlias"
            )
            when (intent.action) {
                LauncherIconContract.ACTION_QUERY -> Unit
                LauncherIconContract.ACTION_SET -> {
                    if (!intent.hasExtra(LauncherIconContract.EXTRA_HIDDEN)) return
                    @Suppress("DEPRECATION")
                    val hidden = intent.extras?.get(LauncherIconContract.EXTRA_HIDDEN) as? Boolean ?: return
                    packageManager.setComponentEnabledSetting(
                        alias,
                        if (hidden) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                        else PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP
                    )
                }
                else -> return
            }
            val hidden = when (packageManager.getComponentEnabledSetting(alias)) {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> true
                else -> false
            }
            setResult(RESULT_OK, Intent().putExtra(LauncherIconContract.EXTRA_HIDDEN, hidden))
        } catch (_: Exception) {
            setResult(RESULT_CANCELED)
        } finally {
            finish()
        }
    }
}
