// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidmessages.mms

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import dev.liquidmessages.mms.pdu.CharacterSets
import dev.liquidmessages.mms.pdu.EncodedStringValue
import dev.liquidmessages.mms.pdu.NotificationInd
import dev.liquidmessages.mms.pdu.PduBody
import dev.liquidmessages.mms.pdu.PduHeaders
import dev.liquidmessages.mms.pdu.RetrieveConf
import dev.liquidmessages.mms.pdu.SendReq

/**
 * Guarda MMS en el proveedor del sistema (tablas pdu, addr y part), como lo hace la app de SMS de
 * AOSP con PduPersister pero solo con lo que esta app usa. Solo funciona siendo la app de SMS
 * predeterminada. Nada de esto registra direcciones ni contenido.
 */
class MmsStore(context: Context) {
    private val app = context.applicationContext
    private val resolver = app.contentResolver

    /**
     * Números que en otros MMS recibidos son el único destinatario: es la línea propia. Se calcula
     * en el momento desde lo que ya está en el celular y no se guarda en ningún lado; sirve para
     * no contarse a uno mismo como participante de un grupo (la app no pide el número de línea).
     */
    private fun selfCandidates(): Set<String> {
        val out = HashSet<String>()
        resolver.query(Telephony.Mms.Inbox.CONTENT_URI, arrayOf(Telephony.Mms._ID), null, null, "${Telephony.Mms.DATE} DESC LIMIT 30")?.use { c ->
            while (c.moveToNext()) {
                val to = addresses(c.getLong(0), PduHeaders.TO)
                if (to.size == 1) out += normal(to[0])
            }
        }
        return out
    }

    private fun normal(a: String) = a.filter { it.isDigit() }.takeLast(8)

    private fun addresses(mmsId: Long, type: Int): List<String> =
        resolver.query(Uri.parse("content://mms/$mmsId/addr"), arrayOf(Telephony.Mms.Addr.ADDRESS), "${Telephony.Mms.Addr.TYPE} = $type", null, null)?.use { c ->
            buildList { while (c.moveToNext()) c.getString(0)?.takeIf { it.isNotBlank() && it != INSERT_ADDRESS_TOKEN }?.let(::add) }
        }.orEmpty()

    /** Participantes de un MMS recibido: quien lo mandó y los demás destinatarios, sin uno mismo. */
    private fun participants(from: String?, to: List<String>, cc: List<String>): List<String> {
        val others = to + cc
        val self = if (others.size > 1) selfCandidates() else emptySet()
        val rest = if (others.size <= 1) emptyList() else others.filter { normal(it) !in self }
        return (listOfNotNull(from) + rest).distinctBy(::normal).filter { it.isNotBlank() }
    }

    private fun EncodedStringValue?.text(): String? = this?.string?.takeIf { it.isNotBlank() }
    private fun Array<EncodedStringValue>?.texts(): List<String> = this?.mapNotNull { it.text() }.orEmpty()

    /** El aviso de un MMS (antes de bajarlo): se guarda para mostrarlo y poder reintentar la descarga. */
    fun insertNotification(ind: NotificationInd, subscriptionId: Int?): Uri? {
        val from = ind.from.text() ?: return null
        val thread = Telephony.Threads.getOrCreateThreadId(app, setOf(from))
        val uri = resolver.insert(
            Telephony.Mms.Inbox.CONTENT_URI,
            ContentValues().apply {
                put(Telephony.Mms.THREAD_ID, thread)
                put(Telephony.Mms.DATE, System.currentTimeMillis() / 1000)
                put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_INBOX)
                put(Telephony.Mms.READ, 0)
                put(Telephony.Mms.SEEN, 0)
                put(Telephony.Mms.MESSAGE_TYPE, PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND)
                put(Telephony.Mms.MMS_VERSION, ind.mmsVersion)
                ind.contentLocation?.let { put(Telephony.Mms.CONTENT_LOCATION, String(it)) }
                ind.transactionId?.let { put(Telephony.Mms.TRANSACTION_ID, String(it)) }
                put(Telephony.Mms.MESSAGE_SIZE, ind.messageSize)
                put(Telephony.Mms.EXPIRY, ind.expiry)
                ind.subject.text()?.let { put(Telephony.Mms.SUBJECT, it) }
                subscriptionId?.let { put(Telephony.Mms.SUBSCRIPTION_ID, it) }
            },
        ) ?: return null
        insertAddress(uri, from, PduHeaders.FROM)
        return uri
    }

    /** Lo que hace falta para bajar un aviso guardado: dónde está el MMS y su transacción. */
    fun pendingInfo(uri: Uri): Triple<String, String?, Int?>? =
        resolver.query(uri, arrayOf(Telephony.Mms.CONTENT_LOCATION, Telephony.Mms.TRANSACTION_ID, Telephony.Mms.SUBSCRIPTION_ID), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { Triple(it, c.getString(1), if (c.isNull(2)) null else c.getInt(2)) } else null
        }

    /**
     * Guarda un MMS bajado. Si venía de un aviso guardado ([replaces]), se reemplaza por el MMS
     * completo en el mismo hilo. Devuelve la fila nueva y los participantes.
     */
    fun insertRetrieved(conf: RetrieveConf, subscriptionId: Int?, replaces: Uri?): Pair<Uri, List<String>>? {
        val from = conf.from.text()
        val people = participants(from, conf.to.texts(), conf.cc.texts())
        if (people.isEmpty()) return null
        val thread = Telephony.Threads.getOrCreateThreadId(app, people.toSet())
        val body = conf.body
        val uri = resolver.insert(
            Telephony.Mms.Inbox.CONTENT_URI,
            ContentValues().apply {
                put(Telephony.Mms.THREAD_ID, thread)
                put(Telephony.Mms.DATE, System.currentTimeMillis() / 1000)
                if (conf.date > 0) put(Telephony.Mms.DATE_SENT, conf.date)
                put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_INBOX)
                put(Telephony.Mms.READ, 0)
                put(Telephony.Mms.SEEN, 0)
                put(Telephony.Mms.MESSAGE_TYPE, PduHeaders.MESSAGE_TYPE_RETRIEVE_CONF)
                put(Telephony.Mms.MMS_VERSION, conf.mmsVersion)
                conf.messageId?.let { put(Telephony.Mms.MESSAGE_ID, String(it)) }
                conf.transactionId?.let { put(Telephony.Mms.TRANSACTION_ID, String(it)) }
                conf.contentType?.let { put(Telephony.Mms.CONTENT_TYPE, String(it)) }
                conf.subject?.let {
                    put(Telephony.Mms.SUBJECT, it.string)
                    put(Telephony.Mms.SUBJECT_CHARSET, it.characterSet)
                }
                put(Telephony.Mms.TEXT_ONLY, if (body != null && (0 until body.partsNum).all { String(body.getPart(it).contentType ?: ByteArray(0)).startsWith("text/") }) 1 else 0)
                subscriptionId?.let { put(Telephony.Mms.SUBSCRIPTION_ID, it) }
            },
        ) ?: return null
        from?.let { insertAddress(uri, it, PduHeaders.FROM) }
        conf.to.texts().forEach { insertAddress(uri, it, PduHeaders.TO) }
        conf.cc.texts().forEach { insertAddress(uri, it, PduHeaders.CC) }
        body?.let { insertParts(uri, it) }
        replaces?.let { resolver.delete(it, null, null) }
        return uri to people
    }

    /** Guarda un MMS que se va a enviar (en "saliendo"); devuelve la fila. */
    fun insertOutgoing(req: SendReq, recipients: List<String>, subscriptionId: Int?): Uri? {
        val thread = Telephony.Threads.getOrCreateThreadId(app, recipients.toSet())
        val uri = resolver.insert(
            Telephony.Mms.Outbox.CONTENT_URI,
            ContentValues().apply {
                put(Telephony.Mms.THREAD_ID, thread)
                put(Telephony.Mms.DATE, System.currentTimeMillis() / 1000)
                put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_OUTBOX)
                put(Telephony.Mms.READ, 1)
                put(Telephony.Mms.SEEN, 1)
                put(Telephony.Mms.MESSAGE_TYPE, PduHeaders.MESSAGE_TYPE_SEND_REQ)
                put(Telephony.Mms.MMS_VERSION, req.mmsVersion)
                req.contentType?.let { put(Telephony.Mms.CONTENT_TYPE, String(it)) }
                req.subject?.let { put(Telephony.Mms.SUBJECT, it.string) }
                subscriptionId?.let { put(Telephony.Mms.SUBSCRIPTION_ID, it) }
            },
        ) ?: return null
        insertAddress(uri, INSERT_ADDRESS_TOKEN, PduHeaders.FROM)
        recipients.forEach { insertAddress(uri, it, PduHeaders.TO) }
        req.body?.let { insertParts(uri, it) }
        return uri
    }

    fun setBox(uri: Uri, box: Int, messageId: String? = null) {
        resolver.update(
            uri,
            ContentValues().apply {
                put(Telephony.Mms.MESSAGE_BOX, box)
                messageId?.let { put(Telephony.Mms.MESSAGE_ID, it) }
            },
            null,
            null,
        )
    }

    private fun insertAddress(mms: Uri, address: String, type: Int) {
        resolver.insert(
            Uri.parse("content://mms/${ContentUris.parseId(mms)}/addr"),
            ContentValues().apply {
                put(Telephony.Mms.Addr.ADDRESS, address)
                put(Telephony.Mms.Addr.TYPE, type)
                put(Telephony.Mms.Addr.CHARSET, CharacterSets.DEFAULT_CHARSET)
            },
        )
    }

    /** Las partes: el texto va en la columna "text"; lo demás (fotos, audio) en el archivo de la parte. */
    private fun insertParts(mms: Uri, body: PduBody) {
        val partsUri = Uri.parse("content://mms/${ContentUris.parseId(mms)}/part")
        for (i in 0 until body.partsNum) {
            val part = body.getPart(i)
            val type = part.contentType?.let(::String)?.lowercase() ?: "application/octet-stream"
            val values = ContentValues().apply {
                put(Telephony.Mms.Part.SEQ, i)
                put(Telephony.Mms.Part.CONTENT_TYPE, type)
                part.name?.let { put(Telephony.Mms.Part.NAME, String(it)) }
                part.filename?.let { put(Telephony.Mms.Part.FILENAME, String(it)) }
                part.contentId?.let { put(Telephony.Mms.Part.CONTENT_ID, String(it)) }
                part.contentLocation?.let { put(Telephony.Mms.Part.CONTENT_LOCATION, String(it)) }
                if (part.charset != 0) put(Telephony.Mms.Part.CHARSET, part.charset)
            }
            val data = part.data
            val isText = type == "text/plain" || type == "application/smil" || type.startsWith("text/")
            if (isText && type != "text/x-vcard" && type != "text/vcard") {
                values.put(Telephony.Mms.Part.TEXT, data?.let { decodeText(it, part.charset) }.orEmpty())
                resolver.insert(partsUri, values)
            } else {
                val row = resolver.insert(partsUri, values) ?: continue
                if (data != null) resolver.openOutputStream(row)?.use { it.write(data) }
            }
        }
    }

    private fun decodeText(data: ByteArray, charset: Int): String = try {
        if (charset == CharacterSets.ANY_CHARSET || charset == 0) String(data, Charsets.UTF_8)
        else String(data, charset(CharacterSets.getMimeName(charset)))
    } catch (e: Exception) {
        String(data, Charsets.UTF_8)
    }

    companion object {
        const val INSERT_ADDRESS_TOKEN = "insert-address-token"
    }
}
