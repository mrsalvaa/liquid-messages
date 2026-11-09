// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidmessages.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Block
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.foundation.border
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Quickreply
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import dev.liquidmessages.core.sms.Attachment
import dev.liquidmessages.core.sms.Box as MessageBox
import dev.liquidmessages.core.sms.Conversations
import dev.liquidmessages.core.sms.Message
import dev.liquidmessages.core.sms.SenderKind
import dev.liquidmessages.core.sms.SmsLength
import dev.liquidmessages.core.text.Codes
import dev.liquidmessages.data.Repo
import dev.liquidmessages.data.Stores
import dev.liquidmessages.mms.MmsTransport
import dev.liquidmessages.notify.Notifier
import dev.liquidmessages.ui.components.DangerButton
import dev.liquidmessages.ui.components.MessageText
import dev.liquidmessages.ui.components.dangerText
import dev.liquidmessages.ui.components.who
import dev.liquidmessages.ui.glass.DialogSpec
import dev.liquidmessages.ui.glass.GlassButton
import dev.liquidmessages.ui.glass.GlassIconButton
import dev.liquidmessages.ui.glass.GlassTextField
import dev.liquidmessages.ui.glass.LocalActionSheet
import dev.liquidmessages.ui.glass.LocalDialog
import dev.liquidmessages.ui.glass.LocalGlass
import dev.liquidmessages.ui.glass.SheetAction
import dev.liquidmessages.ui.glass.SheetSpec
import dev.liquidmessages.ui.glass.accentFill
import dev.liquidmessages.ui.glass.styled
import dev.liquidmessages.util.Dates
import dev.liquidmessages.util.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Una línea de la conversación: un separador de día o un mensaje. */
private sealed interface Line {
    data class Day(val date: LocalDate) : Line
    data class Msg(val m: Message, val code: String?) : Line
}

/** Guarda o borra el borrador de una conversación (vacío = sin borrador). */
private fun saveDraft(key: String, text: String) {
    val t = text.trim()
    if (key.isEmpty() || Stores.drafts.value[key].orEmpty() == t) return
    Stores.drafts.update { if (t.isEmpty()) it - key else it + (key to t) }
}

/** Cómo se ven las burbujas, según Ajustes. */
private class BubbleStyle(val contactColor: androidx.compose.ui.graphics.Color?, val fontSize: androidx.compose.ui.unit.TextUnit, val timeAlways: Boolean)

/** Letra de los mensajes según el ajuste (0 = chica, 1 = normal, 2 = grande). */
fun textSizeOf(setting: Int) = when (setting) {
    0 -> 14.sp
    2 -> 19.sp
    else -> 16.sp
}

/** Un mensaje de solo emojis (hasta tres, sin letras ni números). */
private fun isOnlyEmoji(text: String): Boolean {
    val t = text.trim()
    if (t.isEmpty() || t.length > 24) return false
    var count = 0
    var i = 0
    while (i < t.length) {
        val cp = t.codePointAt(i)
        val type = Character.getType(cp)
        when {
            cp == 0x200D || cp == 0xFE0F || cp in 0x1F3FB..0x1F3FF || Character.isWhitespace(cp) -> Unit
            type == Character.OTHER_SYMBOL.toInt() || type == Character.SURROGATE.toInt() -> count++
            else -> return false
        }
        i += Character.charCount(cp)
    }
    return count in 1..3
}

/** Una foto elegida para enviar: lo que se manda y la miniatura que se muestra. */
private class Picked(val image: MmsTransport.Image, val preview: Bitmap)

/**
 * Una conversación. Se abre por su hilo, o por las direcciones si todavía no hay mensajes (un
 * mensaje nuevo): en cuanto aparece un hilo con esos participantes, se usa ese.
 */
@Composable
fun ThreadScreen(
    threadId: Long?,
    addresses: List<String>,
    initialText: String,
    initialImage: Uri?,
    padding: PaddingValues,
    onBack: () -> Unit,
    /** Un mensaje para mostrar y resaltar al abrir (desde Destacados). */
    focus: Long? = null,
) {
    val inbox by Repo.inbox.collectAsStateWithLifecycle()
    val book by Repo.book.collectAsStateWithLifecycle()
    val numbers by Repo.numbers.collectAsStateWithLifecycle()
    val settings by Stores.settings.state.collectAsStateWithLifecycle()
    val sims by Repo.sims.state.collectAsStateWithLifecycle()
    val isDefault by Repo.isDefault.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val sheet = LocalActionSheet.current
    val dialog = LocalDialog.current
    val toast = LocalToast.current
    val undoToast = LocalUndoToast.current
    val p = LocalGlass.current
    val scope = rememberCoroutineScope()
    // Texto de un programado (o de un envío deshecho) cancelado: vuelve al campo de escribir.
    var restore by remember { mutableStateOf<String?>(null) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    var hit by rememberSaveable { mutableStateOf(0) }
    // El mensaje resaltado (el de la búsqueda o el que se abrió desde Destacados).
    var focused by remember { mutableStateOf(focus) }

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
    // Una privada (abierta desde una notificación, por ejemplo) pide desbloquear antes de mostrarse.
    val peopleKey = remember(people, numbers) { Conversations.key(people, numbers) }
    if (peopleKey in settings.hidden) {
        val open by dev.liquidmessages.security.AppLock.privateOpen.collectAsStateWithLifecycle()
        if (!open) {
            PrivateGate(Modifier.padding(padding)) {}
            return
        }
    }
    val group = people.size > 1
    val w = remember(people, book, numbers) { who(people, book, numbers) }
    val messages = remember(inbox, thread) { thread?.let { Conversations.thread(inbox.messages, it) }.orEmpty() }
    // Las reacciones que llegan como SMS ("Le gustó “…”") van sobre su mensaje, no aparte.
    val applied = remember(messages) { dev.liquidmessages.core.sms.Reactions.apply(messages) }
    val lines = remember(applied) {
        buildList {
            var day: LocalDate? = null
            for (m in applied.visible) {
                val d = Dates.day(m.date)
                if (d != day) {
                    add(Line.Day(d))
                    day = d
                }
                add(Line.Msg(m, if (m.box == MessageBox.INBOX && m.body.isNotBlank()) Codes.find(m.body) else null))
            }
        }.asReversed()
    }
    // Nombre y color de cada remitente, calculados una vez (no en cada burbuja y cada cuadro).
    val senders = remember(messages, book, numbers) {
        messages.asSequence().filter { !it.box.outgoing }.map { it.address }.distinct().associateWith { who(listOf(it.ifBlank { people.firstOrNull().orEmpty() }), book, numbers).title }
    }
    val dark = p.dark
    val senderColors = remember(senders, dark) { senders.mapValues { (_, name) -> dev.liquidmessages.ui.components.avatarColor(name, dark) } }
    val bubbleFont = textSizeOf(settings.textSize)
    val single = people.singleOrNull()
    val canReply = (single != null && SenderKind.of(single).canReply) || (group && people.all { SenderKind.of(it) != SenderKind.ALPHANUMERIC })
    val lastSub = messages.lastOrNull { it.subscriptionId != null }?.subscriptionId

    // Abrir la conversación es leerla: se marca leída (solo la app predeterminada puede) y se quita su notificación.
    val unread = messages.count { !it.read }
    LaunchedEffect(thread, unread, isDefault) {
        val t = thread ?: return@LaunchedEffect
        Notifier.cancel(context, t)
        if (isDefault && unread > 0) Repo.io { Repo.store.markRead(t) }
    }

    fun confirmDelete(title: String, text: String, onYes: () -> Unit) {
        dialog(
            DialogSpec(title) { dismiss ->
                Text(text, color = LocalGlass.current.contentSecondary, fontSize = 15.sp)
                Spacer(Modifier.height(16.dp))
                DangerButton(str(R.string.delete), { dismiss(); onYes() }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                GlassButton(str(R.string.cancel), dismiss, Modifier.fillMaxWidth())
            },
        )
    }

    /** Detalles de un mensaje: tipo, de o para quién, horas, chip y estado. */
    fun details(m: Message) {
        val mine = m.box.outgoing
        val rows = buildList {
            add(str(R.string.detail_type) to (if (m.mms) "MMS" else "SMS"))
            if (mine) add(str(R.string.detail_to) to people.joinToString(", "))
            else add(str(R.string.detail_from) to (m.address.ifBlank { people.firstOrNull().orEmpty() }))
            val full = Dates.header(Dates.day(m.date)) + " · " + Dates.time(m.date)
            add(str(if (mine) R.string.detail_sent else R.string.detail_received) to full)
            val sent = m.sentAt
            if (!mine && sent != null && kotlin.math.abs(sent - m.date) >= 60_000) {
                add(str(R.string.detail_sent_by_sender) to (Dates.header(Dates.day(sent)) + " · " + Dates.time(sent)))
            }
            sims.bySubscription(m.subscriptionId)?.let { c ->
                add(str(R.string.detail_sim) to if (c.displayName.isNotBlank()) str(R.string.sim_label_named, c.slotNumber, c.displayName) else str(R.string.sim_label, c.slotNumber))
            }
            if (mine) {
                add(
                    str(R.string.detail_status) to str(
                        when {
                            m.box == MessageBox.FAILED -> R.string.failed
                            m.box == MessageBox.OUTBOX || m.box == MessageBox.QUEUED -> R.string.sending
                            m.delivered -> R.string.delivered
                            else -> R.string.detail_sent_ok
                        },
                    ),
                )
            }
            m.subject?.let { add(str(R.string.detail_subject) to it) }
            if (m.attachments.isNotEmpty()) add(str(R.string.detail_attachments) to m.attachments.size.toString())
        }
        dialog(
            DialogSpec(str(R.string.details)) { dismiss ->
                val g = LocalGlass.current
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    rows.forEach { (k, v) ->
                        Column(Modifier.semantics(mergeDescendants = true) {}) {
                            Text(k, fontSize = 13.sp, color = g.contentSecondary)
                            Text(v, fontSize = 16.sp, color = g.content)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                GlassButton(str(R.string.close), dismiss, Modifier.fillMaxWidth())
            },
        )
    }

    fun bubbleMenu(m: Message, code: String?) {
        sheet(
            SheetSpec(
                Dates.header(Dates.day(m.date)) + " · " + Dates.time(m.date),
                buildList {
                    if (m.body.isNotBlank()) {
                        add(SheetAction(str(R.string.copy), Icons.Rounded.ContentCopy) {
                            Actions.copy(context, m.body)
                            toast(str(R.string.copied))
                        })
                    }
                    val isStarred = m.id in settings.starred
                    add(SheetAction(str(if (isStarred) R.string.unstar else R.string.star), if (isStarred) Icons.Rounded.StarOutline else Icons.Rounded.Star) {
                        Stores.settings.update { s -> s.copy(starred = if (m.id in s.starred) s.starred - m.id else s.starred + m.id) }
                    })
                    add(SheetAction(str(R.string.details), Icons.Rounded.Info) { details(m) })
                    if (code != null) {
                        add(SheetAction(str(R.string.copy_code, code), Icons.Rounded.Key) {
                            Actions.copy(context, code, sensitive = true)
                            toast(str(R.string.code_copied))
                        })
                    }
                    if (isDefault && m.box == MessageBox.FAILED && !m.mms && single != null) {
                        add(SheetAction(str(R.string.resend), Icons.Rounded.Refresh) {
                            sendMessage(context, people, m.body, emptyList(), SendTarget.UsePolicy, m.subscriptionId, dialog, toast) { sent ->
                                if (sent) Repo.io { Repo.store.delete(listOf(m.id)) }
                            }
                        })
                    }
                    if (isDefault) {
                        add(SheetAction(str(R.string.delete), Icons.Rounded.DeleteOutline) {
                            confirmDelete(str(R.string.delete_message_title), str(R.string.to_trash_text)) {
                                Repo.io {
                                    val entry = Repo.trash.deleteMessagesUndoable(listOf(m.id), people) ?: return@io
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        undoToast(str(R.string.moved_to_trash)) { Repo.io { Repo.trash.restore(entry) } }
                                    }
                                }
                            }
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
            subtitle = if (group) dev.liquidmessages.util.plural(R.plurals.group_members, people.size) else w.subtitle,
        ) {
            if (messages.isNotEmpty()) GlassIconButton(Icons.Rounded.Search, str(R.string.search_in_conversation), { searching = !searching; if (!searching) search = "" })
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
        LaunchedEffect(messages.size) { if (state.firstVisibleItemIndex <= 1 && focused == null) state.animateScrollToItem(0) }

        // Buscar en la conversación: los que coinciden, del más nuevo al más viejo (como la lista).
        val matches = remember(lines, search) {
            val q = dev.liquidmessages.core.text.Fold.fold(search.trim())
            if (q.isEmpty()) emptyList()
            else lines.indices.filter { i -> (lines[i] as? Line.Msg)?.m?.let { dev.liquidmessages.core.text.Fold.fold(it.body).contains(q) } == true }
        }
        LaunchedEffect(matches) { hit = 0 }
        LaunchedEffect(matches, hit) {
            val i = matches.getOrNull(hit) ?: return@LaunchedEffect
            focused = (lines[i] as Line.Msg).m.id
            state.animateScrollToItem(i)
        }
        // Desde Destacados: se va al mensaje una vez, cuando ya está cargado.
        var focusDone by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(lines, focus) {
            if (focus == null || focusDone) return@LaunchedEffect
            val i = lines.indexOfFirst { it is Line.Msg && it.m.id == focus }
            if (i >= 0) {
                focusDone = true
                focused = focus
                state.scrollToItem(i)
            }
        }
        if (searching) {
            SearchBar(
                search, { search = it },
                count = if (search.isBlank()) null else if (matches.isEmpty()) str(R.string.no_results) else str(R.string.match_of, hit + 1, matches.size),
                canOlder = hit < matches.size - 1,
                canNewer = hit > 0,
                onOlder = { hit++ },
                onNewer = { hit-- },
                onDate = {
                    val days = lines.withIndex().filter { it.value is Line.Day }
                    dialog(
                        DialogSpec(str(R.string.go_to_date)) { dismiss ->
                            androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                                items(days.size) { k ->
                                    val (i, l) = days[k]
                                    Text(
                                        Dates.header((l as Line.Day).date),
                                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                            .clickable { dismiss(); scope.launch { state.scrollToItem(i) } }
                                            .padding(horizontal = 8.dp, vertical = 12.dp),
                                        fontSize = 16.sp,
                                        color = LocalGlass.current.content,
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            GlassButton(str(R.string.cancel), dismiss, Modifier.fillMaxWidth())
                        },
                    )
                },
            )
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = state,
            reverseLayout = true,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            // Pegadas abajo, junto al campo de escribir, aunque sean pocas.
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Bottom),
        ) {
            items(lines, key = { l -> if (l is Line.Msg) "m${l.m.id}" else "d${(l as Line.Day).date}" }, contentType = { if (it is Line.Msg) 1 else 0 }) { l ->
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
                        starred = l.m.id in settings.starred,
                        reactions = applied.reactions[l.m.id],
                        focused = l.m.id == focused,
                        style = BubbleStyle(
                            contactColor = if (settings.bubbleColor == 1 && !l.m.box.outgoing) senderColors[l.m.address] else null,
                            fontSize = bubbleFont,
                            timeAlways = settings.timeMode == 0,
                        ),
                        sender = if (group && !l.m.box.outgoing) senders[l.m.address] else null,
                        chipLabel = if (sims.multiple) sims.bySubscription(l.m.subscriptionId)?.let { str(R.string.sim_label, it.slotNumber) } else null,
                        onLongClick = { bubbleMenu(l.m, l.code) },
                        onLink = { link -> Actions.link(context, link, dialog) },
                        onDownload = {
                            toast(str(R.string.mms_downloading))
                            Repo.io { MmsTransport.download(context, Uri.parse("content://mms/${-l.m.id}")) }
                        },
                        onOpen = { a -> scope.launch { Actions.openPart(context, a.partId, a.contentType, a.name) } },
                    )
                }
            }
        }

        // Programados de esta conversación: a la vista, encima del campo de escribir.
        val scheduled by Stores.scheduled.state.collectAsStateWithLifecycle()
        val mine = remember(scheduled, people, numbers) { scheduled.filter { Conversations.key(it.addresses, numbers) == Conversations.key(people, numbers) }.sortedBy { it.at } }
        mine.forEach { sm ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable {
                        sheet(
                            SheetSpec(
                                str(R.string.scheduled_for, scheduledLabel(sm.at)),
                                listOf(
                                    SheetAction(str(R.string.send_now), Icons.AutoMirrored.Rounded.Send) { Repo.io { dev.liquidmessages.schedule.Scheduler.sendNow(context, sm.id) } },
                                    SheetAction(str(R.string.cancel_scheduled), Icons.Rounded.Close) {
                                        dev.liquidmessages.schedule.Scheduler.cancel(context, sm.id)
                                        restore = sm.text
                                    },
                                ),
                            ),
                        )
                    }
                    .background(p.accentFg.copy(alpha = 0.10f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(styled(Icons.Rounded.Schedule), null, Modifier.size(18.dp), tint = p.accentFg)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(str(R.string.scheduled_for, scheduledLabel(sm.at)), fontSize = 12.sp, color = p.accentFg, fontWeight = FontWeight.Medium)
                    Text(sm.text, fontSize = 14.sp, color = p.content, maxLines = 2)
                }
            }
        }

        if (canReply) {
            Composer(
                draftKey = Conversations.key(people, numbers),
                initialText = initialText,
                initialImage = initialImage,
                simplify = settings.simplifyAccents,
                isDefault = isDefault,
                group = group,
                padding = padding,
                chipFor = { target -> (Repo.decide(target, lastSub, canSend = isDefault) as? SendDecision.Send)?.chip?.let(::chipName) },
                multipleChips = sims.multiple && isDefault,
                chips = sims.chips,
                restore = restore,
                onRestored = { restore = null },
                onSchedule = { text, target, at ->
                    val body = if (settings.simplifyAccents) SmsLength.simplify(text) else text
                    dev.liquidmessages.schedule.Scheduler.schedule(
                        context,
                        dev.liquidmessages.schedule.ScheduledMessage(System.currentTimeMillis(), people, body, at, (target as? SendTarget.Slot)?.slotIndex, lastSub),
                    )
                    toast(str(R.string.scheduled_for, scheduledLabel(at)))
                },
                onSend = { text, images, target, done ->
                    val body = if (settings.simplifyAccents) SmsLength.simplify(text) else text
                    sendMessage(context, people, body, images, target, lastSub, dialog, toast, undoToast to { t -> restore = t }, done)
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
 * con cientos de burbujas, cada superficie de cristal cuesta. En un grupo, arriba va quién lo mandó.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(
    m: Message,
    starred: Boolean,
    focused: Boolean,
    reactions: List<String>?,
    style: BubbleStyle,
    sender: String?,
    chipLabel: String?,
    onLongClick: () -> Unit,
    onLink: (dev.liquidmessages.core.text.Links.Link) -> Unit,
    onDownload: () -> Unit,
    onOpen: (Attachment) -> Unit,
) {
    val p = LocalGlass.current
    val mine = m.box.outgoing
    val failed = m.box == MessageBox.FAILED
    val tint = style.contactColor
    val bg = when {
        mine -> p.accentFill()
        tint != null -> tint.copy(alpha = if (p.dark) 0.34f else 0.26f)
        p.solid -> p.levels.getOrElse(1) { p.content.copy(alpha = 0.08f) }
        else -> p.content.copy(alpha = if (p.dark) 0.12f else 0.07f)
    }
    val fg = if (mine) p.onAccent else p.content
    // Con la hora "al tocar", un toque en el mensaje la muestra u oculta; un error se ve siempre.
    var reveal by remember { mutableStateOf(false) }
    val showMeta = style.timeAlways || reveal || failed || starred || m.box == MessageBox.OUTBOX || m.box == MessageBox.QUEUED
    val jumbo = remember(m.body) { m.attachments.isEmpty() && m.subject == null && isOnlyEmoji(m.body) }
    val meta = listOfNotNull(
        Dates.time(m.date),
        chipLabel,
        when (m.box) {
            MessageBox.FAILED -> str(R.string.failed)
            MessageBox.OUTBOX, MessageBox.QUEUED -> str(R.string.sending)
            MessageBox.SENT -> if (m.delivered) str(R.string.delivered) else null
            else -> null
        },
    ).joinToString(" · ")
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        if (sender != null) Text(sender, Modifier.padding(start = 12.dp, bottom = 2.dp), fontSize = 12.sp, color = p.contentSecondary, fontWeight = FontWeight.Medium)
        Column(
            Modifier
                .widthIn(max = 300.dp)
                .then(if (focused) Modifier.border(2.dp, p.accentFg, RoundedCornerShape(20.dp, 20.dp, if (mine) 6.dp else 20.dp, if (mine) 20.dp else 6.dp)) else Modifier)
                .clip(RoundedCornerShape(20.dp, 20.dp, if (mine) 6.dp else 20.dp, if (mine) 20.dp else 6.dp))
                .then(if (jumbo) Modifier else Modifier.background(bg))
                .combinedClickable(remember { MutableInteractionSource() }, null, onLongClick = onLongClick, onClick = { if (!style.timeAlways) reveal = !reveal }),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when {
                m.pending -> Row(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(str(R.string.mms_pending), color = fg.copy(alpha = 0.8f), fontSize = 15.sp, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(10.dp))
                    GlassButton(str(R.string.mms_download), onDownload, icon = Icons.Rounded.Download)
                }
                else -> {
                    m.attachments.forEach { a -> AttachmentView(a, fg, onOpen) }
                    m.subject?.let { Text(it, Modifier.padding(start = 14.dp, end = 14.dp, top = 9.dp), color = fg, fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
                    if (jumbo) {
                        // Solo emojis (hasta tres): grandes y sin burbuja, como en otras apps de mensajes.
                        Text(m.body.trim(), Modifier.padding(horizontal = 4.dp), fontSize = style.fontSize * 2.6f)
                    } else if (m.body.isNotBlank()) {
                        Box(Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) { MessageText(m.body, fg, onLink, fontSize = style.fontSize) }
                    } else if (m.attachments.isEmpty() && m.subject == null) {
                        Text(str(R.string.mms_placeholder), Modifier.padding(horizontal = 14.dp, vertical = 9.dp), color = fg.copy(alpha = 0.75f), fontSize = 15.sp)
                    }
                }
            }
        }
        if (!reactions.isNullOrEmpty()) {
            // Cada emoji una vez, con cuántos si son varios: "👍 2 ❤️".
            val label = reactions.groupingBy { it }.eachCount().entries.joinToString("  ") { (e, n) -> if (n > 1) "$e $n" else e }
            Text(
                label,
                Modifier
                    .padding(horizontal = 10.dp)
                    .offset(y = (-6).dp)
                    .clip(CircleShape)
                    .background(if (p.solid) p.levels.getOrElse(2) { p.content.copy(alpha = 0.12f) } else p.content.copy(alpha = if (p.dark) 0.18f else 0.10f))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .semantics { contentDescription = str(R.string.reactions_label, label) },
                fontSize = 14.sp,
                color = p.content,
            )
        }
        if (showMeta) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                if (starred) Icon(styled(Icons.Rounded.Star), str(R.string.starred_one), Modifier.size(14.dp).padding(end = 2.dp), tint = p.accentFg)
                if (failed) Icon(styled(Icons.Rounded.ErrorOutline), null, Modifier.size(14.dp).padding(end = 2.dp), tint = p.dangerText)
                Text(meta, fontSize = 12.sp, color = if (failed) p.dangerText else p.contentSecondary)
            }
        } else {
            Spacer(Modifier.height(2.dp))
        }
    }
}

/** Una foto se ve dentro de la burbuja (tocarla la abre en grande); lo demás, como un botón con su tipo. */
@Composable
private fun AttachmentView(a: Attachment, fg: androidx.compose.ui.graphics.Color, onOpen: (Attachment) -> Unit) {
    val context = LocalContext.current
    val maxPx = with(LocalDensity.current) { 300.dp.roundToPx() }
    if (a.kind == Attachment.Kind.IMAGE) {
        val bitmap by produceState<Bitmap?>(null, a.partId) { value = withContext(Dispatchers.IO) { MmsTransport.loadPart(context, a.partId, maxPx) } }
        val b = bitmap
        val label = str(R.string.attachment_photo)
        if (b != null) {
            Image(
                b.asImageBitmap(),
                label,
                Modifier.fillMaxWidth().heightIn(max = 360.dp).clickable { onOpen(a) },
                contentScale = ContentScale.FillWidth,
            )
        } else {
            Box(Modifier.size(200.dp, 150.dp).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
                Icon(styled(Icons.Rounded.Image), null, tint = fg.copy(alpha = 0.5f))
            }
        }
        return
    }
    val (icon, label) = when (a.kind) {
        Attachment.Kind.VIDEO -> Icons.Rounded.Movie to str(R.string.attachment_video)
        Attachment.Kind.AUDIO -> Icons.Rounded.Audiotrack to str(R.string.attachment_audio)
        Attachment.Kind.CONTACT -> Icons.Rounded.Person to str(R.string.attachment_contact)
        else -> Icons.Rounded.AttachFile to (a.name ?: str(R.string.attachment_file))
    }
    Row(
        Modifier.padding(horizontal = 10.dp, vertical = 6.dp).clip(RoundedCornerShape(14.dp)).clickable { onOpen(a) }.padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(styled(icon), null, Modifier.size(22.dp), tint = fg)
        Spacer(Modifier.width(8.dp))
        Text(label, color = fg, fontSize = 15.sp, maxLines = 1)
    }
}

/**
 * El campo de escribir: crece hasta 6 líneas, muestra el contador cuando importa (más de una
 * parte o caracteres que pasan a 70 por parte), el chip por el que va a salir (con dos chips; se
 * puede cambiar para este mensaje), una foto para adjuntar y el botón de enviar.
 */
@Composable
private fun Composer(
    draftKey: String,
    initialText: String,
    initialImage: Uri?,
    simplify: Boolean,
    isDefault: Boolean,
    group: Boolean,
    padding: PaddingValues,
    chipFor: (SendTarget) -> String?,
    multipleChips: Boolean,
    chips: List<dev.liquidmessages.core.sim.SimChip>,
    restore: String?,
    onRestored: () -> Unit,
    onSchedule: (String, SendTarget, Long) -> Unit,
    onSend: (String, List<MmsTransport.Image>, SendTarget, (Boolean) -> Unit) -> Unit,
) {
    val p = LocalGlass.current
    val context = LocalContext.current
    val sheet = LocalActionSheet.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    // Lo que llega de otra app manda; si no, el borrador guardado de esta conversación.
    var text by rememberSaveable { mutableStateOf(initialText.ifEmpty { Stores.drafts.value[draftKey].orEmpty() }) }
    // El borrador se guarda medio segundo después de dejar de escribir y al salir de la conversación.
    LaunchedEffect(text) {
        kotlinx.coroutines.delay(500)
        saveDraft(draftKey, text)
    }
    androidx.compose.runtime.DisposableEffect(draftKey) { onDispose { saveDraft(draftKey, text) } }
    LaunchedEffect(restore) {
        if (restore != null) {
            text = restore
            onRestored()
        }
    }
    val dialog = LocalDialog.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    var slot by rememberSaveable { mutableStateOf<Int?>(null) }
    var pick by remember { mutableStateOf<Picked?>(null) }
    val target = slot?.let { SendTarget.Slot(it) } ?: SendTarget.UsePolicy
    val info = remember(text, simplify) { SmsLength.of(if (simplify) SmsLength.simplify(text) else text) }
    val mms = pick != null || group

    fun attach(uri: Uri) {
        scope.launch {
            val prepared = withContext(Dispatchers.IO) {
                val max = (MmsTransport.maxSize(context, null) * 0.9).toInt()
                MmsTransport.prepareImage(context, uri, max)?.let { img ->
                    val preview = android.graphics.BitmapFactory.decodeByteArray(img.bytes, 0, img.bytes.size)
                    preview?.let { Picked(img, it) }
                }
            }
            if (prepared == null) toast(str(R.string.mms_too_big)) else pick = prepared
        }
    }
    LaunchedEffect(initialImage) { if (initialImage != null && isDefault) attach(initialImage) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(::attach) }

    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = padding.calculateBottomPadding().coerceAtLeast(8.dp))) {
        val chip = if (multipleChips) chipFor(target) else null
        if (chip != null || mms || (text.isNotEmpty() && (info.parts > 1 || info.unicode))) {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (chip != null) {
                    // Tocar el chip permite mandar este mensaje por el otro, a la vista.
                    Row(
                        Modifier.clip(CircleShape).clickable {
                            sheet(SheetSpec(str(R.string.choose_sim), chips.map { c -> SheetAction(chipName(c), Icons.Rounded.SimCard) { slot = c.slotIndex } }))
                        }.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(styled(Icons.Rounded.SimCard), null, Modifier.size(14.dp), tint = p.accentFg)
                        Spacer(Modifier.width(4.dp))
                        Text(chip, fontSize = 12.sp, color = p.accentFg, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.width(6.dp))
                }
                val counter = when {
                    mms -> str(R.string.will_send_mms)
                    text.isNotEmpty() && (info.parts > 1 || info.unicode) ->
                        str(R.string.parts_counter, info.remaining, info.parts) +
                            if (info.unicode) " · " + str(R.string.unicode_hint, info.nonGsm.take(3).joinToString(" ")) else ""
                    else -> null
                }
                counter?.let { Text(it, fontSize = 12.sp, color = p.contentSecondary) }
            }
        }
        pick?.let { pk ->
            Box(Modifier.padding(start = 8.dp, bottom = 6.dp)) {
                Image(pk.preview.asImageBitmap(), str(R.string.attachment_photo), Modifier.size(96.dp).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Crop)
                Box(
                    Modifier.align(Alignment.TopEnd).padding(4.dp).size(28.dp).clip(CircleShape).background(p.base.copy(alpha = 0.7f)).clickable { pick = null }
                        .semantics { contentDescription = str(R.string.remove_attachment) },
                    contentAlignment = Alignment.Center,
                ) { Icon(styled(Icons.Rounded.Close), null, Modifier.size(18.dp), tint = p.content) }
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            if (isDefault) {
                GlassIconButton(Icons.Rounded.AddPhotoAlternate, str(R.string.attach), {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                })
                Spacer(Modifier.size(8.dp))
            }
            // Con el campo vacío, las respuestas rápidas: se insertan, no se envían.
            val replies = Stores.settings.state.collectAsStateWithLifecycle().value.quickReplies
            if (text.isEmpty() && replies.isNotEmpty()) {
                GlassIconButton(Icons.Rounded.Quickreply, str(R.string.quick_replies), {
                    sheet(SheetSpec(str(R.string.quick_replies), replies.map { r -> SheetAction(r, Icons.Rounded.Quickreply) { text = r } }))
                })
                Spacer(Modifier.size(8.dp))
            }
            GlassTextField(
                text,
                { text = it },
                str(R.string.message_hint),
                Modifier.weight(1f).heightIn(max = 180.dp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                singleLine = false,
            )
            Spacer(Modifier.size(8.dp))
            // Programar: solo texto (sin foto) y siendo la predeterminada.
            if (isDefault && text.isNotBlank() && pick == null) {
                GlassIconButton(Icons.Rounded.Schedule, str(R.string.schedule), {
                    // Sin el teclado: si no, tapa la ventana y al cerrarse la corre de lugar.
                    keyboard?.hide()
                    focusManager.clearFocus()
                    dialog(DialogSpec(str(R.string.schedule)) { dismiss ->
                        ScheduleContent({ at ->
                            dismiss()
                            onSchedule(text.trim(), target, at)
                            text = ""
                            slot = null
                        }, dismiss)
                    })
                })
                Spacer(Modifier.size(8.dp))
            }
            val canSend = text.isNotBlank() || pick != null
            GlassIconButton(
                Icons.AutoMirrored.Rounded.Send,
                str(R.string.send),
                {
                    if (canSend) onSend(text.trim(), listOfNotNull(pick?.image), target) { done ->
                        if (done) {
                            text = ""
                            pick = null
                            slot = null
                        }
                    }
                },
                prominent = true,
                enabled = canSend,
            )
        }
    }
}

/** Barra de búsqueda dentro de una conversación: el texto, cuántos hay, anterior/siguiente e "ir a fecha". */
@Composable
private fun SearchBar(
    value: String,
    onChange: (String) -> Unit,
    count: String?,
    canOlder: Boolean,
    canNewer: Boolean,
    onOlder: () -> Unit,
    onNewer: () -> Unit,
    onDate: () -> Unit,
) {
    val p = LocalGlass.current
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        dev.liquidmessages.ui.components.SearchField(value, onChange, str(R.string.search_in_conversation), str(R.string.clear), focusRequester = focus)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(count.orEmpty(), Modifier.weight(1f).semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }, fontSize = 14.sp, color = p.contentSecondary)
            GlassIconButton(Icons.Rounded.CalendarMonth, str(R.string.go_to_date), onDate, size = 44.dp)
            GlassIconButton(Icons.Rounded.KeyboardArrowUp, str(R.string.older_match), onOlder, size = 44.dp, enabled = canOlder)
            GlassIconButton(Icons.Rounded.KeyboardArrowDown, str(R.string.newer_match), onNewer, size = 44.dp, enabled = canNewer)
        }
    }
}
