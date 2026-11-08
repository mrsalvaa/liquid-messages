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

/**
 * El texto de un mensaje con sus enlaces, emails y teléfonos subrayados. Tocarlos no abre nada
 * directamente: llama a [onLink], que decide (un enlace web pide confirmación con su dominio).
 */
@Composable
fun MessageText(text: String, color: Color, onLink: (Links.Link) -> Unit, modifier: Modifier = Modifier) {
    val links = remember(text) { Links.find(text) }
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
    Text(styled, modifier, color = color, fontSize = 16.sp, lineHeight = 21.sp)
}
