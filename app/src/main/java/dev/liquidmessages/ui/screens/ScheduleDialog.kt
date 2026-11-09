// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidmessages.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.liquidmessages.R
import dev.liquidmessages.ui.glass.GlassButton
import dev.liquidmessages.ui.glass.LocalGlass
import dev.liquidmessages.ui.glass.WheelColon
import dev.liquidmessages.ui.glass.WheelPicker
import dev.liquidmessages.util.Dates
import dev.liquidmessages.util.str
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Cuándo sale un programado, en texto: "mañana 9:00", "lunes 3 de noviembre 18:30". */
fun scheduledLabel(at: Long): String = Dates.header(Dates.day(at)).replaceFirstChar { it.lowercase() } + " " + Dates.time(at)

/**
 * Elegir cuándo sale un mensaje: atajos y, si no, día (hoy y los 6 siguientes) y hora con ruedas,
 * todo de cristal. Una hora que ya pasó no se puede elegir. [onPick] recibe los milisegundos.
 */
@Composable
fun ScheduleContent(onPick: (Long) -> Unit, onCancel: () -> Unit) {
    val p = LocalGlass.current
    val zone = ZoneId.systemDefault()
    val now = LocalDateTime.now(zone)
    fun ms(t: LocalDateTime) = t.atZone(zone).toInstant().toEpochMilli()
    val quick = buildList {
        add(str(R.string.schedule_in_hour) to now.plusHours(1).withSecond(0).withNano(0))
        val tonight = now.toLocalDate().atTime(20, 0)
        if (tonight.isAfter(now.plusMinutes(10))) add(str(R.string.schedule_tonight) to tonight)
    }
    quick.forEach { (label, t) ->
        GlassButton("$label · ${scheduledLabel(ms(t))}", { onPick(ms(t)) }, Modifier.fillMaxWidth().padding(bottom = 8.dp))
    }
    // Este dice solo "Mañana 9 am": la hora ya va en el nombre.
    val tomorrow9 = now.toLocalDate().plusDays(1).atTime(9, 0)
    GlassButton(str(R.string.schedule_tomorrow_morning), { onPick(ms(tomorrow9)) }, Modifier.fillMaxWidth().padding(bottom = 8.dp))
    Text(str(R.string.schedule_other), Modifier.padding(top = 8.dp, bottom = 4.dp), fontSize = 15.sp, color = p.contentSecondary)
    var day by remember { mutableIntStateOf(if (now.hour >= 22) 1 else 0) }
    var hour by remember { mutableIntStateOf((now.hour + 1) % 24) }
    var minute by remember { mutableIntStateOf(0) }
    val days = (0..6).map { LocalDate.now(zone).plusDays(it.toLong()) }
    // El día en un desplegable: se abre en otra ventana, encima de esta.
    dev.liquidmessages.ui.components.Dropdown(str(R.string.schedule_day), (0..6).toList(), day, { i ->
        when (i) {
            0 -> str(R.string.today)
            1 -> str(R.string.tomorrow)
            else -> Dates.header(days[i]).substringBefore(' ') + " " + days[i].dayOfMonth
        }
    }) { day = it }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        WheelPicker(24, hour, { hour = it }, visibleItems = 3, fontSize = 30, width = 72.dp, itemHeight = 44.dp)
        WheelColon(fontSize = 30)
        WheelPicker(12, minute / 5, { minute = it * 5 }, visibleItems = 3, fontSize = 30, width = 72.dp, itemHeight = 44.dp, label = { "%02d".format(it * 5) })
    }
    val chosen = days[day].atTime(hour, minute)
    val valid = chosen.isAfter(now.plusMinutes(1))
    Spacer(Modifier.height(12.dp))
    GlassButton(
        if (valid) str(R.string.schedule_for, scheduledLabel(ms(chosen))) else str(R.string.schedule_past),
        { if (valid) onPick(ms(chosen)) },
        Modifier.fillMaxWidth(),
        prominent = true,
        enabled = valid,
    )
    Spacer(Modifier.height(8.dp))
    GlassButton(str(R.string.cancel), onCancel, Modifier.fillMaxWidth())
}
