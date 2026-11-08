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
import androidx.compose.material.icons.rounded.Refresh
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
    val p = LocalGlass.current
    val scope = rememberCoroutineScope()

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
    val group = people.size > 1
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
                add(Line.Msg(m, if (m.box == MessageBox.INBOX && m.body.isNotBlank()) Codes.find(m.body) else null))
            }
        }.asReversed()
    }
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
                            confirmDelete(str(R.string.delete_message_title), str(R.string.to_trash_text)) { Repo.io { Repo.trash.deleteMessages(listOf(m.id), people) } }
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
                        BubbleStyle(
                            contactColor = if (settings.bubbleColor == 1 && !l.m.box.outgoing) dev.liquidmessages.ui.components.avatarColor(who(listOf(l.m.address.ifBlank { people.firstOrNull().orEmpty() }), book, numbers).title, p.dark) else null,
                            fontSize = textSizeOf(settings.textSize),
                            timeAlways = settings.timeMode == 0,
                        ),
                        sender = if (group && !l.m.box.outgoing) who(listOf(l.m.address), book, numbers).title else null,
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
                onSend = { text, images, target, done ->
                    val body = if (settings.simplifyAccents) SmsLength.simplify(text) else text
                    sendMessage(context, people, body, images, target, lastSub, dialog, toast, done)
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
    val showMeta = style.timeAlways || reveal || failed || m.box == MessageBox.OUTBOX || m.box == MessageBox.QUEUED
    val jumbo = remember(m.body) { m.attachments.isEmpty() && m.subject == null && isOnlyEmoji(m.body) }
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
        if (sender != null) Text(sender, Modifier.padding(start = 12.dp, bottom = 2.dp), fontSize = 12.sp, color = p.contentSecondary, fontWeight = FontWeight.Medium)
        Column(
            Modifier
                .widthIn(max = 300.dp)
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
        if (showMeta) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
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
            GlassTextField(
                text,
                { text = it },
                str(R.string.message_hint),
                Modifier.weight(1f).heightIn(max = 180.dp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                singleLine = false,
            )
            Spacer(Modifier.size(8.dp))
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
