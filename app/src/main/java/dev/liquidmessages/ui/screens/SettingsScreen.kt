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
import dev.liquidmessages.ui.components.SwitchRow
import dev.liquidmessages.ui.glass.GlassCard
import dev.liquidmessages.ui.glass.LocalGlass
import dev.liquidmessages.ui.glass.SectionLabel
import dev.liquidmessages.util.str

@Composable
fun SettingsScreen(padding: PaddingValues, onBack: () -> Unit) {
    val s by Stores.settings.state.collectAsStateWithLifecycle()
    val sims by Repo.sims.state.collectAsStateWithLifecycle()
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

        SectionLabel(str(R.string.writing))
        GlassCard {
            SwitchRow(str(R.string.simplify_accents), s.simplifyAccents, { v -> edit { it.copy(simplifyAccents = v) } }, subtitle = str(R.string.simplify_accents_hint))
        }

        SectionLabel(str(R.string.about))
        GlassCard {
            Text(str(R.string.version, BuildConfig.VERSION_NAME), fontSize = 15.sp, color = p.contentSecondary)
        }
    }
}
