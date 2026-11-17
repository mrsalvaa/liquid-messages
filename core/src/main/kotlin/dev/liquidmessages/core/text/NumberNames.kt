// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidmessages.core.text

/**
 * Los teléfonos dentro de un mensaje recibido que son de un contacto se muestran con su nombre: el
 * aviso de la operadora "Llamada perdida de +54 9 11 5555-0143" se lee "Llamada perdida de Lucas
 * Rodríguez". Solo cambia lo que se muestra: el mensaje guardado queda como llegó.
 */
object NumberNames {
    /** Un nombre puesto en el texto: dónde quedó ([range], en el texto nuevo) y qué número reemplazó. */
    data class Named(val range: IntRange, val number: String)

    data class Result(val text: String, val names: List<Named>)

    /**
     * [nameOf] devuelve el nombre del contacto de un número, o null si no hay uno solo. Lo que no
     * coincide con nadie queda igual.
     */
    fun replace(text: String, nameOf: (String) -> String?): Result {
        val phones = Links.find(text).filter { it.kind == Links.Kind.PHONE }
        if (phones.isEmpty()) return Result(text, emptyList())
        val out = StringBuilder()
        val names = ArrayList<Named>()
        var at = 0
        for (link in phones) {
            val (number, name) = named(link.text, nameOf) ?: continue
            out.append(text, at, link.range.first)
            names += Named(out.length until out.length + name.length, number)
            out.append(name)
            at = link.range.first + number.length
        }
        if (names.isEmpty()) return Result(text, emptyList())
        out.append(text, at, text.length)
        return Result(out.toString(), names)
    }

    /**
     * El número y su nombre. Si lo marcado entero no es de nadie, prueba sin los últimos grupos: en
     * "del 11 5555-0143 12/11" lo marcado se lleva el "12" de la fecha.
     */
    private fun named(marked: String, nameOf: (String) -> String?): Pair<String, String>? {
        var candidate = marked
        while (candidate.count(Char::isDigit) >= 7) {
            nameOf(candidate)?.takeIf { it.isNotBlank() }?.let { return candidate to it }
            val cut = candidate.lastIndexOf(' ')
            if (cut < 0) break
            candidate = candidate.substring(0, cut).trimEnd()
        }
        return null
    }
}
