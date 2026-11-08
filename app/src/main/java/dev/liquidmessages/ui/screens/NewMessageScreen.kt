// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidmessages.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.liquidmessages.R
import dev.liquidmessages.core.text.ContactSearch
import dev.liquidmessages.data.Repo
import dev.liquidmessages.ui.components.ContactRow
import dev.liquidmessages.ui.glass.GlassTextField
import dev.liquidmessages.ui.glass.LocalGlass
import dev.liquidmessages.ui.glass.SectionLabel
import dev.liquidmessages.ui.glass.styled
import dev.liquidmessages.util.str

/** Un número de un contacto, para elegir a quién escribir. */
private class Pick(val name: String, val number: String, val photoUri: String?, val score: Int)

/**
 * Elegir a quién escribir: los contactos con número que coinciden con lo escrito (uno por número),
 * o el número tal cual si se escribió uno. Elegir abre la conversación (la que ya exista con ese
 * número, o una nueva).
 */
@Composable
fun NewMessageScreen(padding: PaddingValues, onBack: () -> Unit, onPick: (String) -> Unit) {
    val book by Repo.book.collectAsStateWithLifecycle()
    val numbers by Repo.numbers.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val p = LocalGlass.current

    val search = remember(query, numbers) { ContactSearch(query, numbers::searchForms) }
    val picks = remember(book, search) {
        book.rows.flatMap { c ->
            val score = search.score(c.displayName, c.phones)
            if (score == 0) emptyList() else c.phones.map { Pick(c.displayName, it, c.photoUri, score) }
        }.sortedByDescending { it.score }.take(200)
    }
    val typed = query.trim().takeIf { search.isNumber && query.count(Char::isDigit) >= 3 }

    LazyColumn(
        Modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp, start = 12.dp, end = 12.dp),
    ) {
        item(key = "title") { BackTitle(str(R.string.new_message), onBack, Modifier.padding(horizontal = 4.dp)) }
        item(key = "to") {
            GlassTextField(
                query,
                { query = it },
                str(R.string.to_hint),
                Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { typed?.let(onPick) }),
                focusRequester = focus,
            )
        }
        if (typed != null) {
            item(key = "typed") {
                ContactRow(
                    str(R.string.write_to, numbers.format(typed)),
                    onClick = { onPick(typed) },
                    trailing = { Icon(styled(Icons.AutoMirrored.Rounded.Send), null, tint = p.accentFg) },
                )
            }
        }
        if (picks.isNotEmpty()) {
            item(key = "contacts") { SectionLabel(str(R.string.contacts_section), Modifier.padding(start = 12.dp, top = 12.dp)) }
        }
        items(picks, key = { "${it.name}|${it.number}" }) { pick ->
            ContactRow(pick.name, onClick = { onPick(pick.number) }, subtitle = numbers.format(pick.number), photoUri = pick.photoUri)
        }
    }
}
