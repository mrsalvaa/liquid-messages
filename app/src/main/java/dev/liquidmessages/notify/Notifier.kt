// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidmessages.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import dev.liquidmessages.MainActivity
import dev.liquidmessages.R
import dev.liquidmessages.core.phone.PhoneNumbers
import dev.liquidmessages.core.sms.Conversations
import dev.liquidmessages.core.sms.SenderKind
import dev.liquidmessages.core.text.Codes
import dev.liquidmessages.data.Stores
import dev.liquidmessages.mms.MmsText

/**
 * Notificaciones de mensajes. En la pantalla bloqueada solo dice "Mensaje nuevo" (sin quién ni
 * qué): el contenido se ve al desbloquear. Nada de esto se registra en logcat.
 */
object Notifier {
    private const val CHANNEL_MESSAGES = "messages"
    private const val CHANNEL_FAILED = "failed"
    const val EXTRA_THREAD = "thread"
    const val EXTRA_ADDRESS = "address"
    const val EXTRA_TEXT = "text"
    const val KEY_REPLY = "reply"

    fun channels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGES, context.getString(R.string.channel_messages), NotificationManager.IMPORTANCE_HIGH).apply {
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_FAILED, context.getString(R.string.channel_failed), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    /** En Android 12 el permiso no existe: las notificaciones están permitidas salvo que se apaguen en el sistema. */
    fun allowed(context: Context) =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun numbers() = Stores.settings.value.let { PhoneNumbers(it.defaultCountry.ifEmpty { "AR" }, it.defaultAreaCode.ifEmpty { null }) }

    /** Abrir la app en una conversación (y, si hay, con un texto escrito). */
    fun openThread(context: Context, threadId: Long, address: String?, text: String? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction("dev.liquidmessages.OPEN_THREAD")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_THREAD, threadId)
            .putExtra(EXTRA_ADDRESS, address)
            .putExtra(EXTRA_TEXT, text)
        return PendingIntent.getActivity(context, threadId.toInt(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun action(context: Context, action: String, threadId: Long, address: String, mutable: Boolean = false, extra: Intent.() -> Unit = {}): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java)
            .setAction(action)
            .putExtra(EXTRA_THREAD, threadId)
            .putExtra(EXTRA_ADDRESS, address)
            .apply(extra)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, (threadId * 7 + action.hashCode()).toInt(), intent, flags)
    }

    /** Nombre y foto de un número, si es de un solo contacto. */
    private fun contact(context: Context, address: String): Pair<String, Uri?>? {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        if (SenderKind.of(address) == SenderKind.ALPHANUMERIC) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address))
        return context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME, ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI), null, null, null)?.use { c ->
            if (c.count == 1 && c.moveToFirst()) c.getString(0)?.let { it to c.getString(1)?.let(Uri::parse) } else null
        }
    }

    private fun person(context: Context, address: String, numbers: PhoneNumbers): Person {
        val found = contact(context, address)
        return Person.Builder()
            .setName(found?.first ?: if (SenderKind.of(address) == SenderKind.ALPHANUMERIC) address else numbers.format(address))
            .apply { found?.second?.let { setIcon(Icon.createWithContentUri(it)) } }
            .setKey(address)
            .build()
    }

    private class Unread(val address: String, val text: String, val date: Long)

    /** Los mensajes sin leer de una conversación, del más viejo al más nuevo (los últimos 8). */
    private fun unread(context: Context, threadId: Long): List<Unread> {
        val out = ArrayList<Unread>()
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.READ} = 0",
            arrayOf(threadId.toString()),
            "${Telephony.Sms.DATE} DESC LIMIT 8",
        )?.use { c -> while (c.moveToNext()) out += Unread(c.getString(0).orEmpty(), c.getString(1).orEmpty(), c.getLong(2)) }
        context.contentResolver.query(
            Telephony.Mms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Mms._ID, Telephony.Mms.DATE),
            "${Telephony.Mms.THREAD_ID} = ? AND ${Telephony.Mms.READ} = 0",
            arrayOf(threadId.toString()),
            "${Telephony.Mms.DATE} DESC LIMIT 8",
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                out += Unread(MmsText.sender(context, id).orEmpty(), MmsText.summary(context, id), c.getLong(1) * 1000)
            }
        }
        return out.sortedBy { it.date }.takeLast(8)
    }

    /**
     * Publica (o actualiza) la notificación de una conversación con sus mensajes sin leer. No hace
     * nada si la conversación está silenciada o no queda nada sin leer.
     */
    fun incoming(context: Context, threadId: Long, participants: List<String>) {
        if (!allowed(context)) return
        val numbers = numbers()
        val key = Conversations.key(participants, numbers)
        if (key in Stores.settings.value.muted) return
        val messages = unread(context, threadId)
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (messages.isEmpty()) {
            nm.cancel(threadId.toInt())
            return
        }
        // Posible spam: queda en su carpeta, sin avisar.
        if (dev.liquidmessages.sms.Filters.isSpam(context, key, threadId, participants.singleOrNull(), messages.map { it.text })) return
        val settings = Stores.settings.value
        val channel = if (key in settings.customSound) conversationChannel(context, key, null) else CHANNEL_MESSAGES
        // Con la app bloqueada o la vista previa en "nada": ni quién ni qué, y sin responder.
        if (settings.appLock || settings.notifPreview == 2 || key in settings.hidden) {
            nm.notify(
                threadId.toInt(),
                Notification.Builder(context, channel)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(context.getString(R.string.notif_new_public))
                    .setCategory(Notification.CATEGORY_MESSAGE)
                    .setContentIntent(openThread(context, threadId, participants.singleOrNull()))
                    .setAutoCancel(true)
                    .setNumber(messages.size)
                    .build(),
            )
            return
        }
        val me = Person.Builder().setName(context.getString(R.string.you)).setKey("me").build()
        val group = participants.size > 1
        val style = Notification.MessagingStyle(me).setGroupConversation(group)
        val people = HashMap<String, Person>()
        for (m in messages) {
            val p = people.getOrPut(m.address) { person(context, m.address, numbers) }
            // Vista previa "solo quién": se ve quién escribió, no qué.
            val text = if (settings.notifPreview == 1) context.getString(R.string.notif_new_public) else m.text
            style.addMessage(Notification.MessagingStyle.Message(text, m.date, p))
        }
        if (group) style.conversationTitle = participants.joinToString(", ") { a -> (people[a] ?: person(context, a, numbers)).name?.toString() ?: a }
        val single = participants.singleOrNull()
        val code = if (settings.notifPreview == 1) null else messages.lastOrNull()?.text?.let(Codes::find)
        val builder = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(style)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setContentIntent(openThread(context, threadId, single))
            .setAutoCancel(true)
            .setShowWhen(true)
            .setWhen(messages.last().date)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(
                Notification.Builder(context, CHANNEL_MESSAGES)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(context.getString(R.string.notif_new_public))
                    .build(),
            )
        if (single != null && SenderKind.of(single).canReply) {
            val input = RemoteInput.Builder(KEY_REPLY).setLabel(context.getString(R.string.notif_reply)).build()
            builder.addAction(
                Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_notification), context.getString(R.string.notif_reply), action(context, NotificationActionReceiver.ACTION_REPLY, threadId, single, mutable = true))
                    .addRemoteInput(input)
                    .setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY)
                    .build(),
            )
        }
        builder.addAction(
            Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_notification), context.getString(R.string.notif_mark_read), action(context, NotificationActionReceiver.ACTION_MARK_READ, threadId, single.orEmpty()))
                .setSemanticAction(Notification.Action.SEMANTIC_ACTION_MARK_AS_READ)
                .build(),
        )
        if (code != null) {
            builder.addAction(
                Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_notification), context.getString(R.string.notif_copy_code, code), action(context, NotificationActionReceiver.ACTION_COPY_CODE, threadId, single.orEmpty()) { putExtra(EXTRA_TEXT, code) })
                    .build(),
            )
        }
        nm.notify(threadId.toInt(), builder.build())
    }

    /**
     * Canal propio de una conversación, para darle otro sonido o vibración en los ajustes del
     * sistema. El id no lleva el número: es un resumen de la clave.
     */
    fun conversationChannel(context: Context, key: String, title: String?): String {
        val id = "conv_" + Integer.toHexString(key.hashCode())
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm != null && nm.getNotificationChannel(id) == null) {
            nm.createNotificationChannel(
                NotificationChannel(id, title ?: context.getString(R.string.channel_messages), NotificationManager.IMPORTANCE_HIGH).apply {
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                    group = null
                },
            )
        }
        return id
    }

    /** Abre los ajustes del sistema del canal propio de una conversación (y lo crea si hace falta). */
    fun openConversationSound(context: Context, key: String, title: String) {
        val id = conversationChannel(context, key, title)
        Stores.settings.update { it.copy(customSound = it.customSound + key) }
        context.startActivity(
            Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun cancel(context: Context, threadId: Long) {
        context.getSystemService(NotificationManager::class.java)?.cancel(threadId.toInt())
    }

    /** Aviso de un mensaje que no salió: lleva a la conversación, donde se ve y se puede reintentar. */
    fun sendFailed(context: Context, uri: Uri) {
        if (!allowed(context)) return
        val (thread, address) = context.contentResolver.query(uri, arrayOf(Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getLong(0) to c.getString(1).orEmpty() else null
        } ?: return
        notice(context, thread, address, context.getString(R.string.notif_failed_title), context.getString(R.string.notif_failed_text, person(context, address, numbers()).name))
    }

    /** Un aviso simple sobre una conversación (no enviado, elegir chip, MMS sin descargar). */
    fun notice(context: Context, threadId: Long, address: String?, title: String, text: String, textToKeep: String? = null) {
        if (!allowed(context)) return
        val n = Notification.Builder(context, CHANNEL_FAILED)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openThread(context, threadId, address, textToKeep))
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()
        context.getSystemService(NotificationManager::class.java)?.notify((threadId + 1_000_000).toInt(), n)
    }
}
