package com.linex.app.core

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat

/** Read the installed manifest rather than a version constant inlined by Kotlin. */
object AppVersion {
    data class Installed(val name: String, val code: Long)

    fun read(context: Context): Installed {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return Installed(info.versionName ?: "unknown", PackageInfoCompat.getLongVersionCode(info))
    }
}
