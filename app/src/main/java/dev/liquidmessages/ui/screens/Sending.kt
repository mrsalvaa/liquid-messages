// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidmessages.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.liquidmessages.R
import dev.liquidmessages.core.sim.AskReason
import dev.liquidmessages.core.sim.SendDecision
import dev.liquidmessages.core.sim.SendTarget
import dev.liquidmessages.core.sim.SimChip
import dev.liquidmessages.data.Repo
import dev.liquidmessages.mms.MmsTransport
import dev.liquidmessages.sms.SmsSender
import dev.liquidmessages.ui.glass.DialogSpec
import dev.liquidmessages.ui.glass.GlassButton
import dev.liquidmessages.ui.glass.LocalGlass
import dev.liquidmessages.util.str

/** Cómo se nombra un chip: "SIM 2 · Claro", o "SIM 2" si no tiene nombre. */
fun chipName(chip: SimChip): String =
    if (chip.displayName.isNotBlank()) str(R.string.sim_label_named, chip.slotNumber, chip.displayName) else str(R.string.sim_label, chip.slotNumber)

/**
 * Enviar desde la app: decide el chip con SmsDecider y, si hay que elegir, pregunta con el motivo a
 * la vista (nunca cambia de chip sin que se vea). Va como MMS si hay fotos o es un grupo; si no,
 * como SMS. Sin ser la app predeterminada, pasa el texto a la app de SMS actual.
 *
 * [onDone] se llama con true si el mensaje salió (o quedó en camino) o se pasó a otra app, para
 * vaciar el campo; con false si se canceló o no se pudo.
 */
fun sendMessage(
    context: Context,
    people: List<String>,
    text: String,
    images: List<MmsTransport.Image>,
    target: SendTarget,
    conversationSubscription: Int?,
    dialog: (DialogSpec) -> Unit,
    toast: (String) -> Unit,
    /** Con esto, los mensajes de texto esperan [UNDO_MS] y el aviso ofrece deshacer (devuelve el texto). */
    undo: Pair<(String, () -> Unit) -> Unit, (String) -> Unit>? = null,
    onDone: (Boolean) -> Unit,
) {
    if (people.isEmpty() || (text.isBlank() && images.isEmpty())) return
    fun go(chip: SimChip?) {
        val work: suspend () -> Unit = {
            if (images.isNotEmpty() || people.size > 1) MmsTransport.send(context, people, text, images, chip)
            else SmsSender.send(context, people[0], text, chip)
        }
        // Las fotos no se demoran: si se deshace, el texto vuelve al campo pero la foto no.
        if (undo != null && images.isEmpty() && dev.liquidmessages.data.Stores.settings.value.undoSend) {
            val job = Repo.later(UNDO_MS, work)
            onDone(true)
            if (job != null) {
                undo.first(str(R.string.sending_soon)) {
                    job.cancel()
                    undo.second(text)
                }
            }
        } else {
            Repo.io(work)
            onDone(true)
        }
    }
    when (val d = Repo.decide(target, conversationSubscription, canSend = Repo.isDefault.value, hasText = true)) {
        is SendDecision.Send -> go(d.chip)
        SendDecision.SystemChooses -> go(null)
        SendDecision.HandToSystemApp -> {
            val single = people.singleOrNull()
            if (single != null && images.isEmpty() && Actions.handToSystemApp(context, single, text)) {
                toast(str(R.string.handed_to_system))
                onDone(true)
            } else {
                toast(str(R.string.needs_default))
                onDone(false)
            }
        }
        SendDecision.Nothing -> onDone(false)
        is SendDecision.Ask -> dialog(
            DialogSpec(str(R.string.choose_sim)) { dismiss ->
                val p = LocalGlass.current
                val reason = when (d.reason) {
                    AskReason.ALWAYS -> str(R.string.ask_always)
                    AskReason.PRIMARY_UNAVAILABLE -> d.other?.let { str(R.string.ask_primary_gone_other, chipName(it)) } ?: str(R.string.ask_primary_gone)
                    AskReason.NO_SERVICE -> str(R.string.ask_no_service, d.suggested?.let(::chipName).orEmpty())
                    AskReason.CHIP_GONE -> str(R.string.ask_chip_gone)
                    AskReason.AMBIGUOUS_SLOT -> str(R.string.ask_ambiguous)
                }
                Text(reason, color = p.content, fontSize = 15.sp)
                Spacer(Modifier.height(14.dp))
                d.options.forEach { chip ->
                    GlassButton(chipName(chip), { dismiss(); go(chip) }, Modifier.fillMaxWidth(), prominent = chip == (d.other ?: d.suggested))
                    Spacer(Modifier.height(8.dp))
                }
                GlassButton(str(R.string.cancel), { dismiss(); onDone(false) }, Modifier.fillMaxWidth())
            },
        )
    }
}
