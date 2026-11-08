// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidmessages.sms

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import dev.liquidmessages.core.sms.Box

/**
 * Escritura en el proveedor de mensajes. Solo funciona siendo la app de SMS predeterminada: las
 * demás no pueden escribir ahí. Todas las funciones hacen E/S: llamarlas fuera del hilo principal.
 * Nada de esto registra direcciones ni textos en logcat.
 */
class SmsStore(context: Context) {
    private val app = context.applicationContext
    private val resolver = app.contentResolver

    /** El hilo de una conversación con estas direcciones (lo crea si no existe). */
    fun threadFor(addresses: Collection<String>): Long = Telephony.Threads.getOrCreateThreadId(app, addresses.toSet())

    /** Un SMS recibido, sin leer. [sentAt] es la hora que puso la red; [date] la de llegada. */
    fun insertIncoming(address: String, body: String, sentAt: Long, subscriptionId: Int?, date: Long = System.currentTimeMillis()): Uri? =
        resolver.insert(
            Telephony.Sms.Inbox.CONTENT_URI,
            ContentValues().apply {
                put(Telephony.Sms.ADDRESS, address)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, date)
                put(Telephony.Sms.DATE_SENT, sentAt)
                put(Telephony.Sms.READ, 0)
                put(Telephony.Sms.SEEN, 0)
                subscriptionId?.let { put(Telephony.Sms.SUBSCRIPTION_ID, it) }
            },
        )

    /** Un SMS que se va a enviar: entra como "saliendo" y después pasa a enviado o a no enviado. */
    fun insertOutgoing(address: String, body: String, subscriptionId: Int?, threadId: Long): Uri? =
        resolver.insert(
            Telephony.Sms.Outbox.CONTENT_URI,
            ContentValues().apply {
                put(Telephony.Sms.ADDRESS, address)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, System.currentTimeMillis())
                put(Telephony.Sms.READ, 1)
                put(Telephony.Sms.SEEN, 1)
                put(Telephony.Sms.THREAD_ID, threadId)
                subscriptionId?.let { put(Telephony.Sms.SUBSCRIPTION_ID, it) }
            },
        )

    fun setBox(uri: Uri, box: Box, errorCode: Int? = null) {
        resolver.update(
            uri,
            ContentValues().apply {
                put(Telephony.Sms.TYPE, box.value)
                errorCode?.let { put(Telephony.Sms.ERROR_CODE, it) }
            },
            null,
            null,
        )
    }

    /** Marca leída y vista toda una conversación, SMS y MMS. Devuelve cuántos cambió. */
    fun markRead(threadId: Long): Int {
        val values = ContentValues().apply {
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
        }
        val where = "${Telephony.Sms.THREAD_ID} = ? AND (${Telephony.Sms.READ} = 0 OR ${Telephony.Sms.SEEN} = 0)"
        val args = arrayOf(threadId.toString())
        return resolver.update(Telephony.Sms.CONTENT_URI, values, where, args) +
            resolver.update(Telephony.Mms.CONTENT_URI, values, where, args)
    }

    /** Borra mensajes por id (los MMS van en negativo, como los lee SystemSms). */
    fun delete(ids: Collection<Long>): Int = ids.sumOf { id ->
        if (id < 0) resolver.delete(ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, -id), null, null)
        else resolver.delete(ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id), null, null)
    }

    /** Borra conversaciones enteras, SMS y MMS. */
    fun deleteThreads(threadIds: Collection<Long>): Int = threadIds.sumOf { t ->
        resolver.delete(ContentUris.withAppendedId(Telephony.Threads.CONTENT_URI, t), null, null)
    }
}
