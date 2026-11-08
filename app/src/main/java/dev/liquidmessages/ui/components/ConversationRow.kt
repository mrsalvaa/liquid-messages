// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidmessages.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.liquidmessages.R
import dev.liquidmessages.core.phone.PhoneNumbers
import dev.liquidmessages.core.sms.Box as MessageBox
import dev.liquidmessages.core.sms.Conversation
import dev.liquidmessages.core.sms.Message
import dev.liquidmessages.core.sms.SenderKind
import dev.liquidmessages.data.Book
import dev.liquidmessages.core.phone.PhoneMatch
import dev.liquidmessages.ui.glass.LocalGlass
import dev.liquidmessages.ui.glass.readableOn
import dev.liquidmessages.ui.glass.styled
import dev.liquidmessages.util.Dates
import dev.liquidmessages.util.str

/** Cómo se muestra a quién es una conversación. */
data class Who(
    val title: String,
    /** El número con formato, si el título es un nombre de contacto. */
    val subtitle: String?,
    val photoUri: String?,
    /** El contacto, si el número es de exactamente uno. */
    val contactId: Long?,
)

/**
 * Nombre de contacto si el número es de uno solo (con varios candidatos no se elige ninguno, como en
 * Phone), si no el número con formato; un remitente con letras va tal cual.
 */
fun who(addresses: List<String>, book: Book, numbers: PhoneNumbers): Who {
    fun one(address: String): Who {
        if (SenderKind.of(address) == SenderKind.ALPHANUMERIC) return Who(address.trim(), null, null, null)
        val formatted = numbers.format(address)
        val match = book.matcher?.match(address)
        val contact = (match as? PhoneMatch.One)?.item
        return if (contact != null && contact.displayName.isNotBlank()) {
            Who(contact.displayName, formatted, contact.photoUri, contact.id)
        } else {
            Who(formatted, null, null, null)
        }
    }
    return when (addresses.size) {
        0 -> Who("?", null, null, null)
        1 -> one(addresses[0])
        else -> Who(addresses.map { one(it).title }.joinToString(", "), null, null, null)
    }
}

/**
 * Lo que se ve de un mensaje en una sola línea: el texto, o qué adjunto tiene ("Foto"), o que es un
 * MMS sin bajar; con "Vos: …" si es tuyo.
 */
fun snippet(m: Message): String {
    val text = when {
        m.pending -> str(R.string.mms_pending)
        m.body.isNotBlank() -> m.body.replace('\n', ' ').trim()
        m.subject != null -> m.subject!!
        m.attachments.isNotEmpty() -> str(
            when (m.attachments.first().kind) {
                dev.liquidmessages.core.sms.Attachment.Kind.IMAGE -> R.string.attachment_photo
                dev.liquidmessages.core.sms.Attachment.Kind.VIDEO -> R.string.attachment_video
                dev.liquidmessages.core.sms.Attachment.Kind.AUDIO -> R.string.attachment_audio
                dev.liquidmessages.core.sms.Attachment.Kind.CONTACT -> R.string.attachment_contact
                dev.liquidmessages.core.sms.Attachment.Kind.FILE -> R.string.attachment_file
            },
        )
        m.mms -> str(R.string.mms_placeholder)
        else -> ""
    }
    return if (m.box.outgoing) str(R.string.you_prefix, text) else text
}

/**
 * Fila de la lista: avatar, nombre (en negrita si hay sin leer), último mensaje, hora y cuántos sin
 * leer. Plana, sin cristal propio (el cristal va en los contenedores, como en Contacts).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConversationRow(
    c: Conversation,
    who: Who,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Para la búsqueda: el mensaje que coincide, en vez del último. */
    shown: Message = c.last,
    /** Lista compacta: fila más baja, avatar más chico y una sola línea de vista previa. */
    compact: Boolean = false,
    /** null = la lista no está seleccionando. */
    selected: Boolean? = null,
) {
    val p = LocalGlass.current
    val unread = c.unread > 0
    val failed = shown.box == MessageBox.FAILED
    val line = snippet(shown)
    val time = Dates.short(shown.date)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = if (compact) 56.dp else 72.dp)
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(remember { MutableInteractionSource() }, null, role = Role.Button, onLongClick = onLongClick, onClick = onClick)
            .semantics(mergeDescendants = true) {
                if (selected != null) this.selected = selected
                contentDescription = listOfNotNull(
                    who.title,
                    if (c.prefs.pinned) str(R.string.pinned_cd) else null,
                    if (unread) Strings.unread(c.unread) else null,
                    if (failed) str(R.string.failed) else null,
                    line,
                    time,
                ).joinToString(", ")
            }
            .padding(horizontal = 8.dp, vertical = if (compact) 4.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val avatarSize = if (compact) 38.dp else 48.dp
        if (selected == true) {
            Box(Modifier.size(avatarSize).clip(CircleShape).background(p.accentFg), contentAlignment = Alignment.Center) {
                Icon(styled(Icons.Rounded.Check), null, Modifier.size(avatarSize * 0.55f), tint = readableOn(p.accentFg))
            }
        } else {
            ContactAvatar(who.title, size = avatarSize, photoUri = who.photoUri)
        }
        Spacer(Modifier.width(if (compact) 12.dp else 14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    who.title,
                    Modifier.weight(1f),
                    fontSize = 17.sp,
                    fontWeight = if (unread) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (c.prefs.pinned) Icon(styled(Icons.Rounded.PushPin), null, Modifier.padding(start = 6.dp).size(15.dp), tint = p.contentSecondary)
                Text(
                    time,
                    Modifier.padding(start = 8.dp),
                    fontSize = 13.sp,
                    color = if (unread) p.accentFg else p.contentSecondary,
                    fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (failed) Icon(styled(Icons.Rounded.ErrorOutline), null, Modifier.size(16.dp), tint = p.dangerText)
                Text(
                    if (failed) str(R.string.failed) + " · " + line else line,
                    Modifier.weight(1f),
                    fontSize = 15.sp,
                    color = if (unread) p.content else p.contentSecondary,
                    maxLines = if (compact) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (unread) UnreadBadge(c.unread)
            }
        }
    }
}

/** Cantidad sin leer en una píldora del color de énfasis. */
@Composable
fun UnreadBadge(count: Int) {
    val p = LocalGlass.current
    val bg = p.accentFg
    Box(
        Modifier.defaultMinSize(minWidth = 22.dp, minHeight = 22.dp).clip(CircleShape).background(bg).padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (count > 99) "99+" else count.toString(), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = readableOn(bg))
    }
}

/** Textos con plural, sin depender de Compose. */
private object Strings {
    fun unread(n: Int): String = dev.liquidmessages.util.plural(R.plurals.unread_count, n)
}
