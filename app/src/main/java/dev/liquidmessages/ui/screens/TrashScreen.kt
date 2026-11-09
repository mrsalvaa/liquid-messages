// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidmessages.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.liquidmessages.R
import dev.liquidmessages.data.Repo
import dev.liquidmessages.sms.Trash
import dev.liquidmessages.sms.TrashEntry
import dev.liquidmessages.ui.components.ContactRow
import dev.liquidmessages.ui.components.DangerButton
import dev.liquidmessages.ui.components.who
import dev.liquidmessages.ui.glass.DialogSpec
import dev.liquidmessages.ui.glass.GlassButton
import dev.liquidmessages.ui.glass.LocalActionSheet
import dev.liquidmessages.ui.glass.LocalDialog
import dev.liquidmessages.ui.glass.LocalGlass
import dev.liquidmessages.ui.glass.SheetAction
import dev.liquidmessages.ui.glass.SheetSpec
import dev.liquidmessages.util.plural
import dev.liquidmessages.util.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lo borrado en los últimos 30 días. Tocar una entrada ofrece restaurarla (vuelve a su
 * conversación) o borrarla para siempre. Restaurar necesita ser la app de SMS predeterminada.
 */
@Composable
fun TrashScreen(padding: PaddingValues, onBack: () -> Unit) {
    val book by Repo.book.collectAsStateWithLifecycle()
    val numbers by Repo.numbers.collectAsStateWithLifecycle()
    val isDefault by Repo.isDefault.collectAsStateWithLifecycle()
    val sheet = LocalActionSheet.current
    val dialog = LocalDialog.current
    val toast = LocalToast.current
    var version by remember { mutableIntStateOf(0) }
    val entries by produceState<List<TrashEntry>?>(null, version) { value = withContext(Dispatchers.IO) { Repo.trash.entries() } }

    fun confirmPurge(entry: TrashEntry?) {
        dialog(
            DialogSpec(str(if (entry == null) R.string.trash_empty_title else R.string.trash_purge_title)) { dismiss ->
                Text(str(R.string.delete_cant_undo), color = LocalGlass.current.contentSecondary, fontSize = 15.sp)
                Spacer(Modifier.height(16.dp))
                DangerButton(str(R.string.delete), {
                    dismiss()
                    Repo.io {
                        Repo.trash.purge(entry?.id)
                        version++
                    }
                }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                GlassButton(str(R.string.cancel), dismiss, Modifier.fillMaxWidth())
            },
        )
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp, start = 12.dp, end = 12.dp),
    ) {
        item(key = "title") { BackTitle(str(R.string.trash), onBack, Modifier.padding(horizontal = 4.dp)) }
        val list = entries
        // Vaciar arriba, a la vista apenas se entra.
        if (!list.isNullOrEmpty()) {
            item(key = "empty_all") {
                DangerButton(str(R.string.trash_empty_title), { confirmPurge(null) }, Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp))
            }
        }
        if (list != null && list.isEmpty()) item(key = "empty") { EmptyState(str(R.string.trash_none), str(R.string.trash_hint)) }
        items(list.orEmpty(), key = { it.id }) { e ->
            val title = who(e.participants.ifEmpty { e.messages.map { it.address }.distinct() }, book, numbers).title
            // Hacia arriba: recién borrado son 30 días, no 29.
            val left = ((Trash.DAYS_30 - (System.currentTimeMillis() - e.deletedAt) + 86_399_999L) / 86_400_000L).coerceIn(0, 30).toInt()
            ContactRow(
                title,
                onClick = {
                    sheet(
                        SheetSpec(
                            title,
                            buildList {
                                if (isDefault) {
                                    add(SheetAction(str(R.string.restore), Icons.Rounded.Restore) {
                                        Repo.io {
                                            val n = Repo.trash.restore(e.id)
                                            version++
                                            withContext(Dispatchers.Main) { toast(plural(R.plurals.restored_count, n)) }
                                        }
                                    })
                                }
                                add(SheetAction(str(R.string.delete_forever), Icons.Rounded.DeleteForever) { confirmPurge(e) })
                            },
                        ),
                    )
                },
                subtitle = plural(R.plurals.message_count, e.messages.size) + " · " + plural(R.plurals.days_left, left),
            )
        }
    }
}
