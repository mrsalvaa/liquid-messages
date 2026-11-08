// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidmessages.notify

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.liquidmessages.R
import dev.liquidmessages.core.sim.SendDecision
import dev.liquidmessages.core.sim.SendTarget
import dev.liquidmessages.core.sms.SmsLength
import dev.liquidmessages.data.Repo
import dev.liquidmessages.data.Stores
import dev.liquidmessages.sms.DefaultSms
import dev.liquidmessages.sms.SmsSender
import dev.liquidmessages.sms.SmsStore
import dev.liquidmessages.ui.screens.Actions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Acciones de la notificación: responder, marcar como leído y copiar el código. No es exportado:
 * solo lo llaman los PendingIntent de la propia notificación.
 */
class NotificationActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_REPLY = "dev.liquidmessages.REPLY"
        const val ACTION_MARK_READ = "dev.liquidmessages.MARK_READ"
        const val ACTION_COPY_CODE = "dev.liquidmessages.COPY_CODE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val thread = intent.getLongExtra(Notifier.EXTRA_THREAD, -1)
        val address = intent.getStringExtra(Notifier.EXTRA_ADDRESS).orEmpty()
        when (intent.action) {
            ACTION_COPY_CODE -> intent.getStringExtra(Notifier.EXTRA_TEXT)?.let { Actions.copy(context, it, sensitive = true) }
            ACTION_MARK_READ, ACTION_REPLY -> {
                val reply = if (intent.action == ACTION_REPLY) RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notifier.KEY_REPLY)?.toString()?.trim() else null
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        if (!reply.isNullOrEmpty()) reply(context, thread, address, reply)
                        SmsStore(context).markRead(thread)
                        Notifier.cancel(context, thread)
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    /**
     * Responder sin abrir la app, con la misma regla que en la pantalla: si el chip que toca no se
     * puede usar o hay que elegirlo, no se envía y se avisa, con el texto guardado para mandarlo
     * desde la conversación. Nunca sale por otro chip sin que se vea.
     */
    private fun reply(context: Context, thread: Long, address: String, text: String) {
        val body = if (Stores.settings.value.simplifyAccents) SmsLength.simplify(text) else text
        val last = lastSubscription(context, thread)
        when (val d = Repo.decide(SendTarget.UsePolicy, last, canSend = DefaultSms.isDefault(context))) {
            is SendDecision.Send -> SmsSender.send(context, address, body, d.chip)
            SendDecision.SystemChooses -> SmsSender.send(context, address, body, null)
            else -> Notifier.notice(context, thread, address, context.getString(R.string.notif_choose_sim_title), context.getString(R.string.notif_choose_sim_text), body)
        }
    }

    private fun lastSubscription(context: Context, thread: Long): Int? =
        context.contentResolver.query(
            android.provider.Telephony.Sms.CONTENT_URI,
            arrayOf(android.provider.Telephony.Sms.SUBSCRIPTION_ID),
            "${android.provider.Telephony.Sms.THREAD_ID} = ?",
            arrayOf(thread.toString()),
            "${android.provider.Telephony.Sms.DATE} DESC LIMIT 1",
        )?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getInt(0).takeIf { it >= 0 } else null }
}
