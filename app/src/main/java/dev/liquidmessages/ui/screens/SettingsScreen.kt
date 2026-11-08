// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidmessages.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxWidth
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.liquidmessages.BuildConfig
import dev.liquidmessages.R
import dev.liquidmessages.core.sim.SimPolicy
import dev.liquidmessages.data.AppSettings
import dev.liquidmessages.data.Repo
import dev.liquidmessages.data.Stores
import dev.liquidmessages.security.AppLock
import androidx.compose.material.icons.rounded.Close
import dev.liquidmessages.ui.components.SwitchRow
import dev.liquidmessages.ui.glass.GlassCard
import dev.liquidmessages.ui.glass.LocalGlass
import dev.liquidmessages.ui.glass.SectionLabel
import dev.liquidmessages.ui.glass.SettingRow
import dev.liquidmessages.util.str

@Composable
fun SettingsScreen(padding: PaddingValues, onBack: () -> Unit, onBlocked: () -> Unit, onTrash: () -> Unit) {
    val s by Stores.settings.state.collectAsStateWithLifecycle()
    val sims by Repo.sims.state.collectAsStateWithLifecycle()
    val isDefault by Repo.isDefault.collectAsStateWithLifecycle()
    val p = LocalGlass.current
    val edit: ((AppSettings) -> AppSettings) -> Unit = { Stores.settings.update(it) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding(), start = 16.dp, end = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BackTitle(str(R.string.settings), onBack)

        SectionLabel(str(R.string.appearance))
        AppearanceCard(s, edit)

        SectionLabel(str(R.string.look))
        GlassCard {
            Text(str(R.string.bubble_color), fontSize = 16.sp)
            ChipRow(listOf(0, 1), s.bubbleColor, { str(if (it == 0) R.string.bubble_gray else R.string.bubble_contact) }) { v -> edit { it.copy(bubbleColor = v) } }
            Text(str(R.string.text_size), Modifier.padding(top = 12.dp), fontSize = 16.sp)
            ChipRow(listOf(0, 1, 2), s.textSize, { str(listOf(R.string.size_small, R.string.size_normal, R.string.size_large)[it]) }) { v -> edit { it.copy(textSize = v) } }
            Text(str(R.string.time_shown), Modifier.padding(top = 12.dp), fontSize = 16.sp)
            ChipRow(listOf(0, 1), s.timeMode, { str(if (it == 0) R.string.time_always else R.string.time_on_tap) }) { v -> edit { it.copy(timeMode = v) } }
            SwitchRow(str(R.string.compact_list), s.compactList, { v -> edit { it.copy(compactList = v) } }, Modifier.padding(top = 4.dp), subtitle = str(R.string.compact_list_hint))
            Text(str(R.string.swipe_right), Modifier.padding(top = 12.dp), fontSize = 16.sp)
            ChipRow(SwipeAction.entries.map { it.value }, s.swipeRight, { str(SwipeAction.of(it).label) }) { v -> edit { it.copy(swipeRight = v) } }
            Text(str(R.string.swipe_left), Modifier.padding(top = 12.dp), fontSize = 16.sp)
            ChipRow(SwipeAction.entries.map { it.value }, s.swipeLeft, { str(SwipeAction.of(it).label) }) { v -> edit { it.copy(swipeLeft = v) } }
            Text(str(R.string.swipe_hint), Modifier.padding(top = 4.dp), fontSize = 13.sp, color = p.contentSecondary)
        }

        // Con un solo chip no hay nada que elegir.
        if (sims.multiple) {
            SectionLabel(str(R.string.sims))
            GlassCard {
                Text(str(R.string.primary_sim), fontSize = 16.sp)
                ChipRow(sims.chips.map { it.slotIndex }.filter { it in 0..1 }, s.primarySimSlot, { slot ->
                    val chip = sims.chips.firstOrNull { it.slotIndex == slot }
                    if (chip != null && chip.displayName.isNotBlank()) str(R.string.sim_label_named, slot + 1, chip.displayName) else str(R.string.sim_label, slot + 1)
                }) { slot -> edit { it.copy(primarySimSlot = slot) } }
                Text(str(R.string.primary_sim_hint), fontSize = 13.sp, color = p.contentSecondary)

                Text(str(R.string.sim_policy), Modifier.padding(top = 14.dp), fontSize = 16.sp)
                ChipRow(SimPolicy.entries, SimPolicy.fromValue(s.simPolicy), { policy ->
                    str(
                        when (policy) {
                            SimPolicy.CONVERSATION -> R.string.sim_policy_conversation
                            SimPolicy.PRIMARY -> R.string.sim_policy_primary
                            SimPolicy.ASK -> R.string.sim_policy_ask
                            SimPolicy.SYSTEM_DEFAULT -> R.string.sim_policy_system
                        },
                    )
                }) { policy -> edit { it.copy(simPolicy = policy.value) } }
                if (SimPolicy.fromValue(s.simPolicy) == SimPolicy.CONVERSATION) {
                    Text(str(R.string.sim_policy_conversation_hint), fontSize = 13.sp, color = p.contentSecondary)
                }
            }
        }

        SectionLabel(str(R.string.notifications))
        GlassCard {
            Text(str(R.string.notif_preview), fontSize = 16.sp)
            ChipRow(listOf(0, 1, 2), s.notifPreview, { str(listOf(R.string.preview_all, R.string.preview_sender, R.string.preview_none)[it]) }) { v -> edit { it.copy(notifPreview = v) } }
            Text(str(R.string.notif_preview_hint), fontSize = 13.sp, color = p.contentSecondary)
            SwitchRow(str(R.string.delivery_reports), s.deliveryReports, { v -> edit { it.copy(deliveryReports = v) } }, Modifier.padding(top = 8.dp), subtitle = str(R.string.delivery_reports_hint))
        }

        SectionLabel(str(R.string.security))
        GlassCard {
            val activity = androidx.activity.compose.LocalActivity.current as? androidx.activity.ComponentActivity
            val toast = LocalToast.current
            SwitchRow(str(R.string.app_lock), s.appLock, { on ->
                val a = activity ?: return@SwitchRow
                if (on && !AppLock.available(a)) {
                    toast(str(R.string.app_lock_unavailable))
                    return@SwitchRow
                }
                // Activar o desactivar también pide desbloquear: nadie lo cambia con el celular ajeno.
                AppLock.authenticate(a) { ok ->
                    if (ok) {
                        edit { it.copy(appLock = on) }
                        AppLock.unlockedNow()
                    }
                }
            }, subtitle = str(R.string.app_lock_hint))
        }

        SectionLabel(str(R.string.writing))
        GlassCard {
            val prompt = dev.liquidmessages.ui.glass.LocalPrompt.current
            Text(str(R.string.quick_replies), fontSize = 16.sp)
            s.quickReplies.forEach { r ->
                SettingRow(r, trailing = {
                    dev.liquidmessages.ui.glass.GlassIconButton(androidx.compose.material.icons.Icons.Rounded.Close, str(R.string.remove_quick_reply), {
                        edit { it.copy(quickReplies = it.quickReplies - r) }
                    }, size = 40.dp)
                })
            }
            SettingRow(str(R.string.add_quick_reply), onClick = {
                prompt(dev.liquidmessages.ui.glass.PromptSpec(str(R.string.add_quick_reply), str(R.string.add_recipient), str(R.string.quick_reply_hint)) { t ->
                    val v = t.trim()
                    if (v.isNotEmpty()) edit { it.copy(quickReplies = (it.quickReplies + v).distinct()) }
                })
            })
            SwitchRow(str(R.string.simplify_accents), s.simplifyAccents, { v -> edit { it.copy(simplifyAccents = v) } }, subtitle = str(R.string.simplify_accents_hint))
        }

        // La lista de bloqueados del sistema solo la puede ver la app de SMS o de teléfono predeterminada.
        if (isDefault) {
            SectionLabel(str(R.string.block))
            GlassCard { SettingRow(str(R.string.blocked_numbers), onClick = onBlocked) }
        }

        SectionLabel(str(R.string.backup))
        BackupCard(isDefault)

        SectionLabel(str(R.string.trash))
        GlassCard { SettingRow(str(R.string.trash), subtitle = str(R.string.trash_hint), onClick = onTrash) }

        SectionLabel(str(R.string.about))
        GlassCard {
            Text(str(R.string.version, BuildConfig.VERSION_NAME), fontSize = 15.sp, color = p.contentSecondary)
        }
    }
}

/**
 * Exportar e importar en el formato de "SMS Backup & Restore". El archivo se elige con el selector
 * del sistema (sin permiso de almacenamiento). Importar necesita ser la app predeterminada.
 */
@Composable
private fun BackupCard(isDefault: Boolean) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val dialog = dev.liquidmessages.ui.glass.LocalDialog.current
    val p = LocalGlass.current
    var status by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var busy by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    fun run(work: (dev.liquidmessages.sms.Backup, (Int) -> Unit) -> dev.liquidmessages.sms.Backup.Result?, done: (dev.liquidmessages.sms.Backup.Result) -> String) {
        busy = true
        scope.launch {
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { work(dev.liquidmessages.sms.Backup(context)) { n -> scope.launch { status = str(R.string.backup_progress, n) } } }.getOrNull()
            }
            busy = false
            status = null
            val text = result?.let(done) ?: str(R.string.backup_error)
            dialog(dev.liquidmessages.ui.glass.DialogSpec(str(R.string.backup)) { dismiss ->
                Text(text, color = LocalGlass.current.content, fontSize = 15.sp)
                androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 16.dp))
                dev.liquidmessages.ui.glass.GlassButton(str(R.string.ok), dismiss, Modifier.fillMaxWidth(), prominent = true)
            })
        }
    }

    val exportTo = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/xml")) { uri ->
        if (uri != null) run({ b, progress -> context.contentResolver.openOutputStream(uri)?.use { b.export(it, progress) } }) { r -> str(R.string.backup_exported, r.sms, r.mms) }
    }
    val importFrom = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) run({ b, progress -> context.contentResolver.openInputStream(uri)?.use { b.import(it, progress) } }) { r ->
            str(R.string.backup_imported, r.sms, r.mms, r.skipped) + if (r.failed > 0) " " + str(R.string.backup_failed_some, r.failed) else ""
        }
    }
    GlassCard {
        SettingRow(str(R.string.backup_export), subtitle = str(R.string.backup_export_hint), onClick = {
            if (!busy) exportTo.launch("mensajes-" + java.time.LocalDate.now() + ".xml")
        })
        SettingRow(
            str(R.string.backup_import),
            subtitle = str(if (isDefault) R.string.backup_import_hint else R.string.needs_default),
            onClick = { if (!busy && isDefault) importFrom.launch(arrayOf("text/xml", "application/xml", "*/*")) },
        )
        status?.let { Text(it, Modifier.padding(top = 8.dp), fontSize = 13.sp, color = p.contentSecondary) }
    }
}
