// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidmessages

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.liquidmessages.data.Repo
import dev.liquidmessages.data.Stores
import dev.liquidmessages.ui.glass.ActionSheetHost
import dev.liquidmessages.ui.glass.DialogHost
import dev.liquidmessages.ui.glass.DialogSpec
import dev.liquidmessages.ui.glass.GlassButton
import dev.liquidmessages.ui.glass.GlassIconButton
import dev.liquidmessages.ui.glass.GlassRoot
import dev.liquidmessages.ui.glass.GlassTheme
import dev.liquidmessages.ui.glass.LocalActionSheet
import dev.liquidmessages.ui.glass.LocalDialog
import dev.liquidmessages.ui.glass.LocalGlass
import dev.liquidmessages.ui.glass.LocalPrompt
import dev.liquidmessages.ui.glass.LocalReducedMotion
import dev.liquidmessages.ui.glass.PromptHost
import dev.liquidmessages.ui.glass.PromptSpec
import dev.liquidmessages.ui.glass.SheetSpec
import dev.liquidmessages.ui.glass.isDarkTheme
import dev.liquidmessages.ui.screens.ConversationsScreen
import dev.liquidmessages.ui.screens.LocalToast
import dev.liquidmessages.ui.screens.NewMessageScreen
import dev.liquidmessages.ui.screens.PermissionsScreen
import dev.liquidmessages.ui.screens.SettingsScreen
import dev.liquidmessages.ui.screens.ThreadScreen
import dev.liquidmessages.ui.screens.ToastHost
import dev.liquidmessages.util.preferHighestRefreshRate
import dev.liquidmessages.util.str
import java.util.Locale

/** Pantallas que se abren encima de la lista. */
sealed interface Overlay {
    /** Una conversación, por su hilo o, si es nueva, por las direcciones. */
    data class Thread(val threadId: Long?, val addresses: List<String>, val text: String = "") : Overlay
    data object New : Overlay
    data object Archived : Overlay
    data object Settings : Overlay
}

/** Sin estos no hay nada para mostrar; los otros dos solo agregan nombres y chips. */
private val PERMISSIONS = arrayOf(Manifest.permission.READ_SMS, Manifest.permission.READ_CONTACTS, Manifest.permission.READ_PHONE_STATE)

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        Repo.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        preferHighestRefreshRate()
        setContent {
            val settings by Stores.settings.state.collectAsStateWithLifecycle()
            SystemBarsFor(isDarkTheme(settings.themeMode))
            GlassTheme(settings.glassStyle) {
                MessagesUi(settings.animatedBackground)
            }
        }
    }
}

@Composable
private fun MessagesUi(animated: Boolean) {
    val stack = remember { mutableStateListOf<Overlay>() }
    val holder = rememberSaveableStateHolder()
    var sheet by remember { mutableStateOf<SheetSpec?>(null) }
    var prompt by remember { mutableStateOf<PromptSpec?>(null) }
    var dialog by remember { mutableStateOf<DialogSpec?>(null) }
    var toast by remember { mutableStateOf<Pair<String, Long>?>(null) }
    val reduced = LocalReducedMotion.current
    val tick by Repo.permissionTick.collectAsStateWithLifecycle()
    val conflict by Repo.countryConflict.collectAsStateWithLifecycle()
    val activity = LocalActivity.current

    val canRead = remember(tick) { Repo.granted(Manifest.permission.READ_SMS) }
    var denied by rememberSaveable { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        Repo.start()
        // Negado y sin poder volver a preguntar: el botón pasa a llevar a los ajustes de la app.
        denied = !Repo.granted(Manifest.permission.READ_SMS) &&
            activity?.shouldShowRequestPermissionRationale(Manifest.permission.READ_SMS) == false
    }

    fun push(o: Overlay) {
        stack += o
    }
    fun pop() {
        if (stack.isEmpty()) return
        val key = "${stack.lastIndex}:${stack.last()}"
        stack.removeAt(stack.lastIndex)
        holder.removeState(key)
    }
    BackHandler(enabled = stack.isNotEmpty() && sheet == null && prompt == null && dialog == null) { pop() }

    // Sin el teclado: cada pantalla que escribe lo suma con imePadding, una sola vez.
    val insets = WindowInsets.safeDrawing.exclude(WindowInsets.ime).asPaddingValues()
    val dir = LocalLayoutDirection.current
    val padding = PaddingValues(
        top = insets.calculateTopPadding(),
        bottom = insets.calculateBottomPadding() + 8.dp,
        start = insets.calculateStartPadding(dir),
        end = insets.calculateEndPadding(dir),
    )

    CompositionLocalProvider(
        LocalActionSheet provides { sheet = it },
        LocalPrompt provides { prompt = it },
        LocalDialog provides { dialog = it },
        LocalToast provides { toast = it to System.nanoTime() },
    ) {
        // Dos chips de países distintos: no se adivina el país de los números sin prefijo.
        LaunchedEffect(conflict) {
            if (conflict.isNotEmpty()) {
                dialog = DialogSpec(str(R.string.country_conflict_title)) { dismiss ->
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(str(R.string.country_conflict_text), color = LocalGlass.current.content, fontSize = 14.sp)
                        conflict.forEach { iso ->
                            GlassButton(Locale("", iso).displayCountry, { Repo.setCountry(iso); dismiss() }, Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
        GlassRoot(
            animated = animated,
            floating = {
                AnimatedVisibility(
                    canRead && stack.isEmpty(),
                    modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp),
                    enter = fadeIn() + if (reduced) fadeIn() else scaleIn(initialScale = 0.8f),
                    exit = fadeOut() + if (reduced) fadeOut() else scaleOut(targetScale = 0.8f),
                ) {
                    GlassIconButton(Icons.Rounded.Edit, str(R.string.new_message), { push(Overlay.New) }, size = 60.dp, prominent = true)
                }
                ToastHost(toast) { toast = null }
                ActionSheetHost(sheet) { sheet = null }
                PromptHost(prompt) { prompt = null }
                DialogHost(dialog) { dialog = null }
            },
        ) {
            if (!canRead) {
                PermissionsScreen(padding, denied) { request.launch(PERMISSIONS) }
                return@GlassRoot
            }
            val top = stack.lastOrNull()
            val topKey = if (top == null) "none" else "${stack.lastIndex}:$top"
            AnimatedContent(
                targetState = top to topKey,
                transitionSpec = {
                    if (reduced) {
                        fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                    } else {
                        (fadeIn(tween(200)) + scaleIn(tween(220), initialScale = 0.97f)) togetherWith fadeOut(tween(120))
                    }
                },
                contentKey = { it.second },
                label = "overlay",
            ) { (current, key) ->
                holder.SaveableStateProvider(key) {
                    Box(Modifier.fillMaxSize()) {
                        when (current) {
                            null -> ConversationsScreen(
                                padding,
                                archived = false,
                                onOpen = { push(Overlay.Thread(it.threadId, it.addresses)) },
                                onSettings = { push(Overlay.Settings) },
                                onArchived = { push(Overlay.Archived) },
                                onBack = {},
                            )
                            Overlay.Archived -> ConversationsScreen(
                                padding,
                                archived = true,
                                onOpen = { push(Overlay.Thread(it.threadId, it.addresses)) },
                                onSettings = {},
                                onArchived = {},
                                onBack = ::pop,
                            )
                            is Overlay.Thread -> ThreadScreen(current.threadId, current.addresses, current.text, padding, ::pop)
                            Overlay.New -> NewMessageScreen(padding, ::pop) { address ->
                                // La pantalla de elegir se reemplaza por la conversación: volver lleva a la lista.
                                pop()
                                push(Overlay.Thread(null, listOf(address)))
                            }
                            Overlay.Settings -> SettingsScreen(padding, ::pop)
                        }
                    }
                }
            }
        }
    }
}

/** Iconos de las barras del sistema según el tema (claros sobre fondo oscuro). */
@Composable
fun SystemBarsFor(dark: Boolean) {
    val activity = LocalActivity.current as? ComponentActivity ?: return
    LaunchedEffect(dark) {
        val style = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark }
        activity.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }
}
