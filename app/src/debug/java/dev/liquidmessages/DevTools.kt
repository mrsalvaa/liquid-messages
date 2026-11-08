// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidmessages

import dev.liquidmessages.core.model.ContactSummary
import dev.liquidmessages.core.sms.Box
import dev.liquidmessages.core.sms.Message
import dev.liquidmessages.data.Inbox
import dev.liquidmessages.data.Repo
import dev.liquidmessages.data.Stores
import kotlin.random.Random

/**
 * Solo debug y profile. Conversaciones y contactos inventados **en memoria**: nunca se escriben en
 * el proveedor de SMS ni en la agenda (las apps de sincronización leen esos datos). Los números
 * son de los rangos reservados para ficción (ver FakeNumbers) y los nombres llevan "(prueba)".
 * Con datos de prueba cargados, la lista avisa en rojo que nada es real.
 */
object DevTools {
    private val first = listOf("Ana", "Bruno", "Carla", "Diego", "Elena", "Facundo", "Gabriela", "Hernán", "Inés", "Julián", "Lucía", "Martín", "Noelia", "Óscar", "Paula", "Ramiro", "Sofía", "Tomás", "Valeria", "Ximena")
    private val last = listOf("Acosta", "Benítez", "Castro", "Domínguez", "Espósito", "Fernández", "Giménez", "Herrera", "Ibáñez", "Juárez", "López", "Medina", "Núñez", "Ortiz", "Pereyra", "Quiroga", "Romero", "Suárez", "Torres", "Vega")
    private val senders = listOf("BANCO DEMO", "Correo Demo", "Farmacia", "21212", "31313", "Turnos")

    private val lines = listOf(
        "Hola, ¿cómo andás?", "Dale, nos vemos a las 18:30 en la esquina.", "Llego en 10 minutos.",
        "¿Querés que lleve algo para la cena?", "Mañana no puedo, ¿el jueves?", "Gracias!! 😊",
        "Te paso la dirección: https://example.com/mapa?id=42", "Mirá esto www.example.org/noticia",
        "Mi mail es prueba@example.com por si lo necesitás", "Si no atiendo, llamá al +1 202 555 0143",
        "Ok", "Perfecto 👍", "¿Viste el partido ayer?", "Ya salgo", "Sí, está bien",
        "Te escribo un mensaje largo para ver cómo se ven los que ocupan varias líneas en la burbuja, " +
            "con suficiente texto como para que tenga que partirse en más de una parte al enviarlo por SMS.",
    )
    private val service = listOf(
        "Tu código de verificación es %06d. No lo compartas con nadie.",
        "Código de acceso: %06d. Vence en 10 minutos.",
        "Tu pedido está en camino. Seguilo en https://envios.example.com/seguimiento",
        "Recordatorio: turno mañana 10:00. Para cancelar respondé NO.",
        "Ganaste un premio!!! Reclamalo en http://premio.example.net/reclamar",
    )

    /** [count] conversaciones con entre 1 y 40 mensajes; un tercio de los números son contactos. */
    fun generate(count: Int): String {
        val rnd = Random(7)
        val now = System.currentTimeMillis()
        val messages = ArrayList<Message>()
        val participants = HashMap<Long, List<String>>()
        val contacts = ArrayList<ContactSummary>()
        var id = 1L
        for (t in 0 until count) {
            val thread = 1000L + t
            val serviceThread = t % 9 == 4
            val address = if (serviceThread) senders[t % senders.size] else FakeNumbers.number(t)
            check(serviceThread || FakeNumbers.isReserved(address)) { "número fuera de los rangos reservados" }
            participants[thread] = listOf(address)
            if (!serviceThread && t % 3 == 0) {
                contacts += ContactSummary(
                    id = 5000L + t,
                    lookupKey = "prueba-$t",
                    displayName = "${first[t % first.size]} ${last[(t / first.size) % last.size]} (prueba)",
                    phones = listOf(address),
                )
            }
            // Las más nuevas, con más mensajes; las viejas se van años atrás.
            val n = if (t < 30) 10 + rnd.nextInt(30) else 1 + rnd.nextInt(6)
            var date = now - (t.toLong() * t * 60_000L) - rnd.nextLong(3_600_000L)
            val unread = t < 6 && t % 2 == 0
            for (k in 0 until n) {
                val incoming = serviceThread || rnd.nextInt(10) < 5
                val body = if (serviceThread) service[(t + k) % service.size].let { if (it.contains("%06d")) it.format(100_000 + rnd.nextInt(899_999)) else it }
                else lines[rnd.nextInt(lines.size)]
                val box = when {
                    incoming -> Box.INBOX
                    t == 1 && k == 0 -> Box.FAILED
                    else -> Box.SENT
                }
                messages += Message(
                    id = id++,
                    threadId = thread,
                    address = address,
                    body = body,
                    date = date,
                    box = box,
                    read = !(unread && incoming && k < 3),
                    subscriptionId = if (t % 4 == 1) 2 else 1,
                    mms = !serviceThread && t % 11 == 7 && k == 1,
                )
                date -= 60_000L + rnd.nextLong(20 * 3_600_000L)
            }
        }
        Repo.setFake(Inbox(messages, participants, loaded = true, fake = true), contacts)
        return "conversaciones=$count mensajes=${messages.size} contactos=${contacts.size}"
    }

    fun clear(): String {
        Repo.setFake(null, null)
        return "datos de prueba quitados"
    }

    fun style(background: Int, ui: Int, theme: Int): String {
        Stores.settings.update { it.copy(backgroundMode = background, uiStyle = ui, themeMode = theme) }
        return "estilo=$background/$ui/$theme"
    }
}
