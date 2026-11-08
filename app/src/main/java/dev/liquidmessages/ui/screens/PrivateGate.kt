// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidmessages.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.liquidmessages.R
import dev.liquidmessages.security.AppLock
import dev.liquidmessages.ui.glass.GlassButton
import dev.liquidmessages.ui.glass.LocalGlass
import dev.liquidmessages.ui.glass.styled
import dev.liquidmessages.util.str

/**
 * Muestra [content] solo con las privadas desbloqueadas; si no, un candado que pide la huella o el
 * PIN del celular (al aparecer y con el botón).
 */
@Composable
fun PrivateGate(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val open by AppLock.privateOpen.collectAsStateWithLifecycle()
    if (open) {
        content()
        return
    }
    val activity = androidx.activity.compose.LocalActivity.current as? androidx.activity.ComponentActivity
    val p = LocalGlass.current
    LaunchedEffect(Unit) { activity?.let { AppLock.openPrivate(it) } }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(styled(Icons.Rounded.Lock), null, Modifier.size(48.dp), tint = p.contentSecondary)
            Text(str(R.string.private_locked), fontSize = 18.sp, color = p.content)
            GlassButton(str(R.string.unlock), { activity?.let { AppLock.openPrivate(it) } }, Modifier.padding(top = 4.dp))
        }
    }
}
