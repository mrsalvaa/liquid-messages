// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidmessages.core.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SmsDeciderTest {
    private val sim1 = SimChip(0, subscriptionId = 3, displayName = "Chip A", color = 0, status = SimStatus.IN_SERVICE)
    private val sim2 = SimChip(1, subscriptionId = 7, displayName = "Chip B", color = 0, status = SimStatus.IN_SERVICE)

    private fun decide(target: SendTarget = SendTarget.UsePolicy, context: SendContext) =
        SmsDecider.decide(hasRecipient = true, hasText = true, target = target, context = context)

    @Test
    fun sinDestinatarioOTextoNoHaceNada() {
        val c = SendContext(listOf(sim1, sim2))
        assertEquals(SendDecision.Nothing, SmsDecider.decide(false, true, SendTarget.UsePolicy, c))
        assertEquals(SendDecision.Nothing, SmsDecider.decide(true, false, SendTarget.UsePolicy, c))
    }

    @Test
    fun sinPoderEnviarSePasaALaAppDelSistema() {
        assertEquals(SendDecision.HandToSystemApp, decide(context = SendContext(listOf(sim1), canSend = false)))
    }

    @Test
    fun sinSaberLosChipsDecideElSistema() {
        assertEquals(SendDecision.SystemChooses, decide(context = SendContext(emptyList(), chipsKnown = false)))
        assertEquals(SendDecision.SystemChooses, decide(context = SendContext(emptyList())))
    }

    @Test
    fun contestaConElChipDeLaConversacion() {
        val d = decide(context = SendContext(listOf(sim1, sim2), conversationSubscription = 7))
        assertEquals(SendDecision.Send(sim2), d)
    }

    @Test
    fun conversacionNuevaVaPorElPrincipal() {
        assertEquals(SendDecision.Send(sim2), decide(context = SendContext(listOf(sim1, sim2), primarySlot = 1)))
    }

    @Test
    fun siElChipDeLaConversacionYaNoEstaPregunta() {
        val d = decide(context = SendContext(listOf(sim1, sim2), conversationSubscription = 99))
        assertIs<SendDecision.Ask>(d)
        assertEquals(AskReason.CHIP_GONE, d.reason)
        assertEquals(sim1, d.suggested)
    }

    @Test
    fun elegirUnChipALaManoGanaALaPolitica() {
        val d = decide(SendTarget.Slot(0), SendContext(listOf(sim1, sim2), conversationSubscription = 7))
        assertEquals(SendDecision.Send(sim1), d)
    }

    @Test
    fun principalSinServicioNombraAlOtro() {
        val down = sim1.copy(status = SimStatus.NO_SERVICE)
        val d = decide(context = SendContext(listOf(down, sim2), policy = SimPolicy.PRIMARY))
        assertIs<SendDecision.Ask>(d)
        assertEquals(AskReason.PRIMARY_UNAVAILABLE, d.reason)
        assertEquals(sim2, d.other)
    }

    @Test
    fun unicoChipQueNoEsElPrincipalSeConfirma() {
        val d = decide(context = SendContext(listOf(sim2), primarySlot = 0))
        assertIs<SendDecision.Ask>(d)
        assertEquals(AskReason.PRIMARY_UNAVAILABLE, d.reason)
    }

    @Test
    fun ranurasRepetidasPreguntan() {
        val d = decide(context = SendContext(listOf(sim1, sim2.copy(slotIndex = 0))))
        assertIs<SendDecision.Ask>(d)
        assertEquals(AskReason.AMBIGUOUS_SLOT, d.reason)
        val bad = decide(context = SendContext(listOf(sim1, sim2), primarySlot = 5))
        assertEquals(AskReason.AMBIGUOUS_SLOT, (bad as SendDecision.Ask).reason)
    }

    @Test
    fun predeterminadoDelSistema() {
        assertEquals(SendDecision.Send(sim2), decide(context = SendContext(listOf(sim1, sim2), policy = SimPolicy.SYSTEM_DEFAULT, systemDefault = 7)))
        assertEquals(SendDecision.SystemChooses, decide(context = SendContext(listOf(sim1, sim2), policy = SimPolicy.SYSTEM_DEFAULT)))
    }

    @Test
    fun politicaDesconocidaVuelveALaConversacion() {
        assertEquals(SimPolicy.CONVERSATION, SimPolicy.fromValue(42))
    }

    /**
     * La regla central, en todas las combinaciones: un [SendDecision.Send] sale con el chip exacto
     * que tocaba, y ese chip se puede usar. Si no, se pregunta.
     */
    @Test
    fun nuncaHayUnCambioSilenciosoDeChip() {
        val statuses = SimStatus.entries
        val chipSets = buildList {
            for (s1 in statuses) for (s2 in statuses) {
                add(listOf(sim1.copy(status = s1), sim2.copy(status = s2)))
                add(listOf(sim1.copy(status = s1)))
                add(listOf(sim2.copy(status = s2)))
            }
        }
        var sends = 0
        for (chips in chipSets) for (policy in SimPolicy.entries) for (primary in 0..1)
            for (conv in listOf(null, 3, 7, 99)) for (default in listOf(null, 3, 7)) for (airplane in listOf(false, true))
                for (target in listOf(SendTarget.UsePolicy, SendTarget.Slot(0), SendTarget.Slot(1))) {
                    val c = SendContext(chips, airplaneMode = airplane, policy = policy, primarySlot = primary, systemDefault = default, conversationSubscription = conv)
                    val d = decide(target, c)
                    if (d !is SendDecision.Send) continue
                    sends++
                    val expected = when (target) {
                        is SendTarget.Slot -> chips.first { it.slotIndex == target.slotIndex }
                        SendTarget.UsePolicy -> when (policy) {
                            SimPolicy.CONVERSATION -> if (conv == null) chips.first { it.slotIndex == primary } else chips.first { it.subscriptionId == conv }
                            SimPolicy.PRIMARY, SimPolicy.ASK -> chips.first { it.slotIndex == primary }
                            SimPolicy.SYSTEM_DEFAULT -> chips.first { it.subscriptionId == default }
                        }
                    }
                    assertEquals(expected, d.chip, "$target $policy primario=$primary conv=$conv")
                    assertTrue(SmsDecider.usable(d.chip, c))
                    // Con un solo chip que no es el principal, nunca se envía sin confirmar.
                    assertTrue(chips.size > 1 || chips[0].slotIndex == primary)
                }
        assertTrue(sends > 100, "la prueba tiene que recorrer envíos: $sends")
    }
}
