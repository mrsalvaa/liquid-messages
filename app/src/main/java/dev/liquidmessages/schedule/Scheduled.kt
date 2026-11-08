// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidmessages.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.liquidmessages.R
import dev.liquidmessages.core.sim.SendDecision
import dev.liquidmessages.core.sim.SendTarget
import dev.liquidmessages.data.Repo
import dev.liquidmessages.data.Stores
import dev.liquidmessages.mms.MmsTransport
import dev.liquidmessages.notify.Notifier
import dev.liquidmessages.sms.DefaultSms
import dev.liquidmessages.sms.SmsSender
import dev.liquidmessages.sms.SmsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** Un mensaje que sale solo a una hora elegida. Vive en la app (files/scheduled.json) hasta que sale. */
@Serializable
data class ScheduledMessage(
    val id: Long,
    val addresses: List<String>,
    val text: String,
    /** Milisegundos desde 1970. */
    val at: Long,
    /** Chip elegido a mano al programarlo (ranura); null = el que toque por la política al salir. */
    val slot: Int? = null,
    /** Chip de la conversación al programarlo, para la política "el de la conversación". */
    val conversationSubscription: Int? = null,
)

/**
 * Programar, cancelar y enviar mensajes programados. La alarma no es exacta (no se pide el permiso
 * especial de alarmas exactas): con el celular en reposo puede salir unos minutos después. Al
 * reiniciar se vuelven a poner (BootReceiver), porque el sistema borra las alarmas.
 */
object Scheduler {
    private const val ACTION = "dev.liquidmessages.SEND_SCHEDULED"
    private const val EXTRA_ID = "id"

    private fun pending(context: Context, id: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            (id and 0x7FFFFFFF).toInt(),
            Intent(context, ScheduledReceiver::class.java).setAction(ACTION).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun arm(context: Context, m: ScheduledMessage) {
        context.getSystemService(AlarmManager::class.java)?.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, m.at, pending(context, m.id))
    }

    fun schedule(context: Context, m: ScheduledMessage) {
        // Con los mensajes de prueba a la vista no se programa nada: saldría un SMS de verdad.
        if (dev.liquidmessages.data.Repo.inbox.value.fake) return
        Stores.scheduled.update { it + m }
        arm(context, m)
    }

    fun cancel(context: Context, id: Long) {
        Stores.scheduled.update { list -> list.filter { it.id != id } }
        context.getSystemService(AlarmManager::class.java)?.cancel(pending(context, id))
    }

    /** Vuelve a poner todas las alarmas (al abrir la app y después de reiniciar). */
    fun rearmAll(context: Context) {
        Stores.scheduled.value.forEach { arm(context, it) }
    }

    /**
     * Envía uno ya mismo (al llegar la hora o con "Enviar ahora"). Misma regla de chips que al
     * escribir: si hay que elegir chip o la app ya no es la predeterminada, no se envía y se avisa,
     * con el texto esperando en la conversación.
     */
    fun sendNow(context: Context, id: Long) {
        val m = Stores.scheduled.value.firstOrNull { it.id == id } ?: return
        Stores.scheduled.update { list -> list.filter { it.id != id } }
        val target = m.slot?.let { SendTarget.Slot(it) } ?: SendTarget.UsePolicy
        val chip = when (val d = Repo.decide(target, m.conversationSubscription, canSend = DefaultSms.isDefault(context))) {
            is SendDecision.Send -> d.chip
            SendDecision.SystemChooses -> null
            else -> {
                val thread = SmsStore(context).threadFor(m.addresses)
                Notifier.notice(context, thread, m.addresses.singleOrNull(), context.getString(R.string.scheduled_not_sent_title), context.getString(R.string.notif_choose_sim_text), m.text)
                return
            }
        }
        if (m.addresses.size > 1) MmsTransport.send(context, m.addresses, m.text, emptyList(), chip)
        else SmsSender.send(context, m.addresses.single(), m.text, chip)
    }

    internal fun idOf(intent: Intent): Long = intent.getLongExtra(EXTRA_ID, -1)
}

/** La alarma de un mensaje programado. No es exportado: solo lo llama el sistema por el PendingIntent. */
class ScheduledReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = Scheduler.idOf(intent)
        if (id < 0) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Scheduler.sendNow(context, id)
            } finally {
                pending.finish()
            }
        }
    }
}

/** Después de reiniciar, vuelve a poner las alarmas de los programados (el sistema las borra). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) Scheduler.rearmAll(context)
    }
}
