// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidmessages.receive

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.telephony.TelephonyManager
import dev.liquidmessages.R
import dev.liquidmessages.core.sim.SendDecision
import dev.liquidmessages.core.sim.SendTarget
import dev.liquidmessages.data.Repo
import dev.liquidmessages.notify.Notifier
import dev.liquidmessages.sms.DefaultSms
import dev.liquidmessages.sms.SmsSender
import dev.liquidmessages.sms.SmsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * "Responder con un mensaje" al rechazar una llamada (lo pide el teléfono del sistema o Liquid
 * Phone). Lo exige el rol de SMS. Protegido con SEND_RESPOND_VIA_MESSAGE: solo el sistema puede
 * llamarlo. Sigue la misma regla de chips: si hay que elegir, no envía y avisa con el texto guardado.
 */
class RespondViaMessageService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val number = intent?.data?.schemeSpecificPart?.substringBefore('?')?.takeIf { it.isNotBlank() }
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)?.trim()
        if (intent?.action != TelephonyManager.ACTION_RESPOND_VIA_MESSAGE || number == null || text.isNullOrEmpty() || !DefaultSms.isDefault(this)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (val d = Repo.decide(SendTarget.UsePolicy, null, canSend = true)) {
                    is SendDecision.Send -> SmsSender.send(this@RespondViaMessageService, number, text, d.chip)
                    SendDecision.SystemChooses -> SmsSender.send(this@RespondViaMessageService, number, text, null)
                    else -> {
                        val thread = SmsStore(this@RespondViaMessageService).threadFor(listOf(number))
                        Notifier.notice(this@RespondViaMessageService, thread, number, getString(R.string.notif_choose_sim_title), getString(R.string.notif_choose_sim_text), text)
                    }
                }
            } finally {
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }
}
