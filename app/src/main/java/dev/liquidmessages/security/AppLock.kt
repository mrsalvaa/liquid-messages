// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidmessages.security

import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import androidx.activity.ComponentActivity
import dev.liquidmessages.R
import dev.liquidmessages.data.Stores
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bloqueo de la app con la huella, la cara o el PIN del celular (BiometricPrompt del sistema: la
 * app nunca ve la huella ni el PIN, solo si se desbloqueó). Pide de nuevo al abrir la app y al volver
 * después de [GRACE_MS] afuera.
 */
object AppLock {
    private const val GRACE_MS = 30_000L
    private val AUTHENTICATORS = Authenticators.BIOMETRIC_WEAK or Authenticators.DEVICE_CREDENTIAL

    private val _locked = MutableStateFlow(true)
    /** true mientras haya que desbloquear (solo cuenta si el ajuste está activo). */
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private var leftAt = 0L

    /** Si el celular tiene un bloqueo de pantalla (sin eso no hay con qué desbloquear la app). */
    fun available(context: Context): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true &&
            context.getSystemService(BiometricManager::class.java)?.canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    fun enabled(): Boolean = Stores.settings.value.appLock

    /** Al salir de la app (onStop). */
    fun left() {
        if (!_locked.value) leftAt = System.currentTimeMillis()
    }

    /** Al volver (onStart): si pasó el margen, se vuelve a pedir. */
    fun returned() {
        if (enabled() && leftAt > 0 && System.currentTimeMillis() - leftAt > GRACE_MS) _locked.value = true
    }

    /** Pide desbloquear. [onResult] recibe true si se pudo. */
    fun authenticate(activity: ComponentActivity, onResult: (Boolean) -> Unit = {}) {
        val prompt = BiometricPrompt.Builder(activity)
            .setTitle(activity.getString(R.string.lock_prompt_title))
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()
        prompt.authenticate(
            CancellationSignal(),
            activity.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    _locked.value = false
                    leftAt = 0
                    onResult(true)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onResult(false)
                }
            },
        )
    }

    /** Al activar el ajuste recién desbloqueado no se vuelve a pedir en esta sesión. */
    fun unlockedNow() {
        _locked.value = false
    }
}
