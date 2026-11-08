// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidmessages.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.liquidmessages.R
import dev.liquidmessages.ui.glass.GlassButton
import dev.liquidmessages.ui.glass.GlassRoot
import dev.liquidmessages.ui.glass.LocalGlass
import dev.liquidmessages.ui.glass.styled
import dev.liquidmessages.util.str

/** Lo que se ve con la app bloqueada: nada de las conversaciones, solo el botón para desbloquear. */
@Composable
fun LockScreen(onUnlock: () -> Unit) {
    // Al aparecer, el sistema ya pide la huella o el PIN.
    LaunchedEffect(Unit) { onUnlock() }
    GlassRoot(animated = false) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(styled(Icons.Rounded.Lock), null, Modifier.size(56.dp), tint = LocalGlass.current.accentFg)
            Text(str(R.string.lock_title), Modifier.padding(top = 16.dp, bottom = 24.dp), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            GlassButton(str(R.string.lock_unlock), onUnlock, Modifier.fillMaxWidth(), prominent = true)
        }
    }
}
