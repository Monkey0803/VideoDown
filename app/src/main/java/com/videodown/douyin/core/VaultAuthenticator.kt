package com.videodown.douyin.core

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal

/**
 * The gate in front of the private vault.
 *
 * An interface rather than a direct call so the vault's behaviour either side of the gate can be
 * driven in tests: the system prompt itself is not something an automated test can answer.
 */
interface VaultAuthenticator {
    /** Whether this device can gate anything at all (a keyguard or enrolled biometrics). */
    fun isAvailable(context: Context): Boolean

    /**
     * Shows the system prompt. [onResult] is called exactly once with the user's answer.
     * Returns `false` when no prompt could be shown, so the caller can explain why.
     */
    fun authenticate(activity: Activity, onResult: (Boolean) -> Unit): Boolean
}

/**
 * The real prompt.
 *
 * Android 11 and newer asks for biometrics **or** the device credential, and Android 10 can add the
 * credential as a fallback. Android 9's framework prompt only offers biometrics (it has no
 * credential form), and older releases have no prompt a foreground app may use, so the vault
 * reports itself unavailable there instead of pretending to be locked.
 */
class SystemVaultAuthenticator : VaultAuthenticator {
    override fun isAvailable(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val keyguard = context.getSystemService(KeyguardManager::class.java) ?: return false
        if (keyguard.isDeviceSecure) return true
        // canAuthenticate(int) 是 API 30 起：Android 10 只看锁屏凭据，没有就不算可用。
        // The framework class has no `from()`; that helper only exists in androidx.biometric, which
        // this project does not depend on.
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            context.getSystemService(BiometricManager::class.java)
                ?.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
    }

    @Suppress("DEPRECATION")
    override fun authenticate(activity: Activity, onResult: (Boolean) -> Unit): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val builder = BiometricPrompt.Builder(activity)
            .setTitle(activity.getString(com.videodown.douyin.R.string.vault_unlock_title))
            .setSubtitle(activity.getString(com.videodown.douyin.R.string.vault_unlock_subtitle))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
        } else {
            // Android 10 only: biometrics, with the device credential as a fallback.
            builder.setDeviceCredentialAllowed(true)
        }
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) = onResult(true)
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) = onResult(false)
        }
        return runCatching {
            builder.build().authenticate(CancellationSignal(), activity.mainExecutor, callback)
            true
        }.getOrDefault(false)
    }
}
