// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidmessages.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.liquidmessages.R
import dev.liquidmessages.core.sms.Message
import dev.liquidmessages.data.Repo
import dev.liquidmessages.data.Stores
import dev.liquidmessages.ui.components.who
import dev.liquidmessages.ui.glass.LocalActionSheet
import dev.liquidmessages.ui.glass.LocalGlass
import dev.liquidmessages.ui.glass.SheetAction
import dev.liquidmessages.ui.glass.SheetSpec
import dev.liquidmessages.util.Dates
import dev.liquidmessages.util.str

/**
 * Los mensajes destacados de todas las conversaciones, del más nuevo al más viejo. Tocar uno abre
 * su conversación en ese mensaje. Filas planas (sin cristal) para que desplazar no cueste.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StarredScreen(padding: PaddingValues, onBack: () -> Unit, onOpen: (Message, List<String>) -> Unit) {
    val inbox by Repo.inbox.collectAsStateWithLifecycle()
    val book by Repo.book.collectAsStateWithLifecycle()
    val numbers by Repo.numbers.collectAsStateWithLifecycle()
    val settings by Stores.settings.state.collectAsStateWithLifecycle()
    val sheet = LocalActionSheet.current
    val toast = LocalToast.current
    val context = LocalContext.current
    val p = LocalGlass.current
    val starred = settings.starred
    val list = remember(inbox, starred) { inbox.messages.filter { it.id in starred }.sortedByDescending { it.date } }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp, start = 12.dp, end = 12.dp),
    ) {
        item(key = "title") { BackTitle(str(R.string.starred), onBack, Modifier.padding(horizontal = 4.dp)) }
        if (list.isEmpty() && inbox.loaded) {
            item(key = "empty") { EmptyState(str(R.string.no_starred), str(R.string.no_starred_hint)) }
        }
        items(list, key = { it.id }) { m ->
            val people = inbox.participants[m.threadId]?.takeIf { it.isNotEmpty() } ?: listOf(m.address)
            val name = remember(people, book, numbers) { who(people, book, numbers).title }
            val text = when {
                m.body.isNotBlank() -> m.body
                m.attachments.isNotEmpty() -> str(R.string.attachment_photo)
                else -> str(R.string.mms_placeholder)
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .combinedClickable(
                        onClick = { onOpen(m, people) },
                        onLongClick = {
                            sheet(
                                SheetSpec(
                                    name,
                                    buildList {
                                        add(SheetAction(str(R.string.open_conversation), Icons.AutoMirrored.Rounded.OpenInNew) { onOpen(m, people) })
                                        if (m.body.isNotBlank()) {
                                            add(SheetAction(str(R.string.copy), Icons.Rounded.ContentCopy) {
                                                Actions.copy(context, m.body)
                                                toast(str(R.string.copied))
                                            })
                                        }
                                        add(SheetAction(str(R.string.unstar), Icons.Rounded.StarOutline) {
                                            Stores.settings.update { s -> s.copy(starred = s.starred - m.id) }
                                        })
                                    },
                                ),
                            )
                        },
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (m.box.outgoing) str(R.string.you_to, name) else name,
                        Modifier.weight(1f),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = p.content,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(Dates.short(m.date), fontSize = 13.sp, color = p.contentSecondary)
                }
                Text(text, Modifier.padding(top = 2.dp), fontSize = 15.sp, color = p.contentSecondary, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
