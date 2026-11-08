// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidmessages.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** Iconos alternativos de la app, cada uno es un activity-alias del manifest. */
object AppIcons {
    data class Icon(val name: String, val alias: String, val background: Int, val foreground: Int)

    // Vacía hasta que se aprueben los íconos de Mensajes (ver DESIGN.md): mientras tanto la app usa
    // un ícono provisorio y Apariencia no muestra la fila de íconos.
    val all: List<Icon> = emptyList()

    /**
     * Íconos que ya no se ofrecen. El alias sigue en el manifest, apagado: si alguien lo tenía elegido
     * y desapareciera con los demás apagados, la app se quedaría sin ícono y no se podría abrir.
     */
    private val retired = emptyList<String>()

    /** Quien tenía elegido un ícono que ya no está pasa al primero, antes de que se note. */
    fun migrate(context: Context, chosen: Int): Boolean {
        if (all.isEmpty() || chosen in all.indices) return false
        apply(context, 0)
        return true
    }

    /** Activa el alias elegido y desactiva el resto (primero activa, para no quedarse sin icono). */
    fun apply(context: Context, index: Int) {
        val pm = context.packageManager
        fun set(icon: Icon, enabled: Boolean) = pm.setComponentEnabledSetting(
            ComponentName(context.packageName, "dev.liquidmessages.${icon.alias}"),
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
        val chosen = all.getOrNull(index) ?: all.firstOrNull() ?: return
        set(chosen, true)
        all.filter { it != chosen }.forEach { set(it, false) }
        retired.forEach { alias ->
            pm.setComponentEnabledSetting(
                ComponentName(context.packageName, "dev.liquidmessages.$alias"),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}
