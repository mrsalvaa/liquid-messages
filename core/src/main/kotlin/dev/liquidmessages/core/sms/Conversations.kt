// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidmessages.core.sms

import dev.liquidmessages.core.phone.PhoneNumbers

/** Tipo de un mensaje, con los mismos valores que la columna `type` de Telephony.Sms. */
enum class Box(val value: Int) {
    INBOX(1), SENT(2), DRAFT(3), OUTBOX(4), FAILED(5), QUEUED(6);

    val outgoing: Boolean get() = this != INBOX

    companion object {
        /** Lo desconocido se trata como recibido: nunca se muestra como enviado algo que no lo fue. */
        fun of(value: Int): Box = entries.firstOrNull { it.value == value } ?: INBOX
    }
}

/** Un mensaje, solo con lo que muestra la app. */
data class Message(
    val id: Long,
    val threadId: Long,
    /** Número o remitente tal como lo guardó el sistema ("+12025550143", "BANCO", "21212"). */
    val address: String,
    val body: String,
    /** Milisegundos desde 1970. */
    val date: Long,
    val box: Box,
    val read: Boolean = true,
    /** Suscripción (chip) por la que llegó o salió; null si el sistema no lo dice. */
    val subscriptionId: Int? = null,
    /** MMS: hasta la etapa de MMS se muestra como "MMS, no soportado todavía". */
    val mms: Boolean = false,
)

/** Qué es un remitente, para saber si se le puede contestar. */
enum class SenderKind {
    /** Un número de teléfono común. */
    PHONE,

    /** Un número corto de servicio ("21212"): se le puede contestar, a veces con costo. */
    SHORT_CODE,

    /** Un nombre ("BANCO", "Movistar"): el sistema no puede enviarle nada. */
    ALPHANUMERIC;

    val canReply: Boolean get() = this != ALPHANUMERIC

    companion object {
        fun of(address: String): SenderKind {
            val a = address.trim()
            if (a.any { it.isLetter() }) return ALPHANUMERIC
            val digits = a.count(Char::isDigit)
            return when {
                digits == 0 -> ALPHANUMERIC
                digits <= 6 && !a.startsWith("+") -> SHORT_CODE
                else -> PHONE
            }
        }
    }
}

/** Lo que guarda la app de cada conversación (en sus ajustes), por [Conversations.key]. */
data class ConversationPrefs(
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val muted: Boolean = false,
)

data class Conversation(
    val threadId: Long,
    /** Los participantes, como los guardó el sistema. Más de uno solo en grupos (MMS). */
    val addresses: List<String>,
    val key: String,
    val last: Message,
    val unread: Int,
    val prefs: ConversationPrefs,
) {
    val sender: SenderKind get() = addresses.map(SenderKind::of).firstOrNull() ?: SenderKind.ALPHANUMERIC
    val canReply: Boolean get() = addresses.size == 1 && sender.canReply
}

object Conversations {
    /**
     * Clave estable de una conversación, para guardar fijada/archivada/silenciada. No se usa el
     * thread_id: el sistema lo cambia si la conversación se borra y vuelve a empezar. Es la forma
     * exacta del número (o la cola de 8 dígitos si le falta el área), o el nombre del remitente en
     * minúsculas; varios participantes, ordenados.
     */
    fun key(addresses: Collection<String>, numbers: PhoneNumbers): String =
        addresses.map { a ->
            if (SenderKind.of(a) == SenderKind.PHONE) {
                val k = numbers.key(a)
                k.exact ?: k.tail ?: k.digits
            } else {
                a.trim().lowercase()
            }
        }.distinct().sorted().joinToString(",")

    /**
     * Arma las conversaciones a partir de sus mensajes (de cualquier orden). [participants] da los
     * participantes de cada hilo; si no está, se usa el remitente del último mensaje. Las
     * archivadas quedan afuera salvo con [archived] = true, que devuelve solo esas. Orden: fijadas
     * primero y después por fecha, de la más nueva a la más vieja.
     */
    fun build(
        messages: List<Message>,
        numbers: PhoneNumbers,
        prefs: (String) -> ConversationPrefs = { ConversationPrefs() },
        participants: (Long) -> List<String>? = { null },
        archived: Boolean = false,
    ): List<Conversation> =
        messages.filter { it.box != Box.DRAFT }
            .groupBy { it.threadId }
            .map { (thread, list) ->
                val last = list.maxWith(compareBy<Message> { it.date }.thenBy { it.id })
                val addresses = participants(thread)?.takeIf { it.isNotEmpty() } ?: listOf(last.address)
                val key = key(addresses, numbers)
                Conversation(
                    threadId = thread,
                    addresses = addresses,
                    key = key,
                    last = last,
                    unread = list.count { !it.read && it.box == Box.INBOX },
                    prefs = prefs(key),
                )
            }
            .filter { it.prefs.archived == archived }
            .sortedWith(compareByDescending<Conversation> { it.prefs.pinned }.thenByDescending { it.last.date }.thenByDescending { it.threadId })

    /** Los mensajes de una conversación en orden de lectura (del más viejo al más nuevo). */
    fun thread(messages: List<Message>, threadId: Long): List<Message> =
        messages.filter { it.threadId == threadId && it.box != Box.DRAFT }
            .sortedWith(compareBy<Message> { it.date }.thenBy { it.id })
}
