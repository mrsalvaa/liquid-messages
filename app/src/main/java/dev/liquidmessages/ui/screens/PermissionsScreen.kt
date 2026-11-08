// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidmessages.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.liquidmessages.R
import dev.liquidmessages.ui.glass.GlassButton
import dev.liquidmessages.ui.glass.GlassCard
import dev.liquidmessages.ui.glass.LocalGlass
import dev.liquidmessages.util.str

/**
 * Antes de pedir los permisos, qué se lee y para qué. Si ya se negaron (el sistema no vuelve a
 * preguntar), el botón lleva a los ajustes de la app.
 */
@Composable
fun PermissionsScreen(padding: PaddingValues, denied: Boolean, onAllow: () -> Unit) {
    val p = LocalGlass.current
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = padding.calculateTopPadding() + 40.dp, bottom = padding.calculateBottomPadding(), start = 20.dp, end = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(str(R.string.permissions_title), Modifier.semantics { heading() }, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        GlassCard {
            Text(str(R.string.permissions_text), fontSize = 16.sp, color = p.content, lineHeight = 22.sp)
            if (denied) {
                Text(str(R.string.permissions_denied), Modifier.padding(top = 12.dp), fontSize = 14.sp, color = p.contentSecondary)
            }
        }
        if (denied) {
            GlassButton(str(R.string.permissions_settings), {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }, Modifier.fillMaxWidth(), prominent = true)
        } else {
            GlassButton(str(R.string.permissions_allow), onAllow, Modifier.fillMaxWidth(), prominent = true)
        }
    }
}
