// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidmessages.sms

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import android.util.AtomicFile
import dev.liquidmessages.mms.pdu.PduHeaders
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class TrashPart(
    val contentType: String,
    val name: String? = null,
    val contentId: String? = null,
    val contentLocation: String? = null,
    val charset: Int = 0,
    /** Partes de texto: el texto. */
    val text: String? = null,
    /** Partes binarias (fotos, audio): el archivo dentro de la carpeta de la entrada. */
    val file: String? = null,
)

@Serializable
data class TrashMessage(
    val mms: Boolean,
    val address: String,
    val body: String = "",
    val date: Long,
    val dateSent: Long = 0,
    /** Tipo de SMS (Telephony.Sms.TYPE) o caja de MMS (Telephony.Mms.MESSAGE_BOX). */
    val box: Int,
    val read: Boolean = true,
    val subscriptionId: Int? = null,
    val subject: String? = null,
    val messageType: Int = 0,
    /** MMS sin bajar: de dónde se baja y su transacción, para que "Descargar" siga andando al restaurar. */
    val contentLocation: String? = null,
    val transactionId: String? = null,
    val to: List<String> = emptyList(),
    val parts: List<TrashPart> = emptyList(),
)

/** Lo que se borró de una vez: un mensaje o una conversación entera. */
@Serializable
data class TrashEntry(
    val id: String,
    val deletedAt: Long,
    val participants: List<String>,
    val messages: List<TrashMessage>,
)

@Serializable
private data class TrashIndex(val entries: List<TrashEntry> = emptyList())

/**
 * Papelera de 30 días, dentro de la app (files/trash.json y files/trash/ID/ para los adjuntos de
 * MMS). Borrar guarda una copia primero y recién después borra del sistema; si guardar la copia
 * falla, no se borra nada. Restaurar devuelve los mensajes a su conversación. Nada de esto sale del
 * celular ni se registra en logcat.
 */
class Trash(context: Context) {
    private val app = context.applicationContext
    private val resolver = app.contentResolver
    private val index = File(app.filesDir, "trash.json")
    private val dir = File(app.filesDir, "trash").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @Synchronized
    fun entries(): List<TrashEntry> = load().entries.sortedByDescending { it.deletedAt }

    private fun load(): TrashIndex = runCatching { if (index.exists()) json.decodeFromString(TrashIndex.serializer(), index.readText()) else TrashIndex() }.getOrDefault(TrashIndex())

    private fun save(i: TrashIndex) {
        val f = AtomicFile(index)
        val out = f.startWrite()
        try {
            out.write(json.encodeToString(TrashIndex.serializer(), i).toByteArray())
            f.finishWrite(out)
        } catch (e: Exception) {
            f.failWrite(out)
            throw e
        }
    }

    /** Mueve mensajes a la papelera (los MMS van con id negativo, como los lee SystemSms). Devuelve cuántos. */
    @Synchronized
    fun deleteMessages(ids: Collection<Long>, participants: List<String>): Int = move(participants) { ids.toList() }

    /** Mueve conversaciones enteras a la papelera. */
    @Synchronized
    fun deleteThreads(threads: Map<Long, List<String>>): Int = threads.entries.sumOf { (thread, people) ->
        move(people) { idsOf(thread) }
    }

    private fun move(participants: List<String>, ids: () -> List<Long>): Int {
        val entryId = UUID.randomUUID().toString()
        val folder = File(dir, entryId)
        val list = ids()
        if (list.isEmpty()) return 0
        val copies = try {
            list.mapNotNull { id -> if (id < 0) copyMms(-id, folder) else copySms(id) }
        } catch (e: Exception) {
            folder.deleteRecursively()
            return 0
        }
        if (copies.isEmpty()) return 0
        // Primero la copia, después el borrado: si guardar falla, no se borra nada.
        try {
            save(load().let { it.copy(entries = it.entries + TrashEntry(entryId, System.currentTimeMillis(), participants, copies)) })
        } catch (e: Exception) {
            folder.deleteRecursively()
            return 0
        }
        list.forEach { id ->
            if (id < 0) resolver.delete(ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, -id), null, null)
            else resolver.delete(ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id), null, null)
        }
        return copies.size
    }

    private fun idsOf(thread: Long): List<Long> {
        val out = ArrayList<Long>()
        resolver.query(Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms._ID), "${Telephony.Sms.THREAD_ID} = ?", arrayOf(thread.toString()), null)?.use { c ->
            while (c.moveToNext()) out += c.getLong(0)
        }
        resolver.query(Telephony.Mms.CONTENT_URI, arrayOf(Telephony.Mms._ID), "${Telephony.Mms.THREAD_ID} = ?", arrayOf(thread.toString()), null)?.use { c ->
            while (c.moveToNext()) out += -c.getLong(0)
        }
        return out
    }

    private fun copySms(id: Long): TrashMessage? =
        resolver.query(
            ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id),
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.DATE_SENT, Telephony.Sms.TYPE, Telephony.Sms.READ, Telephony.Sms.SUBSCRIPTION_ID),
            null,
            null,
            null,
        )?.use { c ->
            if (!c.moveToFirst()) null
            else TrashMessage(
                mms = false,
                address = c.getString(0).orEmpty(),
                body = c.getString(1).orEmpty(),
                date = c.getLong(2),
                dateSent = c.getLong(3),
                box = c.getInt(4),
                read = c.getInt(5) != 0,
                subscriptionId = if (c.isNull(6)) null else c.getInt(6).takeIf { it >= 0 },
            )
        }

    private fun copyMms(id: Long, folder: File): TrashMessage? {
        val head = resolver.query(
            ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, id),
            arrayOf(Telephony.Mms.DATE, Telephony.Mms.DATE_SENT, Telephony.Mms.MESSAGE_BOX, Telephony.Mms.READ, Telephony.Mms.SUBSCRIPTION_ID, Telephony.Mms.SUBJECT, Telephony.Mms.MESSAGE_TYPE, Telephony.Mms.CONTENT_LOCATION, Telephony.Mms.TRANSACTION_ID),
            null,
            null,
            null,
        )?.use { c ->
            if (!c.moveToFirst()) null
            else TrashMessage(
                mms = true, address = "", date = c.getLong(0) * 1000, dateSent = c.getLong(1), box = c.getInt(2), read = c.getInt(3) != 0,
                subscriptionId = if (c.isNull(4)) null else c.getInt(4).takeIf { it >= 0 }, subject = c.getString(5), messageType = c.getInt(6),
                contentLocation = c.getString(7), transactionId = c.getString(8),
            )
        } ?: return null
        var from = ""
        val to = ArrayList<String>()
        resolver.query(Uri.parse("content://mms/$id/addr"), arrayOf(Telephony.Mms.Addr.ADDRESS, Telephony.Mms.Addr.TYPE), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val a = c.getString(0).orEmpty()
                when (c.getInt(1)) {
                    PduHeaders.FROM -> from = a
                    PduHeaders.TO, PduHeaders.CC -> to += a
                }
            }
        }
        val parts = ArrayList<TrashPart>()
        resolver.query(
            Uri.parse("content://mms/$id/part"),
            arrayOf(Telephony.Mms.Part._ID, Telephony.Mms.Part.CONTENT_TYPE, Telephony.Mms.Part.NAME, Telephony.Mms.Part.CONTENT_ID, Telephony.Mms.Part.CONTENT_LOCATION, Telephony.Mms.Part.CHARSET, Telephony.Mms.Part.TEXT),
            null,
            null,
            Telephony.Mms.Part.SEQ,
        )?.use { c ->
            while (c.moveToNext()) {
                val type = c.getString(1).orEmpty()
                val text = c.getString(6)
                val file = if (text == null) {
                    folder.mkdirs()
                    val name = "part-${parts.size}"
                    resolver.openInputStream(Uri.parse("content://mms/part/${c.getLong(0)}"))?.use { input -> File(folder, name).outputStream().use { input.copyTo(it) } }
                    name
                } else {
                    null
                }
                parts += TrashPart(type, c.getString(2), c.getString(3), c.getString(4), if (c.isNull(5)) 0 else c.getInt(5), text, file)
            }
        }
        val body = parts.filter { it.contentType == "text/plain" }.mapNotNull { it.text }.joinToString("\n")
        return head.copy(address = from, to = to, parts = parts, body = body)
    }

    /** Devuelve una entrada a sus conversaciones y la saca de la papelera. Devuelve cuántos mensajes volvieron. */
    @Synchronized
    fun restore(entryId: String): Int {
        val i = load()
        val entry = i.entries.firstOrNull { it.id == entryId } ?: return 0
        val folder = File(dir, entryId)
        val thread = Telephony.Threads.getOrCreateThreadId(app, entry.participants.ifEmpty { entry.messages.map { it.address }.filter(String::isNotBlank) }.toSet())
        var n = 0
        for (m in entry.messages) {
            val ok = if (m.mms) restoreMms(m, thread, folder) else restoreSms(m, thread)
            if (ok) n++
        }
        save(i.copy(entries = i.entries.filter { it.id != entryId }))
        folder.deleteRecursively()
        return n
    }

    private fun restoreSms(m: TrashMessage, thread: Long): Boolean =
        resolver.insert(
            Telephony.Sms.CONTENT_URI,
            ContentValues().apply {
                put(Telephony.Sms.ADDRESS, m.address)
                put(Telephony.Sms.BODY, m.body)
                put(Telephony.Sms.DATE, m.date)
                put(Telephony.Sms.DATE_SENT, m.dateSent)
                put(Telephony.Sms.TYPE, m.box)
                put(Telephony.Sms.READ, if (m.read) 1 else 0)
                put(Telephony.Sms.SEEN, 1)
                put(Telephony.Sms.THREAD_ID, thread)
                m.subscriptionId?.let { put(Telephony.Sms.SUBSCRIPTION_ID, it) }
            },
        ) != null

    private fun restoreMms(m: TrashMessage, thread: Long, folder: File): Boolean {
        val row = resolver.insert(
            Telephony.Mms.CONTENT_URI,
            ContentValues().apply {
                put(Telephony.Mms.THREAD_ID, thread)
                put(Telephony.Mms.DATE, m.date / 1000)
                if (m.dateSent > 0) put(Telephony.Mms.DATE_SENT, m.dateSent)
                put(Telephony.Mms.MESSAGE_BOX, m.box)
                put(Telephony.Mms.READ, if (m.read) 1 else 0)
                put(Telephony.Mms.SEEN, 1)
                put(Telephony.Mms.MESSAGE_TYPE, m.messageType.takeIf { it != 0 } ?: PduHeaders.MESSAGE_TYPE_RETRIEVE_CONF)
                m.subject?.let { put(Telephony.Mms.SUBJECT, it) }
                m.subscriptionId?.let { put(Telephony.Mms.SUBSCRIPTION_ID, it) }
                m.contentLocation?.let { put(Telephony.Mms.CONTENT_LOCATION, it) }
                m.transactionId?.let { put(Telephony.Mms.TRANSACTION_ID, it) }
            },
        ) ?: return false
        val id = ContentUris.parseId(row)
        fun addr(a: String, type: Int) = resolver.insert(Uri.parse("content://mms/$id/addr"), ContentValues().apply {
            put(Telephony.Mms.Addr.ADDRESS, a)
            put(Telephony.Mms.Addr.TYPE, type)
            put(Telephony.Mms.Addr.CHARSET, 106)
        })
        addr(m.address.ifBlank { "insert-address-token" }, PduHeaders.FROM)
        m.to.forEach { addr(it, PduHeaders.TO) }
        m.parts.forEachIndexed { seq, p ->
            val values = ContentValues().apply {
                put(Telephony.Mms.Part.SEQ, seq)
                put(Telephony.Mms.Part.CONTENT_TYPE, p.contentType)
                p.name?.let { put(Telephony.Mms.Part.NAME, it) }
                p.contentId?.let { put(Telephony.Mms.Part.CONTENT_ID, it) }
                p.contentLocation?.let { put(Telephony.Mms.Part.CONTENT_LOCATION, it) }
                if (p.charset != 0) put(Telephony.Mms.Part.CHARSET, p.charset)
                p.text?.let { put(Telephony.Mms.Part.TEXT, it) }
            }
            val part = resolver.insert(Uri.parse("content://mms/$id/part"), values)
            val file = p.file?.let { File(folder, it) }
            if (part != null && file != null && file.exists()) resolver.openOutputStream(part)?.use { out -> file.inputStream().use { it.copyTo(out) } }
        }
        return true
    }

    /** Borra para siempre una entrada (o todas con null). */
    @Synchronized
    fun purge(entryId: String?) {
        val i = load()
        val gone = if (entryId == null) i.entries else i.entries.filter { it.id == entryId }
        save(i.copy(entries = i.entries - gone.toSet()))
        gone.forEach { File(dir, it.id).deleteRecursively() }
    }

    /** Lo que pasó los 30 días se borra para siempre. Se llama al abrir la app. */
    @Synchronized
    fun cleanUp(now: Long = System.currentTimeMillis()) {
        val i = load()
        val old = i.entries.filter { now - it.deletedAt > DAYS_30 }
        if (old.isEmpty()) return
        save(i.copy(entries = i.entries - old.toSet()))
        old.forEach { File(dir, it.id).deleteRecursively() }
    }

    companion object {
        const val DAYS_30 = 30L * 24 * 60 * 60 * 1000
    }
}
