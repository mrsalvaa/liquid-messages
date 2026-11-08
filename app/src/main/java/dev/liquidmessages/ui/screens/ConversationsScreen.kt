// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidmessages.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.DoneAll
import dev.liquidmessages.ui.glass.LocalDialog
import dev.liquidmessages.ui.glass.GlassCard
import dev.liquidmessages.ui.glass.GlassButton
import dev.liquidmessages.ui.glass.DialogSpec
import dev.liquidmessages.ui.components.DangerButton
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Block
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
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
    val drafts by Stores.drafts.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf(setOf<Long>()) }
    val sheet = LocalActionSheet.current
    val dialog = LocalDialog.current
    val toast = LocalToast.current
    val context = LocalContext.current
    val p = LocalGlass.current
    val isDefault by Repo.isDefault.collectAsStateWithLifecycle()
    val tick by Repo.permissionTick.collectAsStateWithLifecycle()
    val notificationsOn = androidx.compose.runtime.remember(tick) { dev.liquidmessages.notify.Notifier.allowed(context) }
    val role = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { Repo.start() }
    val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { Repo.start() }
    // Recién hecha predeterminada: se piden las notificaciones una vez, para avisar cuando llega algo.
    var askedNotifications by rememberSaveable { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(isDefault, notificationsOn) {
        if (isDefault && !notificationsOn && !askedNotifications && !archived && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            askedNotifications = true
            notify.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

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
            add(SheetAction(str(if (c.prefs.muted) R.string.unmute else R.string.mute), if (c.prefs.muted) Icons.Rounded.NotificationsActive else Icons.Rounded.NotificationsOff) {
                toggle({ it.muted }, c.key) { copy(muted = it) }
            })
            add(SheetAction(str(R.string.conversation_sound), Icons.Rounded.MusicNote) {
                dev.liquidmessages.notify.Notifier.openConversationSound(context, c.key, r.who.title)
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
        val more = buildList {
            if (isDefault && single != null && SenderKind.of(single) != SenderKind.ALPHANUMERIC) {
                add(SheetAction(str(R.string.block), Icons.Rounded.Block) {
                    dialog(
                        DialogSpec(str(R.string.block_title, r.who.title)) { dismiss ->
                            Text(str(R.string.block_text), color = LocalGlass.current.contentSecondary, fontSize = 15.sp)
                            Spacer(Modifier.height(16.dp))
                            DangerButton(str(R.string.block), {
                                dismiss()
                                Repo.io { if (Repo.blocked.block(single)) kotlinx.coroutines.withContext(Dispatchers.Main) { toast(str(R.string.blocked_toast)) } }
                            }, Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            GlassButton(str(R.string.cancel), dismiss, Modifier.fillMaxWidth())
                        },
                    )
                })
            }
            if (isDefault) {
                add(SheetAction(str(R.string.delete), Icons.Rounded.DeleteOutline) {
                    dialog(
                        DialogSpec(str(R.string.delete_conversation_title)) { dismiss ->
                            Text(str(R.string.to_trash_text), color = LocalGlass.current.contentSecondary, fontSize = 15.sp)
                            Spacer(Modifier.height(16.dp))
                            DangerButton(str(R.string.delete), {
                                dismiss()
                                Repo.io { Repo.trash.deleteThreads(mapOf(c.threadId to c.addresses)) }
                            }, Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            GlassButton(str(R.string.cancel), dismiss, Modifier.fillMaxWidth())
                        },
                    )
                })
            }
        }
        sheet(SheetSpec(r.who.title, actions + more))
    }

    val list = rows
    // Lo que ya no está en la lista (borrado, archivado) sale de la selección.
    androidx.compose.runtime.LaunchedEffect(list) {
        val ids = list.orEmpty().map { it.c.threadId }.toSet()
        if (selected.any { it !in ids }) selected = selected intersect ids
    }
    ListSelection(selected, list.orEmpty(), isDefault, onChange = { selected = it }, menu = ::menu)

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
                        if (isDefault && list.orEmpty().any { it.c.unread > 0 }) {
                            GlassIconButton(Icons.Rounded.DoneAll, str(R.string.mark_all_read), {
                                val threads = list.orEmpty().filter { it.c.unread > 0 }.map { it.c.threadId }
                                Repo.io { threads.forEach { Repo.store.markRead(it) } }
                            })
                        }
                        GlassIconButton(Icons.Rounded.Archive, str(R.string.archived), onArchived)
                        GlassIconButton(Icons.Rounded.Settings, str(R.string.settings), onSettings)
                    }
                }
            }
        }
        item(key = "search") {
            SearchField(query, { query = it }, str(R.string.search_hint), str(R.string.clear), Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
        }
        if (!isDefault && !archived) {
            item(key = "default") {
                GlassCard(Modifier.padding(horizontal = 4.dp, vertical = 6.dp)) {
                    Text(str(R.string.default_title), fontSize = 17.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    Text(str(R.string.default_text), Modifier.padding(top = 4.dp, bottom = 12.dp), fontSize = 14.sp, color = p.contentSecondary)
                    GlassButton(str(R.string.default_action), {
                        dev.liquidmessages.sms.DefaultSms.request(context)?.let(role::launch)
                    }, Modifier.fillMaxWidth(), prominent = true)
                }
            }
        } else if (isDefault && !notificationsOn && !archived) {
            item(key = "notifications") {
                GlassCard(Modifier.padding(horizontal = 4.dp, vertical = 6.dp)) {
                    Text(str(R.string.notifications_off), fontSize = 14.sp, color = p.content)
                    GlassButton(str(R.string.notifications_allow), {
                        context.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }, Modifier.fillMaxWidth().padding(top = 10.dp))
                }
            }
        }
        if (inbox.fake) {
            item(key = "fake") {
                Text(str(R.string.fake_notice), Modifier.padding(horizontal = 12.dp, vertical = 6.dp), fontSize = 13.sp, color = p.dangerText)
            }
        }
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
            SwipeRow(
                right = SwipeAction.of(settings.swipeRight).takeIf { it.available(isDefault) && selected.isEmpty() },
                left = SwipeAction.of(settings.swipeLeft).takeIf { it.available(isDefault) && selected.isEmpty() },
                pinned = r.c.prefs.pinned,
                archivedView = archived,
                onSwipe = { action ->
                    when (action) {
                        SwipeAction.ARCHIVE -> toggle({ it.archived }, r.c.key) { copy(archived = it) }
                        SwipeAction.PIN -> toggle({ it.pinned }, r.c.key) { copy(pinned = it) }
                        SwipeAction.READ -> Repo.io { Repo.store.markRead(r.c.threadId) }
                        SwipeAction.DELETE -> {
                            Repo.io { Repo.trash.deleteThreads(mapOf(r.c.threadId to r.c.addresses)) }
                            toast(str(R.string.moved_to_trash))
                        }
                        SwipeAction.NONE -> Unit
                    }
                },
            ) {
                val id = r.c.threadId
                ConversationRow(
                    r.c,
                    r.who,
                    // Seleccionando, tocar suma o saca; si no, abre. Mantener apretado empieza a seleccionar.
                    onClick = { if (selected.isEmpty()) onOpen(r.c) else selected = if (id in selected) selected - id else selected + id },
                    onLongClick = { selected = selected + id },
                    shown = r.shown,
                    compact = settings.compactList,
                    selected = if (selected.isEmpty()) null else id in selected,
                    draft = drafts[r.c.key],
                )
            }
        }
    }
}

/** Lo que hace deslizar una conversación (se elige en Ajustes, una por lado). */
enum class SwipeAction(val value: Int, val label: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    NONE(0, R.string.swipe_none, Icons.Rounded.Block),
    ARCHIVE(1, R.string.archive, Icons.Rounded.Archive),
    DELETE(2, R.string.delete, Icons.Rounded.DeleteOutline),
    READ(3, R.string.notif_mark_read, Icons.Rounded.DoneAll),
    PIN(4, R.string.pin, Icons.Rounded.PushPin);

    /** Borrar y marcar como leída solo los puede hacer la app predeterminada. */
    fun available(isDefault: Boolean) = this != NONE && (isDefault || (this != DELETE && this != READ))

    companion object {
        fun of(value: Int) = entries.firstOrNull { it.value == value } ?: NONE
    }
}

/**
 * Una fila que se puede deslizar. Al pasar un tercio aparece el ícono de la acción del lado; al
 * soltar se hace y la fila vuelve (si la acción la saca de la lista, se va con ella).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SwipeRow(
    right: SwipeAction?,
    left: SwipeAction?,
    pinned: Boolean,
    archivedView: Boolean,
    onSwipe: (SwipeAction) -> Unit,
    content: @Composable () -> Unit,
) {
    if (right == null && left == null) {
        content()
        return
    }
    val p = LocalGlass.current
    val state = androidx.compose.material3.rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                androidx.compose.material3.SwipeToDismissBoxValue.StartToEnd -> right?.let(onSwipe)
                androidx.compose.material3.SwipeToDismissBoxValue.EndToStart -> left?.let(onSwipe)
                else -> Unit
            }
            false // La fila vuelve a su lugar; si la acción la saca, la lista ya no la tiene.
        },
        positionalThreshold = { it * 0.33f },
    )
    androidx.compose.material3.SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = right != null,
        enableDismissFromEndToStart = left != null,
        backgroundContent = {
            val toRight = state.dismissDirection == androidx.compose.material3.SwipeToDismissBoxValue.StartToEnd
            val action = (if (toRight) right else left) ?: return@SwipeToDismissBox
            val icon = when {
                action == SwipeAction.ARCHIVE && archivedView -> Icons.Rounded.Unarchive
                else -> action.icon
            }
            val color = if (action == SwipeAction.DELETE) p.dangerText else p.accentFg
            androidx.compose.foundation.layout.Box(
                Modifier.fillMaxSize().padding(horizontal = 24.dp),
                contentAlignment = if (toRight) androidx.compose.ui.Alignment.CenterStart else androidx.compose.ui.Alignment.CenterEnd,
            ) {
                androidx.compose.material3.Icon(dev.liquidmessages.ui.glass.styled(icon), null, tint = color)
            }
        },
    ) { content() }
}

/**
 * Barras de la selección múltiple de conversaciones: leídas, fijar, archivar, silenciar y borrar;
 * con una sola, "Más" abre el menú de esa conversación. Atrás sale de la selección.
 */
@Composable
private fun ListSelection(selected: Set<Long>, rows: List<ListRow>, isDefault: Boolean, onChange: (Set<Long>) -> Unit, menu: (ListRow) -> Unit) {
    val setBottom = LocalSelectionBottom.current
    val dialog = LocalDialog.current
    val toast = LocalToast.current
    val active = selected.isNotEmpty()
    androidx.activity.compose.BackHandler(enabled = active) { onChange(emptySet()) }
    val chosen = rows.filter { it.c.threadId in selected }
    fun keys() = chosen.map { it.c.key }.toSet()
    fun flip(all: Boolean, get: (AppSettings) -> Set<String>, put: AppSettings.(Set<String>) -> AppSettings) {
        val k = keys()
        Stores.settings.update { s -> s.put(if (all) get(s) - k else get(s) + k) }
        onChange(emptySet())
    }
    val allPinned = chosen.isNotEmpty() && chosen.all { it.c.prefs.pinned }
    val allArchived = chosen.isNotEmpty() && chosen.all { it.c.prefs.archived }
    val allMuted = chosen.isNotEmpty() && chosen.all { it.c.prefs.muted }
    val items = androidx.compose.runtime.rememberUpdatedState(
        buildList {
            if (isDefault) add(BarItem(Icons.Rounded.DoneAll, str(R.string.bar_read), str(R.string.notif_mark_read), active) {
                val threads = chosen.map { it.c.threadId }
                Repo.io { threads.forEach { Repo.store.markRead(it) } }
                onChange(emptySet())
            })
            add(BarItem(Icons.Rounded.PushPin, str(if (allPinned) R.string.unpin else R.string.pin), str(if (allPinned) R.string.unpin else R.string.pin), active) { flip(allPinned, { it.pinned }) { copy(pinned = it) } })
            add(BarItem(if (allArchived) Icons.Rounded.Unarchive else Icons.Rounded.Archive, str(if (allArchived) R.string.unarchive else R.string.archive), str(if (allArchived) R.string.unarchive else R.string.archive), active) { flip(allArchived, { it.archived }) { copy(archived = it) } })
            add(BarItem(if (allMuted) Icons.Rounded.NotificationsActive else Icons.Rounded.NotificationsOff, str(if (allMuted) R.string.unmute else R.string.mute), str(if (allMuted) R.string.unmute else R.string.mute), active) { flip(allMuted, { it.muted }) { copy(muted = it) } })
            if (isDefault) add(BarItem(Icons.Rounded.DeleteOutline, str(R.string.delete), str(R.string.delete), active) {
                dialog(DialogSpec(if (chosen.size == 1) str(R.string.delete_conversation_title) else str(R.string.delete_conversations_title, chosen.size)) { dismiss ->
                    Text(str(R.string.to_trash_text), color = LocalGlass.current.contentSecondary, fontSize = 15.sp)
                    Spacer(Modifier.height(16.dp))
                    DangerButton(str(R.string.delete), {
                        dismiss()
                        val threads = chosen.associate { it.c.threadId to it.c.addresses }
                        Repo.io { Repo.trash.deleteThreads(threads) }
                        toast(str(R.string.moved_to_trash))
                        onChange(emptySet())
                    }, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    GlassButton(str(R.string.cancel), dismiss, Modifier.fillMaxWidth())
                })
            })
            chosen.singleOrNull()?.let { one -> add(BarItem(Icons.Rounded.MoreHoriz, str(R.string.more), str(R.string.more), active) { menu(one) }) }
        },
    )
    val top = androidx.compose.runtime.rememberUpdatedState(
        SelectionTopState(selected.size, selected.size < rows.size, { onChange(rows.map { it.c.threadId }.toSet()) }, { onChange(emptySet()) }),
    )
    androidx.compose.runtime.DisposableEffect(active) {
        if (active) setBottom(SelectionBottom(top, items))
        onDispose { setBottom(null) }
    }
}
