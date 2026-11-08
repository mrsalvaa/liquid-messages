// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidmessages.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.liquidmessages.R
import dev.liquidmessages.core.sim.SendDecision
import dev.liquidmessages.core.sim.SendTarget
import dev.liquidmessages.core.sms.Box as MessageBox
import dev.liquidmessages.core.sms.Conversations
import dev.liquidmessages.core.sms.Message
import dev.liquidmessages.core.sms.SenderKind
import dev.liquidmessages.core.sms.SmsLength
import dev.liquidmessages.core.text.Codes
import dev.liquidmessages.data.Repo
import dev.liquidmessages.data.Stores
import dev.liquidmessages.ui.components.MessageText
import dev.liquidmessages.ui.components.dangerText
import dev.liquidmessages.ui.components.who
import dev.liquidmessages.ui.glass.GlassIconButton
import dev.liquidmessages.ui.glass.GlassTextField
import dev.liquidmessages.ui.glass.LocalActionSheet
import dev.liquidmessages.ui.glass.LocalDialog
import dev.liquidmessages.ui.glass.LocalGlass
import dev.liquidmessages.ui.glass.SheetAction
import dev.liquidmessages.ui.glass.SheetSpec
import dev.liquidmessages.ui.glass.accentFill
import dev.liquidmessages.util.Dates
import dev.liquidmessages.util.str
import java.time.LocalDate

/** Una línea de la conversación: un separador de día o un mensaje. */
private sealed interface Line {
    data class Day(val date: LocalDate) : Line
    data class Msg(val m: Message, val code: String?) : Line
}

/**
 * Una conversación. Se abre por su hilo, o por las direcciones si todavía no hay mensajes (un
 * mensaje nuevo): en cuanto aparece un hilo con esos participantes, se usa ese.
 */
@Composable
fun ThreadScreen(
    threadId: Long?,
    addresses: List<String>,
    initialText: String,
    padding: PaddingValues,
    onBack: () -> Unit,
) {
    val inbox by Repo.inbox.collectAsStateWithLifecycle()
    val book by Repo.book.collectAsStateWithLifecycle()
    val numbers by Repo.numbers.collectAsStateWithLifecycle()
    val settings by Stores.settings.state.collectAsStateWithLifecycle()
    val sims by Repo.sims.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val sheet = LocalActionSheet.current
    val dialog = LocalDialog.current
    val toast = LocalToast.current
    val p = LocalGlass.current

    // Si se abrió por direcciones, el hilo que tenga exactamente esos participantes.
    val key = remember(addresses, numbers) { Conversations.key(addresses, numbers) }
    val thread = threadId ?: remember(inbox, key) {
        inbox.participants.entries.firstOrNull { (_, a) -> Conversations.key(a, numbers) == key }?.key
            ?: inbox.messages.firstOrNull { Conversations.key(listOf(it.address), numbers) == key }?.threadId
    }
    val people = remember(inbox, thread, addresses) {
        thread?.let { inbox.participants[it]?.takeIf(List<String>::isNotEmpty) }
            ?: addresses.ifEmpty { thread?.let { t -> inbox.messages.firstOrNull { it.threadId == t }?.address?.let(::listOf) } ?: emptyList() }
    }
    val w = remember(people, book, numbers) { who(people, book, numbers) }
    val messages = remember(inbox, thread) { thread?.let { Conversations.thread(inbox.messages, it) }.orEmpty() }
    val lines = remember(messages) {
        buildList {
            var day: LocalDate? = null
            for (m in messages) {
                val d = Dates.day(m.date)
                if (d != day) {
                    add(Line.Day(d))
                    day = d
                }
                add(Line.Msg(m, if (m.box == MessageBox.INBOX && !m.mms) Codes.find(m.body) else null))
            }
        }.asReversed()
    }
    val single = people.singleOrNull()
    val canReply = single != null && SenderKind.of(single).canReply

    fun bubbleMenu(m: Message, code: String?) {
        if (m.mms) return
        sheet(
            SheetSpec(
                Dates.header(Dates.day(m.date)) + " · " + Dates.time(m.date),
                buildList {
                    add(SheetAction(str(R.string.copy), Icons.Rounded.ContentCopy) {
                        Actions.copy(context, m.body)
                        toast(str(R.string.copied))
                    })
                    if (code != null) {
                        add(SheetAction(str(R.string.copy_code, code), Icons.Rounded.Key) {
                            Actions.copy(context, code, sensitive = true)
                            toast(str(R.string.code_copied))
                        })
                    }
                },
            ),
        )
    }

    Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()).imePadding()) {
        BackTitle(
            w.title,
            onBack,
            Modifier.padding(horizontal = 16.dp),
            subtitle = w.subtitle,
        ) {
            if (single != null && SenderKind.of(single) != SenderKind.ALPHANUMERIC) {
                GlassIconButton(Icons.Rounded.Call, str(R.string.call), { Actions.dial(context, single) })
                val id = w.contactId
                if (id != null) {
                    GlassIconButton(Icons.Rounded.Person, str(R.string.open_contact), { Actions.openContact(context, id) })
                } else {
                    GlassIconButton(Icons.Rounded.PersonAdd, str(R.string.add_contact), { Actions.addContact(context, single) })
                }
            }
        }

        val state = rememberLazyListState()
        // Al llegar o enviar un mensaje, se baja al último si ya se estaba abajo.
        LaunchedEffect(messages.size) { if (state.firstVisibleItemIndex <= 1) state.animateScrollToItem(0) }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = state,
            reverseLayout = true,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            // Pegadas abajo, junto al campo de escribir, aunque sean pocas.
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Bottom),
        ) {
            items(lines, key = { l -> if (l is Line.Msg) "m${l.m.id}" else "d${(l as Line.Day).date}" }) { l ->
                when (l) {
                    is Line.Day -> Text(
                        Dates.header(l.date),
                        Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp).semantics { heading() },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = p.contentSecondary,
                        textAlign = TextAlign.Center,
                    )
                    is Line.Msg -> Bubble(
                        l.m,
                        chipLabel = if (sims.multiple) sims.bySubscription(l.m.subscriptionId)?.let { str(R.string.sim_label, it.slotNumber) } else null,
                        onLongClick = { bubbleMenu(l.m, l.code) },
                        onLink = { link -> Actions.link(context, link, dialog) },
                    )
                }
            }
        }

        if (canReply) {
            Composer(
                initialText = initialText,
                simplify = settings.simplifyAccents,
                padding = padding,
                onSend = { text ->
                    val body = if (settings.simplifyAccents) SmsLength.simplify(text) else text
                    // Etapa 1: la app todavía no es la de SMS predeterminada, así que no envía sola.
                    when (Repo.decide(SendTarget.UsePolicy, messages.lastOrNull()?.subscriptionId, canSend = false)) {
                        SendDecision.HandToSystemApp -> {
                            if (Actions.handToSystemApp(context, single!!, body)) toast(str(R.string.handed_to_system)) else toast(str(R.string.no_sms_app))
                            true
                        }
                        else -> false
                    }
                },
            )
        } else if (single != null || people.isEmpty()) {
            Text(
                str(R.string.cant_reply),
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = padding.calculateBottomPadding() + 8.dp),
                fontSize = 14.sp,
                color = p.contentSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Un mensaje: los recibidos a la izquierda sobre un fondo tenue, los enviados a la derecha en el
 * color de énfasis. Abajo, la hora, el chip (con dos chips) y si no se envió. Sin cristal propio:
 * con cientos de burbujas, cada superficie de cristal cuesta.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(m: Message, chipLabel: String?, onLongClick: () -> Unit, onLink: (dev.liquidmessages.core.text.Links.Link) -> Unit) {
    val p = LocalGlass.current
    val mine = m.box.outgoing
    val failed = m.box == MessageBox.FAILED
    val bg = if (mine) p.accentFill() else if (p.solid) p.levels.getOrElse(1) { p.content.copy(alpha = 0.08f) } else p.content.copy(alpha = if (p.dark) 0.12f else 0.07f)
    val fg = if (mine) p.onAccent else p.content
    val meta = listOfNotNull(
        Dates.time(m.date),
        chipLabel,
        when (m.box) {
            MessageBox.FAILED -> str(R.string.failed)
            MessageBox.OUTBOX, MessageBox.QUEUED -> str(R.string.sending)
            else -> null
        },
    ).joinToString(" · ")
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Box(
            Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(20.dp, 20.dp, if (mine) 6.dp else 20.dp, if (mine) 20.dp else 6.dp))
                .background(bg)
                .combinedClickable(remember { MutableInteractionSource() }, null, onLongClick = onLongClick, onClick = {})
                .padding(horizontal = 14.dp, vertical = 9.dp),
        ) {
            if (m.mms) {
                Text(str(R.string.mms_placeholder), color = fg.copy(alpha = 0.75f), fontSize = 15.sp)
            } else {
                MessageText(m.body, fg, onLink)
            }
        }
        Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            if (failed) Icon(dev.liquidmessages.ui.glass.styled(Icons.Rounded.ErrorOutline), null, Modifier.size(14.dp).padding(end = 2.dp), tint = p.dangerText)
            Text(meta, fontSize = 12.sp, color = if (failed) p.dangerText else p.contentSecondary)
        }
    }
}

/**
 * El campo de escribir: crece hasta 6 líneas, muestra el contador cuando importa (más de una
 * parte o caracteres que pasan a 70 por parte) y el botón de enviar. [onSend] devuelve true si el
 * mensaje salió (o se pasó a otra app) y entonces se borra el campo.
 */
@Composable
private fun Composer(initialText: String, simplify: Boolean, padding: PaddingValues, onSend: (String) -> Boolean) {
    val p = LocalGlass.current
    var text by rememberSaveable { mutableStateOf(initialText) }
    val info = remember(text, simplify) { SmsLength.of(if (simplify) SmsLength.simplify(text) else text) }
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = padding.calculateBottomPadding().coerceAtLeast(8.dp))) {
        if (text.isNotEmpty() && (info.parts > 1 || info.unicode)) {
            Text(
                str(R.string.parts_counter, info.remaining, info.parts) +
                    if (info.unicode) " · " + str(R.string.unicode_hint, info.nonGsm.take(3).joinToString(" ")) else "",
                Modifier.padding(start = 16.dp, bottom = 4.dp),
                fontSize = 12.sp,
                color = p.contentSecondary,
            )
        }
        Row(verticalAlignment = Alignment.Bottom) {
            GlassTextField(
                text,
                { text = it },
                str(R.string.message_hint),
                Modifier.weight(1f).heightIn(max = 180.dp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                singleLine = false,
            )
            Spacer(Modifier.size(8.dp))
            GlassIconButton(
                Icons.AutoMirrored.Rounded.Send,
                str(R.string.send),
                { if (text.isNotBlank() && onSend(text.trim())) text = "" },
                prominent = true,
                enabled = text.isNotBlank(),
            )
        }
    }
}
