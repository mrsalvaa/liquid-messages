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
 *   adb shell am broadcast -f 0x20 -a dev.liquidmessages.DEV_STYLE -p dev.liquidmessages.debug --ei background 2 --ei ui 0 --ei theme 2
 * El resultado sale en `adb logcat -d -s LiquidMessagesDev:I`: cantidades, nunca textos ni números.
 */
class DevReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = when (intent.action) {
            "dev.liquidmessages.DEV_FAKE" -> DevTools.generate(intent.getIntExtra("count", 500).coerceIn(1, 2000))
            "dev.liquidmessages.DEV_CLEAR" -> DevTools.clear()
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
