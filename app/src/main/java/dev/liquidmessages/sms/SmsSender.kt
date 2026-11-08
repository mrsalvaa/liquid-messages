// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidmessages.sms

import android.app.Activity
import android.app.PendingIntent
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.SmsManager
import dev.liquidmessages.core.sim.SimChip
import dev.liquidmessages.core.sms.Box
import dev.liquidmessages.notify.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Si la app es la de SMS predeterminada (sin eso no puede enviar ni guardar mensajes). */
object DefaultSms {
    fun isDefault(context: Context): Boolean =
        context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_SMS) == true

    /** El pedido del sistema para ser la predeterminada; null si el rol no existe. */
    fun request(context: Context): Intent? {
        val roles = context.getSystemService(RoleManager::class.java) ?: return null
        return if (roles.isRoleAvailable(RoleManager.ROLE_SMS)) roles.createRequestRoleIntent(RoleManager.ROLE_SMS) else null
    }
}

/**
 * Envía un SMS por un chip concreto. Nunca elige el chip: lo recibe ya decidido (ver SmsDecider);
 * [chip] null es "que decida el sistema", solo cuando no se sabe qué chips hay.
 */
object SmsSender {
    const val ACTION_SENT = "dev.liquidmessages.SMS_SENT"
    private const val EXTRA_PART = "part"
    private const val EXTRA_PARTS = "parts"

    /** Guarda el mensaje como "saliendo" y lo manda. Devuelve la fila guardada, o null si no se pudo. */
    fun send(context: Context, address: String, body: String, chip: SimChip?, store: SmsStore = SmsStore(context)): Uri? {
        val manager = managerFor(context, chip)
        val thread = store.threadFor(listOf(address))
        val uri = store.insertOutgoing(address, body, chip?.subscriptionId, thread) ?: return null
        val parts = manager.divideMessage(body)
        val sent = ArrayList<PendingIntent>(parts.size)
        for (i in parts.indices) {
            val intent = Intent(context, SmsStatusReceiver::class.java)
                .setAction(ACTION_SENT)
                .setData(uri)
                .putExtra(EXTRA_PART, i)
                .putExtra(EXTRA_PARTS, parts.size)
            val code = ((ContentUris.parseId(uri) * 31 + i) and 0x7FFFFFFF).toInt()
            sent += PendingIntent.getBroadcast(context, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        try {
            if (parts.size == 1) manager.sendTextMessage(address, null, body, sent[0], null)
            else manager.sendMultipartTextMessage(address, null, parts, sent, null)
        } catch (e: RuntimeException) {
            // Dirección inválida, sin servicio de SMS, etc.: queda como no enviado, a la vista.
            store.setBox(uri, Box.FAILED)
            return uri
        }
        return uri
    }

    private fun managerFor(context: Context, chip: SimChip?): SmsManager {
        val base = context.getSystemService(SmsManager::class.java)
        return if (chip != null) base.createForSubscriptionId(chip.subscriptionId) else base
    }

    /** Resultado de cada parte: una que falle deja el mensaje como no enviado. */
    internal fun onPartResult(context: Context, uri: Uri, ok: Boolean, part: Int, parts: Int, errorCode: Int) {
        val store = SmsStore(context)
        if (!ok) {
            store.setBox(uri, Box.FAILED, errorCode)
            Notifier.sendFailed(context, uri)
        } else if (part == parts - 1) {
            // La última parte; si una anterior falló, el estado ya es FAILED y se respeta.
            context.contentResolver.query(uri, arrayOf(android.provider.Telephony.Sms.TYPE), null, null, null)?.use { c ->
                if (c.moveToFirst() && c.getInt(0) != Box.FAILED.value) store.setBox(uri, Box.SENT)
            }
        }
    }

    internal fun partOf(intent: Intent) = intent.getIntExtra(EXTRA_PART, 0) to intent.getIntExtra(EXTRA_PARTS, 1)
}

/** Recibe el resultado del envío de cada parte. No es exportado: solo lo llama el sistema por el PendingIntent. */
class SmsStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val uri = intent.data ?: return
        val (part, parts) = SmsSender.partOf(intent)
        val ok = resultCode == Activity.RESULT_OK
        val error = intent.getIntExtra("errorCode", resultCode)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                SmsSender.onPartResult(context, uri, ok, part, parts, error)
            } finally {
                pending.finish()
            }
        }
    }
}
