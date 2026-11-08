// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidmessages.core.sms

/**
 * Reacciones que llegan como SMS. Cuando alguien reacciona desde un iPhone o desde Google
 * Messages a un mensaje de SMS, al otro lado llega un texto como `Le gustó “Nos vemos”` o
 * `Reacted 😂 to "Nos vemos"`. Acá se reconoce ese texto para mostrarlo como un emoji sobre el
 * mensaje original en vez de como un mensaje aparte. Si no se encuentra el original, el texto se
 * muestra tal cual: nunca se esconde algo que no se pudo ubicar.
 */
object Reactions {
    /** Una reacción leída de un texto. [emoji] null en las que quitan una reacción sin decir cuál. */
    data class Parsed(val emoji: String?, val quoted: String, val removed: Boolean)

    // Las comillas que usan los distintos celulares; el texto citado va entre ellas.
    private const val Q = "[\"“”«»„‘’']"
    private const val QUOTE = "$Q(.*)$Q"

    // Frases fijas de iPhone (en inglés y en castellano), con su emoji.
    private val PHRASES: List<Pair<String, String>> = listOf(
        "Liked" to "👍", "Loved" to "❤️", "Disliked" to "👎", "Laughed at" to "😂", "Emphasized" to "‼️", "Questioned" to "❓",
        "Le gustó" to "👍", "Le encantó" to "❤️", "No le gustó" to "👎", "Le hizo gracia" to "😂", "Se rió de" to "😂",
        "Le pareció gracioso" to "😂", "Enfatizó" to "‼️", "Destacó" to "‼️", "Cuestionó" to "❓", "Preguntó por" to "❓",
    )
    private val phrase = Regex("^(${PHRASES.joinToString("|") { Regex.escape(it.first) }})\\s+$QUOTE$", RegexOption.DOT_MATCHES_ALL)

    // Con cualquier emoji: "Reacted 🙏 to “…”", "Reaccionó con 🙏 a “…”".
    private val custom = Regex("^(?:Reacted|Reaccionó(?: con)?|Reaccionaste(?: con)?)\\s+(\\S{1,16})\\s+(?:to|a)\\s+$QUOTE$", RegexOption.DOT_MATCHES_ALL)

    // Quitar una reacción: "Removed 😂 from “…”", "Removed a like from “…”", "Quitó un Me gusta de “…”".
    private val removedEmoji = Regex("^(?:Removed|Quitó)\\s+(\\S{1,16})\\s+(?:from|de)\\s+$QUOTE$", RegexOption.DOT_MATCHES_ALL)
    private val removedAny = Regex("^(?:Removed|Quitó)\\s+.{1,40}?\\s+(?:from|de)\\s+$QUOTE$", RegexOption.DOT_MATCHES_ALL)

    fun parse(body: String): Parsed? {
        val t = body.trim()
        if (t.length < 5) return null
        phrase.matchEntire(t)?.let { m ->
            val emoji = PHRASES.first { it.first == m.groupValues[1] }.second
            return Parsed(emoji, m.groupValues[2], removed = false)
        }
        custom.matchEntire(t)?.let { m -> if (looksLikeEmoji(m.groupValues[1])) return Parsed(m.groupValues[1], m.groupValues[2], removed = false) }
        removedEmoji.matchEntire(t)?.let { m -> if (looksLikeEmoji(m.groupValues[1])) return Parsed(m.groupValues[1], m.groupValues[2], removed = true) }
        removedAny.matchEntire(t)?.let { m -> return Parsed(null, m.groupValues[1], removed = true) }
        return null
    }

    /** Sin letras ni números: un emoji (o varios juntos), no una palabra. */
    private fun looksLikeEmoji(s: String): Boolean = s.isNotEmpty() && s.none { it.isLetterOrDigit() } && s.any { it.code > 0x2000 }

    /** Si [body] es el mensaje citado: igual, o el comienzo cuando la cita viene cortada con "…". */
    fun quotes(quoted: String, body: String): Boolean {
        val q = quoted.trim()
        val b = body.trim()
        if (q.isEmpty() || b.isEmpty()) return false
        if (q == b) return true
        val cut = q.removeSuffix("…").removeSuffix("...").trimEnd()
        return cut.length >= 8 && cut.length < q.length && b.startsWith(cut)
    }

    /** Lo que se muestra: los mensajes sin las reacciones ubicadas, y las reacciones de cada mensaje. */
    data class Applied(val visible: List<Message>, val reactions: Map<Long, List<String>>)

    /**
     * Ubica las reacciones de una conversación ([messages] en orden de lectura, del más viejo al
     * más nuevo). Cada una va al mensaje anterior más cercano que cita; las de quitar sacan la
     * misma reacción (o la última, si no dicen cuál) de esa persona.
     */
    fun apply(messages: List<Message>): Applied {
        val hidden = HashSet<Long>()
        // Por mensaje: (quién, emoji) en el orden en que llegaron.
        val byTarget = LinkedHashMap<Long, MutableList<Pair<String, String>>>()
        for ((i, m) in messages.withIndex()) {
            if (m.mms || m.body.isBlank()) continue
            val r = parse(m.body) ?: continue
            val target = (i - 1 downTo 0).asSequence().map { messages[it] }
                .firstOrNull { it.id !in hidden && it.body.isNotBlank() && quotes(r.quoted, it.body) && parse(it.body) == null }
                ?: continue
            val who = if (m.box.outgoing) "" else m.address
            val list = byTarget.getOrPut(target.id) { ArrayList() }
            if (r.removed) {
                val k = list.indexOfLast { it.first == who && (r.emoji == null || it.second == r.emoji) }
                if (k >= 0) list.removeAt(k)
            } else {
                // Una persona tiene una reacción por mensaje: la nueva reemplaza a la anterior.
                list.removeAll { it.first == who }
                list += who to r.emoji!!
            }
            hidden += m.id
        }
        return Applied(
            messages.filter { it.id !in hidden },
            byTarget.filterValues { it.isNotEmpty() }.mapValues { (_, l) -> l.map { it.second } },
        )
    }
}
