// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidmessages.core.text

import dev.liquidmessages.core.sms.SenderKind

/**
 * Filtro de spam local: decide con el texto y el remitente, en el celular, sin listas de internet.
 * Es conservador a propósito: solo marca mensajes de un número común (no de un remitente con
 * nombre ni de un número corto, que suelen ser bancos y servicios) que traen un link y además
 * algo típico de las estafas (premios, cuentas bloqueadas, paquetes retenidos, links acortados
 * o a una dirección IP). Lo que diga quién es conocido (un contacto, o alguien a quien se le
 * escribió) lo resuelve quien llama: acá solo se mira un mensaje.
 */
object Spam {
    private val WORDS = listOf(
        // Castellano
        "premio", "ganaste", "ganador", "felicitaciones", "felicidades", "reclam", "sorteo", "regalo",
        "bloquead", "suspendid", "desactivad", "verific", "actualiz", "confirm", "validá", "valida tu",
        "paquete", "envío retenido", "envio retenido", "entrega pendiente", "aduana", "deuda", "multa",
        "urgente", "último aviso", "ultimo aviso", "inmediat", "clic", "click", "ingresá", "ingresa aquí",
        "préstamo", "prestamo", "crédito aprobado", "credito aprobado", "inversión", "inversion", "cripto",
        // Inglés
        "prize", "winner", "won ", "congratulations", "claim", "gift card", "suspended", "locked",
        "verify", "update your", "confirm your", "package", "delivery", "parcel", "customs", "unpaid",
        "overdue", "urgent", "final notice", "loan", "approved", "investment", "crypto",
    )

    private val SHORTENERS = listOf(
        "bit.ly", "tinyurl.com", "cutt.ly", "t.ly", "is.gd", "rb.gy", "shorturl.at", "ow.ly", "goo.su",
        "s.id", "rebrand.ly", "t.co", "tiny.cc", "v.gd", "shorturl.gg", "linktr.ee",
    )

    /** Si este mensaje, de este remitente, parece spam. */
    fun looksLikeSpam(address: String, body: String): Boolean {
        if (SenderKind.of(address) != SenderKind.PHONE) return false
        val text = body.lowercase()
        val hosts = Links.find(body).filter { it.kind == Links.Kind.WEB }.mapNotNull { it.host?.removePrefix("www.") }
        // Un acortador sin "https://" ("bit.ly/abc") no lo ve Links: se busca aparte.
        val bareShort = SHORTENERS.any { s -> Regex("""(?<![\w.])${Regex.escape(s)}/\S""").containsMatchIn(text) }
        if (hosts.isEmpty() && !bareShort) return false
        val shortened = bareShort || hosts.any { it in SHORTENERS }
        val toIp = hosts.any { it.matches(Regex("""\d{1,3}(\.\d{1,3}){3}""")) }
        val words = WORDS.count { text.contains(it) }
        return shortened || toIp || words >= 1
    }
}
