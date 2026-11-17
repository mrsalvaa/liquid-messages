// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidmessages.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import dev.liquidmessages.core.text.Links
import dev.liquidmessages.core.text.NumberNames

/**
 * El texto de un mensaje con sus enlaces, emails y teléfonos subrayados. Tocarlos no abre nada
 * directamente: llama a [onLink], que decide (un enlace web pide confirmación con su dominio).
 */
@Composable
fun MessageText(
    text: String,
    color: Color,
    onLink: (Links.Link) -> Unit,
    modifier: Modifier = Modifier,
    fontSize: androidx.compose.ui.unit.TextUnit = 16.sp,
    /** Nombre del contacto de un número, para mostrarlo en su lugar (tocarlo sigue llamando al número). */
    nameOf: ((String) -> String?)? = null,
    /** Cambia cuando cambia la agenda, para volver a buscar los nombres. */
    namesKey: Any? = null,
) {
    val named = remember(text, namesKey, nameOf != null) { nameOf?.let { NumberNames.replace(text, it) } }
    @Suppress("NAME_SHADOWING")
    val text = named?.text ?: text
    val links = remember(text, named) {
        val names = named?.names.orEmpty().map { Links.Link(it.range, it.number, Links.Kind.PHONE) }
        (Links.find(text).filter { l -> names.none { it.range.first <= l.range.last && l.range.first <= it.range.last } } + names).sortedBy { it.range.first }
    }
    val latest by rememberUpdatedState(onLink)
    val styled = remember(text, color, links) {
        buildAnnotatedString {
            append(text)
            val style = TextLinkStyles(SpanStyle(color = color, textDecoration = TextDecoration.Underline))
            for (l in links) {
                addLink(LinkAnnotation.Clickable(l.target, style) { latest(l) }, l.range.first, l.range.last + 1)
            }
        }
    }
    Text(styled, modifier, color = color, fontSize = fontSize, lineHeight = fontSize * 1.3f)
}
