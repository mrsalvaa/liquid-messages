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
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
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
import dev.liquidmessages.ui.glass.GlassButton
import dev.liquidmessages.ui.glass.GlassChip
import dev.liquidmessages.ui.glass.GlassIconButton
import dev.liquidmessages.ui.glass.GlassTextField
import dev.liquidmessages.ui.glass.LocalGlass
import dev.liquidmessages.ui.glass.SectionLabel
import dev.liquidmessages.ui.glass.styled
import dev.liquidmessages.util.str

/** Un número de un contacto, para elegir a quién escribir. */
private class Pick(val name: String, val number: String, val photoUri: String?, val score: Int)

/**
 * Elegir a quién escribir: los contactos con número que coinciden con lo escrito (uno por número),
 * o el número tal cual si se escribió uno. Tocar uno abre la conversación (la que ya exista, o una
 * nueva); el "+" lo suma a un grupo, que se abre con "Escribir" (los grupos van por MMS).
 */
@Composable
fun NewMessageScreen(padding: PaddingValues, onBack: () -> Unit, onPick: (List<String>) -> Unit) {
    val book by Repo.book.collectAsStateWithLifecycle()
    val numbers by Repo.numbers.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var group by rememberSaveable { mutableStateOf(listOf<String>()) }
    val isDefault by Repo.isDefault.collectAsStateWithLifecycle()
    fun choose(number: String) {
        if (group.isEmpty()) onPick(listOf(number)) else if (group.none { numbers.key(it) == numbers.key(number) }) group = group + number
        query = ""
    }
    // Un grupo es MMS: solo la app predeterminada puede enviarlo.
    val addButton: (@Composable (String) -> Unit)? = if (!isDefault) null else { number ->
        GlassIconButton(Icons.Rounded.GroupAdd, str(R.string.add_recipient), {
            if (group.none { numbers.key(it) == numbers.key(number) }) group = group + number
            query = ""
        }, size = 40.dp)
    }
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
                keyboardActions = KeyboardActions(onGo = { typed?.let(::choose) }),
                focusRequester = focus,
            )
        }
        if (group.isNotEmpty()) {
            item(key = "group") {
                FlowRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    group.forEach { n ->
                        val name = Repo.contactFor(n)?.displayName ?: numbers.format(n)
                        GlassChip("$name  ✕", true, { group = group - n })
                    }
                }
                GlassButton(str(R.string.write_group, group.size), { onPick(group) }, Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), icon = Icons.AutoMirrored.Rounded.Send, prominent = true)
            }
        }
        if (typed != null) {
            item(key = "typed") {
                // El número como título: así el avatar no toma iniciales de "Escribir a".
                ContactRow(
                    numbers.format(typed),
                    onClick = { choose(typed) },
                    subtitle = str(R.string.new_message),
                    trailing = { addButton?.invoke(typed) ?: Icon(styled(Icons.AutoMirrored.Rounded.Send), null, tint = p.accentFg) },
                )
            }
        }
        if (picks.isNotEmpty()) {
            item(key = "contacts") { SectionLabel(str(R.string.contacts_section), Modifier.padding(start = 12.dp, top = 12.dp)) }
        }
        items(picks, key = { "${it.name}|${it.number}" }) { pick ->
            ContactRow(pick.name, onClick = { choose(pick.number) }, subtitle = numbers.format(pick.number), photoUri = pick.photoUri, trailing = addButton?.let { b -> { b(pick.number) } })
        }
    }
}
