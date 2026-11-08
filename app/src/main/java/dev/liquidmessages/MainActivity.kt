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
import dev.liquidmessages.security.AppLock
import dev.liquidmessages.ui.screens.LockScreen
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
import dev.liquidmessages.ui.screens.BlockedScreen
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
    data class Thread(val threadId: Long?, val addresses: List<String>, val text: String = "", val image: android.net.Uri? = null) : Overlay
    /** Elegir a quién escribir; [text] e [image] vienen de compartir desde otra app. */
    data class New(val text: String = "", val image: android.net.Uri? = null) : Overlay
    data object Blocked : Overlay
    data object Trash : Overlay
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

    override fun onStart() {
        super.onStart()
        AppLock.returned()
    }

    override fun onStop() {
        super.onStop()
        AppLock.left()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    /**
     * Lo que llega de otras apps o de una notificación queda listo para escribir: nunca se envía
     * solo. "smsto:" y "sms:" pueden traer varios números separados por coma o punto y coma, y el
     * texto en "sms_body" o en "?body=".
     */
    private fun handle(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            "dev.liquidmessages.OPEN_THREAD" -> Repo.requestCompose(
                Repo.Compose(
                    addresses = listOfNotNull(intent.getStringExtra(dev.liquidmessages.notify.Notifier.EXTRA_ADDRESS)?.takeIf { it.isNotBlank() }),
                    body = intent.getStringExtra(dev.liquidmessages.notify.Notifier.EXTRA_TEXT).orEmpty(),
                    threadId = intent.getLongExtra(dev.liquidmessages.notify.Notifier.EXTRA_THREAD, -1).takeIf { it >= 0 },
                ),
            )
            Intent.ACTION_SENDTO, Intent.ACTION_VIEW -> {
                val data = intent.data ?: return
                if (data.scheme !in setOf("sms", "smsto", "mms", "mmsto")) return
                val raw = data.schemeSpecificPart.orEmpty()
                val addresses = raw.substringBefore('?').split(',', ';').map { android.net.Uri.decode(it).trim() }.filter { it.isNotEmpty() }
                val body = intent.getStringExtra("sms_body") ?: raw.substringAfter("?body=", "").takeIf { it.isNotEmpty() }?.let(android.net.Uri::decode).orEmpty()
                Repo.requestCompose(Repo.Compose(addresses, body))
            }
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                val image = if (intent.type?.startsWith("image/") == true) {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
                } else {
                    null
                }
                Repo.requestCompose(Repo.Compose(body = text, image = image))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handle(intent)
        preferHighestRefreshRate()
        setContent {
            val settings by Stores.settings.state.collectAsStateWithLifecycle()
            val locked by AppLock.locked.collectAsStateWithLifecycle()
            SystemBarsFor(isDarkTheme(settings.themeMode))
            // Con el bloqueo activo, la vista previa de apps recientes no muestra las conversaciones.
            LaunchedEffect(settings.appLock) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(!settings.appLock)
            }
            GlassTheme(settings.glassStyle) {
                if (settings.appLock && locked) LockScreen { AppLock.authenticate(this) } else MessagesUi(settings.animatedBackground)
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
    var toast by remember { mutableStateOf<dev.liquidmessages.ui.screens.ToastMsg?>(null) }
    var selection by remember { mutableStateOf<dev.liquidmessages.ui.screens.SelectionBottom?>(null) }
    val reduced = LocalReducedMotion.current
    val tick by Repo.permissionTick.collectAsStateWithLifecycle()
    val conflict by Repo.countryConflict.collectAsStateWithLifecycle()
    val activity = LocalActivity.current

    val canRead = remember(tick) { Repo.granted(Manifest.permission.READ_SMS) }
    val pending by Repo.pendingCompose.collectAsStateWithLifecycle()
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

    // De otra app o de una notificación: se abre encima de la lista, lista para escribir.
    LaunchedEffect(pending, canRead) {
        val c = pending ?: return@LaunchedEffect
        if (!canRead) return@LaunchedEffect
        stack.clear()
        if (c.threadId != null || c.addresses.isNotEmpty()) push(Overlay.Thread(c.threadId, c.addresses, c.body, c.image))
        else push(Overlay.New(c.body, c.image))
        Repo.requestCompose(null)
    }

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
        LocalToast provides { toast = dev.liquidmessages.ui.screens.ToastMsg(it) },
        dev.liquidmessages.ui.screens.LocalUndoToast provides { text, undo -> toast = dev.liquidmessages.ui.screens.ToastMsg(text, undo) },
        dev.liquidmessages.ui.screens.LocalSelectionBottom provides { selection = it },
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
                // Selección múltiple: arriba la cantidad, abajo las acciones (como en Contacts y Recorder).
                var lastSelection by remember { mutableStateOf(selection) }
                if (selection != null) lastSelection = selection
                AnimatedVisibility(
                    selection != null && stack.isEmpty(),
                    modifier = Modifier.align(Alignment.BottomCenter),
                    enter = fadeIn() + if (reduced) fadeIn() else androidx.compose.animation.slideInVertically { it / 2 },
                    exit = fadeOut() + if (reduced) fadeOut() else androidx.compose.animation.slideOutVertically { it / 2 },
                ) { lastSelection?.let { dev.liquidmessages.ui.screens.SelectionActions(it, flat = false) } }
                AnimatedVisibility(
                    selection != null && stack.isEmpty(),
                    modifier = Modifier.align(Alignment.TopCenter),
                    enter = fadeIn() + if (reduced) fadeIn() else androidx.compose.animation.slideInVertically { -it / 2 },
                    exit = fadeOut() + if (reduced) fadeOut() else androidx.compose.animation.slideOutVertically { -it / 2 },
                ) { lastSelection?.let { dev.liquidmessages.ui.screens.SelectionTop(it, flat = false, insets.calculateTopPadding()) } }
                AnimatedVisibility(
                    canRead && stack.isEmpty() && selection == null,
                    modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp),
                    enter = fadeIn() + if (reduced) fadeIn() else scaleIn(initialScale = 0.8f),
                    exit = fadeOut() + if (reduced) fadeOut() else scaleOut(targetScale = 0.8f),
                ) {
                    GlassIconButton(Icons.Rounded.Edit, str(R.string.new_message), { push(Overlay.New()) }, size = 60.dp, prominent = true)
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
                            is Overlay.Thread -> ThreadScreen(current.threadId, current.addresses, current.text, current.image, padding, ::pop)
                            is Overlay.New -> NewMessageScreen(padding, ::pop) { addresses ->
                                // La pantalla de elegir se reemplaza por la conversación: volver lleva a la lista.
                                pop()
                                push(Overlay.Thread(null, addresses, current.text, current.image))
                            }
                            Overlay.Settings -> SettingsScreen(padding, ::pop, onBlocked = { push(Overlay.Blocked) }, onTrash = { push(Overlay.Trash) })
                            Overlay.Trash -> dev.liquidmessages.ui.screens.TrashScreen(padding, ::pop)
                            Overlay.Blocked -> BlockedScreen(padding, ::pop)
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
