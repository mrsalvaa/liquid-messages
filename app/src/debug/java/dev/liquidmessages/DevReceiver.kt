// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidmessages

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Solo debug y profile. Desde adb (nunca desde otra app, ver el manifest de debug):
 *   adb shell am broadcast -f 0x20 -a dev.liquidmessages.DEV_FAKE -p dev.liquidmessages.debug --ei count 500
 *   adb shell am broadcast -f 0x20 -a dev.liquidmessages.DEV_CLEAR -p dev.liquidmessages.debug
 *   adb -s emulator-5554 shell am broadcast -f 0x20 -a dev.liquidmessages.DEV_MMS -p dev.liquidmessages.debug   (solo emulador)
 *   adb shell am broadcast -f 0x20 -a dev.liquidmessages.DEV_STYLE -p dev.liquidmessages.debug --ei background 2 --ei ui 0 --ei theme 2
 * El resultado sale en `adb logcat -d -s LiquidMessagesDev:I`: cantidades, nunca textos ni números.
 */
class DevReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = when (intent.action) {
            "dev.liquidmessages.DEV_FAKE" -> DevTools.generate(intent.getIntExtra("count", 500).coerceIn(1, 2000))
            "dev.liquidmessages.DEV_CLEAR" -> DevTools.clear()
            // Un aviso de ejemplo con un nombre inventado (sin mensajes reales).
            // El avatar de los avisos como PNG en files/avatar.png (para mirarlo sin abrir el panel).
            "dev.liquidmessages.DEV_AVATAR" -> {
                java.io.File(context.filesDir, "avatar.png").outputStream().use {
                    dev.liquidmessages.notify.NotificationAvatar.bitmap(context, "Ana Prueba").compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                "ok"
            }
            "dev.liquidmessages.DEV_NOTIFY" -> {
                dev.liquidmessages.notify.Notifier.channels(context)
                dev.liquidmessages.notify.Notifier.demo(context, intent.getStringExtra("name") ?: "Ana Prueba", "Mensaje de prueba")
                "ok"
            }
            // Solo en un emulador (ver DevMms): escribe MMS de prueba en el proveedor.
            "dev.liquidmessages.DEV_MMS" -> DevMms.run(context)
            "dev.liquidmessages.DEV_STYLE" -> DevTools.style(
                intent.getIntExtra("background", 2),
                intent.getIntExtra("ui", 0),
                intent.getIntExtra("theme", 0),
            )
            else -> "desconocido"
        }
        Log.i("LiquidMessagesDev", "${intent.action}: $result")
    }
}
