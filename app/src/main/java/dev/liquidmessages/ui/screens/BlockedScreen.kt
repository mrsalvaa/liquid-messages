// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidmessages.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.liquidmessages.R
import dev.liquidmessages.data.Repo
import dev.liquidmessages.ui.components.ContactRow
import dev.liquidmessages.ui.glass.GlassButton
import dev.liquidmessages.util.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** La lista de números bloqueados del sistema, con "Desbloquear" en cada uno. */
@Composable
fun BlockedScreen(padding: PaddingValues, onBack: () -> Unit) {
    val numbers by Repo.numbers.collectAsStateWithLifecycle()
    var version by remember { mutableIntStateOf(0) }
    val list by produceState<List<Pair<Long, String>>?>(null, version) { value = withContext(Dispatchers.IO) { Repo.blocked.list() } }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp, start = 12.dp, end = 12.dp),
    ) {
        item(key = "title") { BackTitle(str(R.string.blocked_numbers), onBack, Modifier.padding(horizontal = 4.dp)) }
        val l = list
        if (l != null && l.isEmpty()) item(key = "empty") { EmptyState(str(R.string.no_blocked), "") }
        items(l.orEmpty(), key = { it.first }) { (_, number) ->
            val name = Repo.contactFor(number)?.displayName
            ContactRow(
                name ?: numbers.format(number),
                onClick = {},
                subtitle = name?.let { numbers.format(number) },
                trailing = {
                    GlassButton(str(R.string.unblock), {
                        Repo.io {
                            Repo.blocked.unblock(number)
                            version++
                        }
                    })
                },
            )
        }
    }
}
