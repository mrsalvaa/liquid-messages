// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidmessages.data

import dev.liquidmessages.ui.glass.GlassStyle
import kotlinx.serialization.Serializable

/**
 * Ajustes guardados en files/settings.json. Los 8 primeros son los de personalización de la suite:
 * mismos nombres, valores y valores por defecto que Liquid Calendar. Después, los de números que
 * comparten Contacts y Phone, y al final los de Mensajes.
 */
@Serializable
data class AppSettings(
    /** 0 = según el sistema, 1 = claro, 2 = oscuro. */
    val themeMode: Int = 0,
    /** 0 = colores en movimiento (COLOR_SETS), 1 = color de énfasis, 2 = liso con acento azul. */
    val backgroundMode: Int = 2,
    /** Índice en COLOR_SETS. */
    val colorSet: Int = 0,
    /** Color de énfasis en ARGB, para backgroundMode = 1. */
    val accentColor: Long = 0xFF0EA5E9,
    val animatedBackground: Boolean = true,
    /** Estilo de interfaz: 0 = Liquid Glass, 1 = sólido. */
    val uiStyle: Int = 0,
    /** Color de énfasis del estilo sólido, en ARGB (aparte del de Liquid Glass). */
    val solidAccent: Long = 0xFF0EA5E9,
    /** Índice del icono de la app (ver AppIcons). */
    val appIcon: Int = 0,

    // --- Números (como en Contacts y Phone) ---

    /** País (ISO 3166, p. ej. "AR") para los números sin prefijo. Vacío hasta el primer arranque. */
    val defaultCountry: String = "",
    /** Código de área para los números guardados sin él (sin el 0). Vacío = no se completa. */
    val defaultAreaCode: String = "",
    val areaCodeNoticeDismissed: Boolean = false,

    // --- Mensajes ---

    /** Política de chip al enviar (ver SimPolicy en :core). 0 = el de la conversación. */
    val simPolicy: Int = 0,
    /** Ranura del chip principal: 0 = SIM 1, 1 = SIM 2. Solo vale en esta app. */
    val primarySimSlot: Int = 0,
) {
    val glassStyle: GlassStyle get() = GlassStyle(themeMode, backgroundMode, colorSet, accentColor, uiStyle, solidAccent)
}
