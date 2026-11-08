// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidmessages.receive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SubscriptionManager
import dev.liquidmessages.mms.MmsStore
import dev.liquidmessages.mms.MmsTransport
import dev.liquidmessages.mms.pdu.NotificationInd
import dev.liquidmessages.mms.pdu.PduParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Aviso de MMS (WAP_PUSH_DELIVER), solo para la app de SMS predeterminada y protegido con
 * BROADCAST_WAP_PUSH. Primero se guarda el aviso (así el MMS nunca se pierde, aunque la descarga
 * falle) y después se pide bajarlo. Un aviso repetido (mismo lugar de descarga) no se duplica.
 */
class MmsWapPushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        val data = intent.getByteArrayExtra("data") ?: return
        val sub = intent.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, -1).takeIf { it >= 0 }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val ind = runCatching { PduParser(data, true).parse() }.getOrNull() as? NotificationInd ?: return@launch
                val location = ind.contentLocation?.let(::String) ?: return@launch
                val repeated = context.contentResolver.query(
                    Telephony.Mms.CONTENT_URI,
                    arrayOf(Telephony.Mms._ID),
                    "${Telephony.Mms.CONTENT_LOCATION} = ?",
                    arrayOf(location),
                    null,
                )?.use { it.count > 0 } == true
                if (repeated) return@launch
                val row = MmsStore(context).insertNotification(ind, sub) ?: return@launch
                MmsTransport.download(context, row)
            } finally {
                pending.finish()
            }
        }
    }
}
