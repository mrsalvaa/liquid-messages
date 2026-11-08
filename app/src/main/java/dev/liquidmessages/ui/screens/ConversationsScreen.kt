// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidmessages.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.liquidmessages.R
import dev.liquidmessages.core.sms.Conversation
import dev.liquidmessages.core.sms.ConversationPrefs
import dev.liquidmessages.core.sms.Conversations
import dev.liquidmessages.core.sms.Message
import dev.liquidmessages.core.sms.SenderKind
import dev.liquidmessages.core.text.Fold
import dev.liquidmessages.data.AppSettings
import dev.liquidmessages.data.Repo
import dev.liquidmessages.data.Stores
import dev.liquidmessages.ui.components.ConversationRow
import dev.liquidmessages.ui.components.dangerText
import dev.liquidmessages.ui.components.SearchField
import dev.liquidmessages.ui.components.Who
import dev.liquidmessages.ui.components.who
import dev.liquidmessages.ui.glass.GlassIconButton
import dev.liquidmessages.ui.glass.LocalActionSheet
import dev.liquidmessages.ui.glass.LocalGlass
import dev.liquidmessages.ui.glass.ScreenTitle
import dev.liquidmessages.ui.glass.SheetAction
import dev.liquidmessages.ui.glass.SheetSpec
import dev.liquidmessages.util.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Una fila ya armada: la conversación, a quién es y qué mensaje mostrar. */
class ListRow(val c: Conversation, val who: Who, val shown: Message)

/** Lo que la app guarda de cada conversación, a partir de los ajustes. */
fun prefsOf(s: AppSettings): (String) -> ConversationPrefs = { key ->
    ConversationPrefs(pinned = key in s.pinned, archived = key in s.archived, muted = key in s.muted)
}

/**
 * La lista de conversaciones (o de archivadas, con [archived]). Con algo escrito en la búsqueda,
 * quedan las conversaciones cuyo nombre, número o algún mensaje coincida, sin importar tildes; la
 * fila muestra el mensaje más nuevo que coincide.
 */
@Composable
fun ConversationsScreen(
    padding: PaddingValues,
    archived: Boolean,
    onOpen: (Conversation) -> Unit,
    onSettings: () -> Unit,
    onArchived: () -> Unit,
    onBack: () -> Unit,
) {
    val inbox by Repo.inbox.collectAsStateWithLifecycle()
    val book by Repo.book.collectAsStateWithLifecycle()
    val numbers by Repo.numbers.collectAsStateWithLifecycle()
    val settings by Stores.settings.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val sheet = LocalActionSheet.current
    val context = LocalContext.current
    val p = LocalGlass.current

    val rows by produceState<List<ListRow>?>(null, inbox, book, numbers, settings.pinned, settings.archived, settings.muted, query, archived) {
        value = withContext(Dispatchers.Default) {
            val list = Conversations.build(inbox.messages, numbers, prefsOf(settings), { inbox.participants[it] }, archived)
            val q = Fold.fold(query.trim())
            val digits = query.filter(Char::isDigit)
            if (q.isEmpty()) {
                list.map { ListRow(it, who(it.addresses, book, numbers), it.last) }
            } else {
                // Mensajes que coinciden, por hilo, el más nuevo primero. Los resultados van por la
                // fecha de lo que coincide, no por la del último mensaje.
                val hits = inbox.messages.asSequence()
                    .filter { !it.mms && Fold.fold(it.body).contains(q) }
                    .groupBy { it.threadId }
                    .mapValues { (_, l) -> l.maxBy { it.date } }
                list.mapNotNull { c ->
                    val w = who(c.addresses, book, numbers)
                    val byName = Fold.fold(w.title).contains(q) ||
                        (digits.length >= 3 && c.addresses.any { a -> a.filter(Char::isDigit).contains(digits) })
                    val hit = hits[c.threadId]
                    when {
                        hit != null -> ListRow(c, w, hit)
                        byName -> ListRow(c, w, c.last)
                        else -> null
                    }
                }.sortedByDescending { it.shown.date }
            }
        }
    }

    fun toggle(get: (AppSettings) -> Set<String>, key: String, put: AppSettings.(Set<String>) -> AppSettings) {
        Stores.settings.update { s -> val cur = get(s); s.put(if (key in cur) cur - key else cur + key) }
    }

    fun menu(r: ListRow) {
        val c = r.c
        val single = c.addresses.singleOrNull()
        val actions = buildList {
            add(SheetAction(str(if (c.prefs.pinned) R.string.unpin else R.string.pin), Icons.Rounded.PushPin) {
                toggle({ it.pinned }, c.key) { copy(pinned = it) }
            })
            add(SheetAction(str(if (c.prefs.archived) R.string.unarchive else R.string.archive), if (c.prefs.archived) Icons.Rounded.Unarchive else Icons.Rounded.Archive) {
                toggle({ it.archived }, c.key) { copy(archived = it) }
            })
            if (single != null && SenderKind.of(single) != SenderKind.ALPHANUMERIC) {
                add(SheetAction(str(R.string.call), Icons.Rounded.Call) { Actions.dial(context, single) })
                val id = r.who.contactId
                if (id != null) {
                    add(SheetAction(str(R.string.open_contact), Icons.Rounded.Person) { Actions.openContact(context, id) })
                } else {
                    add(SheetAction(str(R.string.add_contact), Icons.Rounded.PersonAdd) { Actions.addContact(context, single) })
                }
            }
        }
        sheet(SheetSpec(r.who.title, actions))
    }

    val state = rememberLazyListState()
    LazyColumn(
        Modifier.fillMaxSize(),
        state = state,
        contentPadding = PaddingValues(
            top = padding.calculateTopPadding(),
            bottom = padding.calculateBottomPadding() + 90.dp,
            start = 12.dp,
            end = 12.dp,
        ),
    ) {
        item(key = "title") {
            if (archived) {
                BackTitle(str(R.string.archived), onBack, Modifier.padding(horizontal = 4.dp))
            } else {
                ScreenTitle(str(R.string.conversations), Modifier.padding(horizontal = 4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GlassIconButton(Icons.Rounded.Archive, str(R.string.archived), onArchived)
                        GlassIconButton(Icons.Rounded.Settings, str(R.string.settings), onSettings)
                    }
                }
            }
        }
        item(key = "search") {
            SearchField(query, { query = it }, str(R.string.search_hint), str(R.string.clear), Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
        }
        if (inbox.fake) {
            item(key = "fake") {
                Text(str(R.string.fake_notice), Modifier.padding(horizontal = 12.dp, vertical = 6.dp), fontSize = 13.sp, color = p.dangerText)
            }
        }
        val list = rows
        if (list != null && list.isEmpty() && inbox.loaded) {
            item(key = "empty") {
                when {
                    query.isNotBlank() -> EmptyState(str(R.string.no_results), "")
                    archived -> EmptyState(str(R.string.no_archived), str(R.string.no_archived_hint))
                    else -> EmptyState(str(R.string.no_conversations), str(R.string.no_conversations_hint))
                }
            }
        }
        items(list.orEmpty(), key = { it.c.threadId }, contentType = { "row" }) { r ->
            ConversationRow(r.c, r.who, onClick = { onOpen(r.c) }, onLongClick = { menu(r) }, shown = r.shown)
        }
    }
}
