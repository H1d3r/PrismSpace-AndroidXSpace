package com.yzddmr6.prismspace.setup

import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import com.yzddmr6.prismspace.prism.compose.vm.ShizukuUtil
import rikka.shizuku.Shizuku

/**
 * One-shot Shizuku permission flow for user-initiated privileged operations.
 * Never requested from passive rendering — only from an explicit user action
 * (see openspec privileged-mode-detection: 特权操作的授权时机).
 */
object ShizukuSetupAuthorization {

    @JvmStatic fun isAuthorized(): Boolean = ShizukuUtil.isAuthorized()

    /** Shizuku service is running (Sui included); says nothing about our permission. */
    @JvmStatic fun isRunning(): Boolean = ShizukuUtil.isAvailable()

    /** Delivers exactly one callback on the main thread. */
    fun requestPermission(onResult: (Boolean) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode != REQUEST_CODE) return
                Shizuku.removeRequestPermissionResultListener(this)
                main.post { onResult(grantResult == PackageManager.PERMISSION_GRANTED) }
            }
        }
        try {
            Shizuku.addRequestPermissionResultListener(listener)
            Shizuku.requestPermission(REQUEST_CODE)
        } catch (e: RuntimeException) {
            Shizuku.removeRequestPermissionResultListener(listener)
            main.post { onResult(false) }
        }
    }

    /** Java-facing variant (Java lambdas cannot target Kotlin's Unit-returning Function1). */
    @JvmStatic fun requestPermissionCompat(onResult: java.util.function.Consumer<Boolean>) =
        requestPermission { onResult.accept(it) }

    private const val REQUEST_CODE = 0x50524953   // "PRIS" — ours alone within this process
}
