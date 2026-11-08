package io.github.garfieldrockero.gastronomicon.platform

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Versión visible de la app («0.1.0»), para «Acerca de». Vacía si el sistema no la da. */
fun Context.appVersionName(): String = try {
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0)
    }
    info.versionName.orEmpty()
} catch (e: PackageManager.NameNotFoundException) {
    ""
}
