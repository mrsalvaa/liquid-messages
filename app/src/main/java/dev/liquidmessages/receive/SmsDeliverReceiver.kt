// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidmessages.receive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SubscriptionManager
import dev.liquidmessages.notify.Notifier
import dev.liquidmessages.sms.SmsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * SMS que llega (SMS_DELIVER). El sistema solo se lo entrega a la app de SMS predeterminada, y
 * protegido con BROADCAST_SMS (ver el manifest): ninguna otra app puede inventar uno. Los números
 * bloqueados los descarta el sistema antes de llegar acá.
 *
 * Un SMS largo llega en varias partes del mismo remitente: se juntan en un solo mensaje.
 */
class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)?.filterNotNull().orEmpty()
        if (parts.isEmpty()) return
        val sub = intent.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, -1).takeIf { it >= 0 }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val store = SmsStore(context)
                for ((address, list) in parts.groupBy { it.displayOriginatingAddress ?: it.originatingAddress.orEmpty() }) {
                    val body = list.joinToString("") { it.displayMessageBody ?: it.messageBody.orEmpty() }
                    val uri = store.insertIncoming(address, body, list.first().timestampMillis, sub) ?: continue
                    val thread = context.contentResolver.query(uri, arrayOf(Telephony.Sms.THREAD_ID), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getLong(0) else null
                    } ?: continue
                    Notifier.incoming(context, thread, listOf(address))
                }
            } finally {
                pending.finish()
            }
        }
    }
}
