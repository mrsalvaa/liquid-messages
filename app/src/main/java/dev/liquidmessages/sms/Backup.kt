// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidmessages.sms

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import android.util.Base64
import android.util.Xml
import dev.liquidmessages.mms.pdu.PduHeaders
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Respaldo e importación en el formato XML de "SMS Backup & Restore" (`<smses>` con `<sms>` y
 * `<mms>`, los adjuntos en base64), para traer los mensajes de otra app o llevarlos. El archivo lo
 * elige el usuario con el selector del sistema: la app no pide permiso de almacenamiento.
 *
 * Exportar solo lee. Importar escribe en el proveedor (solo la app predeterminada puede) y saltea
 * lo que ya está, así importar dos veces no duplica nada. Nada de esto se registra en logcat.
 */
class Backup(context: Context) {
    private val app = context.applicationContext
    private val resolver = app.contentResolver

    data class Result(val sms: Int, val mms: Int, val skipped: Int, val failed: Int)

    // ---------------------------------------------------------------- exportar

    /** Escribe todos los SMS y MMS. [progress] recibe cuántos lleva. Devuelve cuántos de cada uno. */
    fun export(out: OutputStream, progress: (Int) -> Unit = {}): Result {
        val total = count(Telephony.Sms.CONTENT_URI) + count(Telephony.Mms.CONTENT_URI)
        val x = Xml.newSerializer()
        x.setOutput(out, "UTF-8")
        x.startDocument("UTF-8", true)
        x.startTag(null, "smses")
        x.attribute(null, "count", total.toString())
        x.attribute(null, "backup_set", UUID.randomUUID().toString())
        x.attribute(null, "backup_date", System.currentTimeMillis().toString())
        x.attribute(null, "type", "full")
        var sms = 0
        var mms = 0
        resolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.DATE, Telephony.Sms.DATE_SENT, Telephony.Sms.TYPE, Telephony.Sms.BODY, Telephony.Sms.READ, Telephony.Sms.SUBSCRIPTION_ID, Telephony.Sms.STATUS),
            null,
            null,
            "${Telephony.Sms.DATE} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                x.startTag(null, "sms")
                x.attribute(null, "protocol", "0")
                x.attribute(null, "address", c.getString(0).orEmpty())
                x.attribute(null, "date", c.getLong(1).toString())
                x.attribute(null, "type", c.getInt(3).toString())
                x.attribute(null, "subject", "null")
                x.attribute(null, "body", c.getString(4).orEmpty())
                x.attribute(null, "toa", "null")
                x.attribute(null, "sc_toa", "null")
                x.attribute(null, "service_center", "null")
                x.attribute(null, "read", c.getInt(5).toString())
                x.attribute(null, "status", if (c.isNull(7)) "-1" else c.getInt(7).toString())
                x.attribute(null, "locked", "0")
                x.attribute(null, "date_sent", c.getLong(2).toString())
                x.attribute(null, "sub_id", if (c.isNull(6)) "-1" else c.getInt(6).toString())
                x.endTag(null, "sms")
                sms++
                if (sms % 100 == 0) progress(sms)
            }
        }
        resolver.query(
            Telephony.Mms.CONTENT_URI,
            arrayOf(Telephony.Mms._ID, Telephony.Mms.DATE, Telephony.Mms.DATE_SENT, Telephony.Mms.MESSAGE_BOX, Telephony.Mms.READ, Telephony.Mms.MESSAGE_TYPE, Telephony.Mms.SUBJECT, Telephony.Mms.CONTENT_TYPE, Telephony.Mms.SUBSCRIPTION_ID, Telephony.Mms.THREAD_ID),
            null,
            null,
            "${Telephony.Mms.DATE} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                // Un aviso sin bajar no tiene contenido: no se exporta.
                if (c.getInt(5) == PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND) continue
                val addrs = addresses(id)
                x.startTag(null, "mms")
                x.attribute(null, "date", (c.getLong(1) * 1000).toString())
                x.attribute(null, "date_sent", c.getLong(2).toString())
                x.attribute(null, "msg_box", c.getInt(3).toString())
                x.attribute(null, "read", c.getInt(4).toString())
                x.attribute(null, "m_type", c.getInt(5).toString())
                x.attribute(null, "sub", c.getString(6) ?: "null")
                x.attribute(null, "ct_t", c.getString(7) ?: "application/vnd.wap.multipart.related")
                x.attribute(null, "sub_id", if (c.isNull(8)) "-1" else c.getInt(8).toString())
                x.attribute(null, "address", participants(c.getLong(9)).joinToString("~"))
                x.startTag(null, "parts")
                resolver.query(
                    Uri.parse("content://mms/$id/part"),
                    arrayOf(Telephony.Mms.Part._ID, Telephony.Mms.Part.SEQ, Telephony.Mms.Part.CONTENT_TYPE, Telephony.Mms.Part.NAME, Telephony.Mms.Part.CHARSET, Telephony.Mms.Part.CONTENT_ID, Telephony.Mms.Part.CONTENT_LOCATION, Telephony.Mms.Part.FILENAME, Telephony.Mms.Part.TEXT),
                    null,
                    null,
                    Telephony.Mms.Part.SEQ,
                )?.use { p ->
                    while (p.moveToNext()) {
                        x.startTag(null, "part")
                        x.attribute(null, "seq", p.getInt(1).toString())
                        x.attribute(null, "ct", p.getString(2).orEmpty())
                        x.attribute(null, "name", p.getString(3) ?: "null")
                        x.attribute(null, "chset", if (p.isNull(4)) "null" else p.getInt(4).toString())
                        x.attribute(null, "cid", p.getString(5) ?: "null")
                        x.attribute(null, "cl", p.getString(6) ?: "null")
                        x.attribute(null, "fn", p.getString(7) ?: "null")
                        val text = p.getString(8)
                        if (text != null) {
                            x.attribute(null, "text", text)
                        } else {
                            val bytes = resolver.openInputStream(Uri.parse("content://mms/part/${p.getLong(0)}"))?.use { it.readBytes() }
                            if (bytes != null) x.attribute(null, "data", Base64.encodeToString(bytes, Base64.NO_WRAP))
                        }
                        x.endTag(null, "part")
                    }
                }
                x.endTag(null, "parts")
                x.startTag(null, "addrs")
                for ((a, type) in addrs) {
                    x.startTag(null, "addr")
                    x.attribute(null, "address", a)
                    x.attribute(null, "type", type.toString())
                    x.attribute(null, "charset", "106")
                    x.endTag(null, "addr")
                }
                x.endTag(null, "addrs")
                x.endTag(null, "mms")
                mms++
                if ((sms + mms) % 20 == 0) progress(sms + mms)
            }
        }
        x.endTag(null, "smses")
        x.endDocument()
        x.flush()
        return Result(sms, mms, 0, 0)
    }

    private fun count(uri: Uri): Int = resolver.query(uri, arrayOf("_id"), null, null, null)?.use { it.count } ?: 0

    private fun addresses(mmsId: Long): List<Pair<String, Int>> =
        resolver.query(Uri.parse("content://mms/$mmsId/addr"), arrayOf(Telephony.Mms.Addr.ADDRESS, Telephony.Mms.Addr.TYPE), null, null, null)?.use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0).orEmpty() to c.getInt(1)) }
        }.orEmpty()

    private val canonical by lazy {
        val map = HashMap<Long, String>()
        resolver.query(Uri.parse("content://mms-sms/canonical-addresses"), arrayOf("_id", "address"), null, null, null)?.use { c ->
            while (c.moveToNext()) map[c.getLong(0)] = c.getString(1).orEmpty()
        }
        map
    }

    private fun participants(thread: Long): List<String> =
        resolver.query(Uri.parse("content://mms-sms/conversations?simple=true"), arrayOf(Telephony.Threads.RECIPIENT_IDS), "${Telephony.Threads._ID} = ?", arrayOf(thread.toString()), null)?.use { c ->
            if (c.moveToFirst()) c.getString(0).orEmpty().split(' ').mapNotNull { it.toLongOrNull()?.let(canonical::get) } else emptyList()
        }.orEmpty()

    // ---------------------------------------------------------------- importar

    private fun tail(a: String) = a.filter { it.isDigit() }.takeLast(8).ifEmpty { a.trim().lowercase() }

    /** Lo que ya hay, para no duplicar: SMS por fecha, tipo, número y texto; MMS por fecha (en segundos), caja y texto. */
    private fun existing(): Pair<HashSet<String>, HashSet<String>> {
        val sms = HashSet<String>()
        resolver.query(Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.ADDRESS, Telephony.Sms.BODY), null, null, null)?.use { c ->
            while (c.moveToNext()) sms += smsKey(c.getLong(0), c.getInt(1), c.getString(2).orEmpty(), c.getString(3).orEmpty())
        }
        val mms = HashSet<String>()
        resolver.query(Telephony.Mms.CONTENT_URI, arrayOf(Telephony.Mms.DATE, Telephony.Mms.MESSAGE_BOX), null, null, null)?.use { c ->
            while (c.moveToNext()) mms += "${c.getLong(0)}|${c.getInt(1)}"
        }
        return sms to mms
    }

    private fun smsKey(date: Long, type: Int, address: String, body: String) = "$date|$type|${tail(address)}|${body.hashCode()}"

    /** Lee un respaldo y guarda lo que falta. [progress] recibe cuántos lleva. */
    fun import(input: InputStream, progress: (Int) -> Unit = {}): Result {
        val (haveSms, haveMms) = existing()
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(input, null)
        var sms = 0
        var mms = 0
        var skipped = 0
        var failed = 0
        val pending = ArrayList<ContentValues>()
        fun flush() {
            if (pending.isEmpty()) return
            sms += resolver.bulkInsert(Telephony.Sms.CONTENT_URI, pending.toTypedArray())
            pending.clear()
        }
        // Un MMS en armado: atributos, partes y direcciones.
        var mmsAttrs: Map<String, String>? = null
        val parts = ArrayList<Map<String, String>>()
        val addrs = ArrayList<Map<String, String>>()
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "sms" -> {
                        val a = attrs(p)
                        val date = a["date"]?.toLongOrNull() ?: continue
                        val type = a["type"]?.toIntOrNull() ?: Telephony.Sms.MESSAGE_TYPE_INBOX
                        val address = a["address"].orEmpty()
                        val body = a["body"].orEmpty()
                        if (!haveSms.add(smsKey(date, type, address, body))) {
                            skipped++
                            continue
                        }
                        pending += ContentValues().apply {
                            put(Telephony.Sms.ADDRESS, address)
                            put(Telephony.Sms.BODY, body)
                            put(Telephony.Sms.DATE, date)
                            a["date_sent"]?.toLongOrNull()?.let { put(Telephony.Sms.DATE_SENT, it) }
                            put(Telephony.Sms.TYPE, type)
                            put(Telephony.Sms.READ, a["read"]?.toIntOrNull() ?: 1)
                            put(Telephony.Sms.SEEN, 1)
                            a["sub_id"]?.toIntOrNull()?.takeIf { it >= 0 }?.let { put(Telephony.Sms.SUBSCRIPTION_ID, it) }
                        }
                        if (pending.size >= 200) {
                            flush()
                            progress(sms + mms)
                        }
                    }
                    "mms" -> {
                        mmsAttrs = attrs(p)
                        parts.clear()
                        addrs.clear()
                    }
                    "part" -> if (mmsAttrs != null) parts += attrs(p)
                    "addr" -> if (mmsAttrs != null) addrs += attrs(p)
                }
                XmlPullParser.END_TAG -> if (p.name == "mms") {
                    val a = mmsAttrs ?: continue
                    mmsAttrs = null
                    flush()
                    when (insertMms(a, parts, addrs, haveMms)) {
                        true -> mms++
                        false -> failed++
                        null -> skipped++
                    }
                    if ((sms + mms) % 20 == 0) progress(sms + mms)
                }
            }
        }
        flush()
        return Result(sms, mms, skipped, failed)
    }

    private fun attrs(p: XmlPullParser): Map<String, String> =
        (0 until p.attributeCount).associate { p.getAttributeName(it) to p.getAttributeValue(it) }.filterValues { it != "null" }

    /** true si se guardó, false si falló, null si ya estaba. */
    private fun insertMms(a: Map<String, String>, parts: List<Map<String, String>>, addrs: List<Map<String, String>>, have: HashSet<String>): Boolean? {
        // La fecha viene en milisegundos; algunas apps la dejan en segundos.
        val raw = a["date"]?.toLongOrNull() ?: return false
        val seconds = if (raw > 100_000_000_000L) raw / 1000 else raw
        val box = a["msg_box"]?.toIntOrNull() ?: Telephony.Mms.MESSAGE_BOX_INBOX
        if (!have.add("$seconds|$box")) return null
        val people = a["address"].orEmpty().split('~').map { it.trim() }.filter { it.isNotEmpty() && it != "insert-address-token" }
            .ifEmpty { addrs.mapNotNull { it["address"] }.filter { it != "insert-address-token" } }
        if (people.isEmpty()) return false
        return try {
            val thread = Telephony.Threads.getOrCreateThreadId(app, people.toSet())
            val row = resolver.insert(
                Telephony.Mms.CONTENT_URI,
                ContentValues().apply {
                    put(Telephony.Mms.THREAD_ID, thread)
                    put(Telephony.Mms.DATE, seconds)
                    a["date_sent"]?.toLongOrNull()?.let { put(Telephony.Mms.DATE_SENT, it) }
                    put(Telephony.Mms.MESSAGE_BOX, box)
                    put(Telephony.Mms.READ, a["read"]?.toIntOrNull() ?: 1)
                    put(Telephony.Mms.SEEN, 1)
                    put(Telephony.Mms.MESSAGE_TYPE, a["m_type"]?.toIntOrNull() ?: if (box == Telephony.Mms.MESSAGE_BOX_INBOX) PduHeaders.MESSAGE_TYPE_RETRIEVE_CONF else PduHeaders.MESSAGE_TYPE_SEND_REQ)
                    a["sub"]?.let { put(Telephony.Mms.SUBJECT, it) }
                    a["ct_t"]?.let { put(Telephony.Mms.CONTENT_TYPE, it) }
                    a["sub_id"]?.toIntOrNull()?.takeIf { it >= 0 }?.let { put(Telephony.Mms.SUBSCRIPTION_ID, it) }
                },
            ) ?: return false
            val id = ContentUris.parseId(row)
            for (ad in addrs) {
                resolver.insert(Uri.parse("content://mms/$id/addr"), ContentValues().apply {
                    put(Telephony.Mms.Addr.ADDRESS, ad["address"].orEmpty())
                    put(Telephony.Mms.Addr.TYPE, ad["type"]?.toIntOrNull() ?: PduHeaders.TO)
                    put(Telephony.Mms.Addr.CHARSET, ad["charset"]?.toIntOrNull() ?: 106)
                })
            }
            for (pt in parts) {
                val values = ContentValues().apply {
                    put(Telephony.Mms.Part.SEQ, pt["seq"]?.toIntOrNull() ?: 0)
                    put(Telephony.Mms.Part.CONTENT_TYPE, pt["ct"] ?: "application/octet-stream")
                    pt["name"]?.let { put(Telephony.Mms.Part.NAME, it) }
                    pt["chset"]?.toIntOrNull()?.let { put(Telephony.Mms.Part.CHARSET, it) }
                    pt["cid"]?.let { put(Telephony.Mms.Part.CONTENT_ID, it) }
                    pt["cl"]?.let { put(Telephony.Mms.Part.CONTENT_LOCATION, it) }
                    pt["fn"]?.let { put(Telephony.Mms.Part.FILENAME, it) }
                    pt["text"]?.let { put(Telephony.Mms.Part.TEXT, it) }
                }
                val part = resolver.insert(Uri.parse("content://mms/$id/part"), values) ?: continue
                pt["data"]?.let { b64 -> resolver.openOutputStream(part)?.use { it.write(Base64.decode(b64, Base64.DEFAULT)) } }
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}
