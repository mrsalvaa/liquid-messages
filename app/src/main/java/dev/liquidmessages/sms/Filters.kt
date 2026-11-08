// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidmessages.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import dev.liquidmessages.core.sms.Box
import dev.liquidmessages.core.sms.Message
import dev.liquidmessages.core.sms.SenderKind
import dev.liquidmessages.core.text.Codes
import dev.liquidmessages.core.text.Spam
import dev.liquidmessages.data.AppSettings
import dev.liquidmessages.data.Stores

/**
 * Spam y limpieza de códigos. Todo se decide en el celular. Una conversación nunca es spam si es
 * de un contacto o si alguna vez se le escribió; lo que el usuario marcó a mano manda siempre.
 */
object Filters {
    /** Hilos sospechosos: sin mensajes enviados y con algún recibido que parece estafa. */
    fun suspiciousThreads(messages: List<Message>): Set<Long> {
        val wrote = HashSet<Long>()
        val flagged = HashSet<Long>()
        for (m in messages) {
            if (m.box.outgoing) wrote += m.threadId
            else if (!m.mms && m.threadId !in flagged && Spam.looksLikeSpam(m.address, m.body)) flagged += m.threadId
        }
        return flagged - wrote
    }

    /** Si una conversación va a "Posible spam". [known]: es de un contacto. */
    fun isSpam(settings: AppSettings, key: String, threadId: Long, known: Boolean, suspicious: Set<Long>): Boolean = when {
        key in settings.notSpam -> false
        key in settings.spam -> true
        else -> settings.spamFilter && !known && threadId in suspicious
    }

    /** Para las notificaciones: lo mismo, consultando solo esta conversación. Hace E/S. */
    fun isSpam(context: Context, key: String, threadId: Long, address: String?, unreadTexts: List<String>): Boolean {
        val s = Stores.settings.value
        if (key in s.notSpam) return false
        if (key in s.spam) return true
        if (!s.spamFilter || address == null) return false
        if (unreadTexts.none { Spam.looksLikeSpam(address, it) }) return false
        if (isContact(context, address)) return false
        // Si alguna vez se le escribió, no es spam.
        val wrote = context.contentResolver.query(
            Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms._ID),
            "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.TYPE} != ${Box.INBOX.value}", arrayOf(threadId.toString()), null,
        )?.use { it.count > 0 } ?: true
        return !wrote
    }

    private fun isContact(context: Context, address: String): Boolean {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return true
        if (SenderKind.of(address) == SenderKind.ALPHANUMERIC) return false
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address))
        return context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)?.use { it.count > 0 } ?: true
    }

    /**
     * Manda a la papelera los códigos de verificación ya leídos y más viejos que lo elegido en
     * Ajustes. No toca los de contactos ni los de conversaciones donde se escribió (un amigo que
     * pasa "el código del portón"). Solo siendo la app predeterminada. Hace E/S.
     */
    fun cleanCodes(context: Context, now: Long = System.currentTimeMillis()): Int {
        val hours = Stores.settings.value.codeCleanup
        if (hours <= 0 || !DefaultSms.isDefault(context)) return 0
        val cutoff = now - hours * 3_600_000L
        class Hit(val id: Long, val thread: Long, val address: String)
        val hits = ArrayList<Hit>()
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY),
            "${Telephony.Sms.READ} = 1 AND ${Telephony.Sms.DATE} < ?", arrayOf(cutoff.toString()), null,
        )?.use { c ->
            while (c.moveToNext()) {
                val body = c.getString(3).orEmpty()
                if (Codes.find(body) != null) hits += Hit(c.getLong(0), c.getLong(1), c.getString(2).orEmpty())
            }
        }
        if (hits.isEmpty()) return 0
        val contacts = HashMap<String, Boolean>()
        val wrote = HashMap<Long, Boolean>()
        val trash = Trash(context)
        var n = 0
        for ((thread, list) in hits.groupBy { it.thread }) {
            val address = list.first().address
            if (contacts.getOrPut(address) { isContact(context, address) }) continue
            val outgoing = wrote.getOrPut(thread) {
                context.contentResolver.query(
                    Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms._ID),
                    "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.TYPE} != ${Box.INBOX.value}", arrayOf(thread.toString()), null,
                )?.use { it.count > 0 } ?: true
            }
            if (outgoing) continue
            n += trash.deleteMessages(list.map { it.id }, listOf(address))
        }
        return n
    }
}
