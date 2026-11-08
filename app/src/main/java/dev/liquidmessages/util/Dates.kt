// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidmessages.util

import dev.liquidmessages.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/** Fechas de la lista y de las conversaciones, en el idioma y la zona del celular. */
object Dates {
    private fun zone(): ZoneId = ZoneId.systemDefault()
    private fun locale(): Locale = Locale.getDefault()

    // Los formateadores se arman una vez por idioma: crearlos en cada fila y cada cuadro cuesta.
    private var cachedFor: Locale? = null
    private lateinit var timeFormat: DateTimeFormatter
    private lateinit var dayMonth: DateTimeFormatter
    private lateinit var shortDate: DateTimeFormatter
    private lateinit var headerFormat: DateTimeFormatter
    private lateinit var longDate: DateTimeFormatter

    @Synchronized
    private fun formats() {
        val l = locale()
        if (cachedFor == l) return
        timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(l)
        dayMonth = DateTimeFormatter.ofPattern("d MMM", l)
        shortDate = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(l)
        headerFormat = DateTimeFormatter.ofPattern(if (l.language == "es") "EEEE d 'de' MMMM" else "EEEE, MMMM d", l)
        longDate = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(l)
        cachedFor = l
    }

    fun day(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(zone()).toLocalDate()

    /** Hora del mensaje ("18:42"), con el formato de 12 o 24 horas del idioma. */
    fun time(millis: Long): String {
        formats()
        return timeFormat.format(Instant.ofEpochMilli(millis).atZone(zone()))
    }

    /** Para la lista: hoy la hora, ayer "Ayer", esta semana el día ("lun"), este año "3 nov", si no "3/11/24". */
    fun short(millis: Long, today: LocalDate = LocalDate.now(zone())): String {
        formats()
        val d = day(millis)
        return when {
            d == today -> time(millis)
            d == today.minusDays(1) -> str(R.string.yesterday)
            d.isAfter(today.minusDays(7)) -> d.dayOfWeek.getDisplayName(TextStyle.SHORT, locale()).trimEnd('.')
            d.year == today.year -> dayMonth.format(d).trimEnd('.')
            else -> shortDate.format(d)
        }
    }

    /** Separador de día dentro de una conversación: "Hoy", "Ayer", "lunes 3 de noviembre", con el año si no es este. */
    fun header(d: LocalDate, today: LocalDate = LocalDate.now(zone())): String {
        formats()
        return when {
            d == today -> str(R.string.today)
            d == today.minusDays(1) -> str(R.string.yesterday)
            d.year == today.year -> headerFormat.format(d).let(::cap)
            else -> longDate.format(d).let(::cap)
        }
    }

    private fun cap(s: String) = s.replaceFirstChar { it.titlecase(locale()) }
}
