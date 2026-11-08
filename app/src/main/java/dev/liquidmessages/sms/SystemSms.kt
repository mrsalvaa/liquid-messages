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
import dev.liquidmessages.core.sms.Attachment
import dev.liquidmessages.core.sms.Box
import dev.liquidmessages.core.sms.Message
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import dev.liquidmessages.mms.pdu.PduHeaders

/**
 * Lectura de los mensajes del sistema (proveedor Telephony). Necesita READ_SMS. De un MMS se lee
 * el texto y la lista de adjuntos; el contenido de un adjunto (una foto) se carga recién al
 * mostrarlo, desde content://mms/part/ID. Todas las funciones hacen E/S: llamarlas fuera del hilo principal. Nada de esto se registra en
 * logcat.
 */
class SystemSms(context: Context) {
    private val resolver: ContentResolver = context.contentResolver

    /** Todos los SMS y MMS. Los id de MMS van en negativo para no chocar con los de SMS. */
    fun messages(participants: Map<Long, List<String>>): List<Message> = sms() + mms(participants)

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

    /**
     * Los MMS con su texto, adjuntos y asunto. Las partes se leen todas juntas (una consulta); el
     * remitente de cada MMS recibido se busca solo en las conversaciones de grupo, donde hace
     * falta saber quién lo mandó.
     */
    private fun mms(participants: Map<Long, List<String>>): List<Message> {
        class Parts(val text: StringBuilder = StringBuilder(), val files: MutableList<Attachment> = ArrayList())
        val parts = HashMap<Long, Parts>()
        resolver.query(PARTS, arrayOf(Telephony.Mms.Part._ID, Telephony.Mms.Part.MSG_ID, Telephony.Mms.Part.CONTENT_TYPE, Telephony.Mms.Part.TEXT, Telephony.Mms.Part.NAME, Telephony.Mms.Part.FILENAME, Telephony.Mms.Part.CONTENT_LOCATION), null, null, Telephony.Mms.Part.SEQ)?.use { c ->
            while (c.moveToNext()) {
                val mid = c.getLong(1)
                val type = c.getString(2).orEmpty().lowercase()
                val p = parts.getOrPut(mid) { Parts() }
                when {
                    type == "application/smil" -> Unit
                    type == "text/plain" -> c.getString(3)?.takeIf { it.isNotBlank() }?.let { if (p.text.isNotEmpty()) p.text.append('\n'); p.text.append(it) }
                    else -> p.files += Attachment(c.getLong(0), type, c.getString(4) ?: c.getString(5) ?: c.getString(6))
                }
            }
        }
        return resolver.query(
            Telephony.Mms.CONTENT_URI,
            arrayOf(Telephony.Mms._ID, Telephony.Mms.THREAD_ID, Telephony.Mms.DATE, Telephony.Mms.MESSAGE_BOX, Telephony.Mms.READ, Telephony.Mms.SUBSCRIPTION_ID, Telephony.Mms.MESSAGE_TYPE, Telephony.Mms.SUBJECT),
            null,
            null,
            null,
        )?.use { c ->
            buildList(c.count) {
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val thread = c.getLong(1)
                    val box = when (c.getInt(3)) {
                        Telephony.Mms.MESSAGE_BOX_SENT -> Box.SENT
                        Telephony.Mms.MESSAGE_BOX_OUTBOX -> Box.OUTBOX
                        Telephony.Mms.MESSAGE_BOX_DRAFTS -> Box.DRAFT
                        Telephony.Mms.MESSAGE_BOX_FAILED -> Box.FAILED
                        else -> Box.INBOX
                    }
                    val people = participants[thread].orEmpty()
                    val from = when {
                        box != Box.INBOX -> ""
                        people.size == 1 -> people[0]
                        else -> sender(id).orEmpty()
                    }
                    val p = parts[id]
                    add(
                        Message(
                            id = -id,
                            threadId = thread,
                            address = from,
                            body = p?.text?.toString().orEmpty(),
                            // En MMS la fecha está en segundos.
                            date = c.getLong(2) * 1000,
                            box = box,
                            read = c.getInt(4) != 0,
                            subscriptionId = c.intOrNull(5)?.takeIf { it >= 0 },
                            mms = true,
                            attachments = p?.files.orEmpty(),
                            subject = c.getString(7)?.takeIf { it.isNotBlank() },
                            pending = c.getInt(6) == PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND,
                        ),
                    )
                }
            }
        }.orEmpty()
    }

    /** Quién mandó un MMS recibido (la dirección "From"). */
    private fun sender(mmsId: Long): String? =
        resolver.query(Uri.parse("content://mms/$mmsId/addr"), arrayOf(Telephony.Mms.Addr.ADDRESS), "${Telephony.Mms.Addr.TYPE} = ${PduHeaders.FROM}", null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() && it != INSERT_ADDRESS_TOKEN } else null
        }

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
        val PARTS: Uri = Uri.parse("content://mms/part")

        /** Lo que guarda el sistema como "From" de un MMS propio: "mi número", sin decirlo. */
        const val INSERT_ADDRESS_TOKEN = "insert-address-token"

        fun Cursor.intOrNull(i: Int): Int? = if (isNull(i)) null else getInt(i)
    }
}
