// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidmessages.sms

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import dev.liquidmessages.core.sms.Box
import dev.liquidmessages.core.sms.Message
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

/**
 * Lectura de los mensajes del sistema (proveedor Telephony). Necesita READ_SMS. Hasta la etapa de
 * MMS, de un MMS solo se lee cuándo llegó, a qué conversación es y si se leyó: nunca su contenido.
 * Todas las funciones hacen E/S: llamarlas fuera del hilo principal. Nada de esto se registra en
 * logcat.
 */
class SystemSms(context: Context) {
    private val resolver: ContentResolver = context.contentResolver

    /** Todos los SMS y, como marcadores, los MMS. Los id de MMS van en negativo para no chocar. */
    fun messages(): List<Message> = sms() + mmsMarkers()

    private fun sms(): List<Message> =
        resolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(
                Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
                Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.READ, Telephony.Sms.SUBSCRIPTION_ID,
            ),
            null,
            null,
            null,
        )?.use { c ->
            buildList(c.count) {
                while (c.moveToNext()) {
                    add(
                        Message(
                            id = c.getLong(0),
                            threadId = c.getLong(1),
                            address = c.getString(2).orEmpty(),
                            body = c.getString(3).orEmpty(),
                            date = c.getLong(4),
                            box = Box.of(c.getInt(5)),
                            read = c.getInt(6) != 0,
                            subscriptionId = c.intOrNull(7)?.takeIf { it >= 0 },
                        ),
                    )
                }
            }
        }.orEmpty()

    private fun mmsMarkers(): List<Message> =
        resolver.query(
            Telephony.Mms.CONTENT_URI,
            arrayOf(Telephony.Mms._ID, Telephony.Mms.THREAD_ID, Telephony.Mms.DATE, Telephony.Mms.MESSAGE_BOX, Telephony.Mms.READ, Telephony.Mms.SUBSCRIPTION_ID),
            null,
            null,
            null,
        )?.use { c ->
            buildList(c.count) {
                while (c.moveToNext()) {
                    val box = when (c.getInt(3)) {
                        Telephony.Mms.MESSAGE_BOX_SENT -> Box.SENT
                        Telephony.Mms.MESSAGE_BOX_OUTBOX -> Box.OUTBOX
                        Telephony.Mms.MESSAGE_BOX_DRAFTS -> Box.DRAFT
                        Telephony.Mms.MESSAGE_BOX_FAILED -> Box.FAILED
                        else -> Box.INBOX
                    }
                    add(
                        Message(
                            id = -c.getLong(0),
                            threadId = c.getLong(1),
                            address = "",
                            body = "",
                            // En MMS la fecha está en segundos.
                            date = c.getLong(2) * 1000,
                            box = box,
                            read = c.getInt(4) != 0,
                            subscriptionId = c.intOrNull(5)?.takeIf { it >= 0 },
                            mms = true,
                        ),
                    )
                }
            }
        }.orEmpty()

    /**
     * Participantes de cada conversación, como los guarda el sistema: cada hilo tiene ids de
     * "direcciones canónicas", y esa tabla tiene el número o nombre de cada una.
     */
    fun participants(): Map<Long, List<String>> {
        val addresses = HashMap<Long, String>()
        resolver.query(CANONICAL, arrayOf("_id", "address"), null, null, null)?.use { c ->
            while (c.moveToNext()) addresses[c.getLong(0)] = c.getString(1).orEmpty()
        }
        val out = HashMap<Long, List<String>>()
        resolver.query(THREADS, arrayOf(Telephony.Threads._ID, Telephony.Threads.RECIPIENT_IDS), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val ids = c.getString(1).orEmpty().split(' ').mapNotNull { it.trim().toLongOrNull() }
                out[c.getLong(0)] = ids.mapNotNull { addresses[it] }.filter { it.isNotBlank() }
            }
        }
        return out
    }

    /** Emite cada vez que cambia algo en los mensajes (de esta app o de cualquier otra). */
    fun changes(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        resolver.registerContentObserver(Telephony.MmsSms.CONTENT_URI, true, observer)
        resolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
        trySend(Unit)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }.conflate()

    private companion object {
        val THREADS: Uri = Uri.parse("content://mms-sms/conversations?simple=true")
        val CANONICAL: Uri = Uri.parse("content://mms-sms/canonical-addresses")

        fun Cursor.intOrNull(i: Int): Int? = if (isNull(i)) null else getInt(i)
    }
}
