// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidmessages

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.liquidmessages.data.Stores
import dev.liquidmessages.ui.glass.GlassRoot
import dev.liquidmessages.ui.glass.GlassTheme
import dev.liquidmessages.ui.glass.LocalGlass
import dev.liquidmessages.ui.glass.ScreenTitle
import dev.liquidmessages.util.preferHighestRefreshRate
import dev.liquidmessages.util.str

/** Etapa 0: solo la estructura. Las pantallas llegan en la etapa 1. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        preferHighestRefreshRate()
        setContent {
            val s by Stores.settings.state.collectAsStateWithLifecycle()
            GlassTheme(s.glassStyle) {
                GlassRoot(animated = s.animatedBackground) {
                    Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp)) {
                        ScreenTitle(str(R.string.app_name))
                        Text(str(R.string.under_construction), fontSize = 15.sp, color = LocalGlass.current.contentSecondary)
                    }
                }
            }
        }
    }
}
