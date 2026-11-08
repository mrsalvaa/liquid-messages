// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidmessages.core.sim

// Adaptado de Liquid Phone (core/sim/CallDecider.kt). La regla es la misma: nunca hay un cambio
// silencioso de chip. Cambia lo que se pide: un mensaje se contesta, por defecto, con el chip por
// el que siguió la conversación, y no hay emergencias ni códigos.

enum class SimStatus {
    IN_SERVICE,
    /** Fuera de servicio, solo emergencias o radio apagada. */
    NO_SERVICE,
    /** El sistema todavía no informó nada: no se asume que falte servicio. */
    UNKNOWN,
}

/**
 * Un chip activo, solo con lo que ya muestra el sistema. [slotIndex] es
 * SubscriptionInfo.getSimSlotIndex(): 0 es la ranura 1 ("SIM 1"), tanto física como eSIM.
 * [subscriptionId] es el id de suscripción que usa SmsManager: un número interno que el sistema
 * asigna en orden, no un identificador del chip ni de la línea.
 */
data class SimChip(
    val slotIndex: Int,
    val subscriptionId: Int,
    val displayName: String,
    val color: Int,
    val status: SimStatus = SimStatus.UNKNOWN,
) {
    /** El número de ranura que ve el usuario. */
    val slotNumber: Int get() = slotIndex + 1
}

/** Política para un mensaje sin chip elegido. Los valores se guardan en Ajustes (`simPolicy`). */
enum class SimPolicy(val value: Int) {
    /** El chip de la conversación (el del último mensaje); si no hay, el principal. */
    CONVERSATION(0),
    PRIMARY(1),
    ASK(2),
    SYSTEM_DEFAULT(3);

    companion object {
        /** Lo desconocido vuelve al valor por defecto: el chip de la conversación. */
        fun fromValue(value: Int): SimPolicy = entries.firstOrNull { it.value == value } ?: CONVERSATION
    }
}

/** Qué pidió el usuario. */
sealed interface SendTarget {
    /** El botón de enviar sin tocar el chip: decide la política. */
    data object UsePolicy : SendTarget

    /** El chip elegido a mano en el campo de escribir: pasa por encima de la política. */
    data class Slot(val slotIndex: Int) : SendTarget
}

/** Lo que se sabe del celu en el momento de enviar. */
data class SendContext(
    val chips: List<SimChip>,
    /** false si falta READ_PHONE_STATE: no se sabe qué chips hay. */
    val chipsKnown: Boolean = true,
    val airplaneMode: Boolean = false,
    /** true si la app puede enviar por sí misma (es la de SMS predeterminada y tiene SEND_SMS). */
    val canSend: Boolean = true,
    val policy: SimPolicy = SimPolicy.CONVERSATION,
    /** Ranura del chip principal, elegida en Ajustes (`primarySimSlot`): 0 = SIM 1, 1 = SIM 2. */
    val primarySlot: Int = 0,
    /** Suscripción predeterminada de SMS del sistema; null si no hay o no se sabe. */
    val systemDefault: Int? = null,
    /** Suscripción del último mensaje de la conversación (recibido o enviado); null si no se sabe. */
    val conversationSubscription: Int? = null,
)

enum class AskReason {
    /** La política es preguntar siempre. */
    ALWAYS,

    /**
     * Tocaba el chip principal y no se puede usar (retirado, sin servicio o modo avión), o el único
     * chip activo no es el principal: "El chip principal no está. ¿Enviar con [otro]?".
     */
    PRIMARY_UNAVAILABLE,

    /** El chip que tocaba, que no es el principal, está pero sin servicio o en modo avión. */
    NO_SERVICE,

    /** El chip de la conversación (o el elegido) ya no está en el celu. */
    CHIP_GONE,

    /** Ranuras repetidas o inválidas, en el sistema o en el ajuste: no se adivina cuál es cuál. */
    AMBIGUOUS_SLOT,
}

sealed interface SendDecision {
    /** No hay destinatario o el texto está vacío. */
    data object Nothing : SendDecision

    /** La app no puede enviar: se le pasa el mensaje a la app de SMS del sistema, sin enviarlo. */
    data object HandToSystemApp : SendDecision

    /** Enviar sin elegir chip: decide el sistema (no se sabe qué chips hay). */
    data object SystemChooses : SendDecision

    data class Send(val chip: SimChip) : SendDecision

    /**
     * Preguntar. [options] son los chips que se pueden elegir; [suggested] es el que tocaba, si
     * sigue en el celu. [other] es el chip que se nombra en [AskReason.PRIMARY_UNAVAILABLE]: solo
     * si hay exactamente uno, porque con dos no hay a quién nombrar.
     */
    data class Ask(
        val options: List<SimChip>,
        val reason: AskReason,
        val suggested: SimChip? = null,
        val other: SimChip? = null,
    ) : SendDecision
}

object SmsDecider {
    /** Lo que se puede elegir en Ajustes: SIM 1 o SIM 2. */
    val VALID_PRIMARY_SLOTS = 0..1

    /**
     * [Send] solo sale con el chip exacto que corresponde al pedido y a la política; si ese chip no
     * se puede usar, se pregunta. Un mensaje nunca sale por un chip distinto sin que se vea.
     */
    fun decide(hasRecipient: Boolean, hasText: Boolean, target: SendTarget, context: SendContext): SendDecision {
        if (!hasRecipient || !hasText) return SendDecision.Nothing
        if (!context.canSend) return SendDecision.HandToSystemApp
        if (!context.chipsKnown) return SendDecision.SystemChooses

        val chips = context.chips
        if (chips.isEmpty()) return SendDecision.SystemChooses

        fun ask(reason: AskReason, suggested: SimChip? = null) = SendDecision.Ask(chips, reason, suggested)

        if (hasAmbiguousSlots(chips) || context.primarySlot !in VALID_PRIMARY_SLOTS) return ask(AskReason.AMBIGUOUS_SLOT)

        val primary = chips.firstOrNull { it.slotIndex == context.primarySlot }
        val primaryUnavailable = ask(AskReason.PRIMARY_UNAVAILABLE, primary)
            .copy(other = chips.filter { it != primary }.singleOrNull())

        // El único chip activo no es el principal: se confirma siempre, sea cual sea el pedido.
        if (chips.size == 1 && primary == null) return primaryUnavailable

        val wanted: SimChip = when (target) {
            is SendTarget.Slot -> chips.firstOrNull { it.slotIndex == target.slotIndex }
                ?: return if (target.slotIndex == context.primarySlot) primaryUnavailable else ask(AskReason.CHIP_GONE)
            SendTarget.UsePolicy -> when (context.policy) {
                SimPolicy.PRIMARY -> primary ?: return primaryUnavailable
                SimPolicy.ASK -> if (chips.size > 1) return ask(AskReason.ALWAYS, primary) else primary ?: return primaryUnavailable
                SimPolicy.SYSTEM_DEFAULT -> {
                    val default = context.systemDefault ?: return SendDecision.SystemChooses
                    chips.firstOrNull { it.subscriptionId == default } ?: return SendDecision.SystemChooses
                }
                SimPolicy.CONVERSATION -> {
                    val sub = context.conversationSubscription
                    if (sub == null) primary ?: return primaryUnavailable
                    else chips.firstOrNull { it.subscriptionId == sub } ?: return ask(AskReason.CHIP_GONE, primary)
                }
            }
        }

        return when {
            usable(wanted, context) -> SendDecision.Send(wanted)
            wanted == primary -> primaryUnavailable
            else -> ask(AskReason.NO_SERVICE, wanted)
        }
    }

    fun usable(chip: SimChip, context: SendContext): Boolean =
        chip.status != SimStatus.NO_SERVICE && !context.airplaneMode

    /** Ranuras inválidas o repetidas. La documentación avisa que el índice puede no ser único. */
    fun hasAmbiguousSlots(chips: List<SimChip>): Boolean =
        chips.any { it.slotIndex < 0 } || chips.map { it.slotIndex }.distinct().size != chips.size
}

/** País para los números sin prefijo, a partir de los países de los chips. Igual que en Phone. */
sealed interface SimCountry {
    data object Unknown : SimCountry
    data class One(val iso: String) : SimCountry

    /** Los chips son de países distintos: no se adivina, se le pregunta al usuario. */
    data class Conflict(val isos: List<String>) : SimCountry

    companion object {
        fun of(isos: List<String>): SimCountry {
            val known = isos.map { it.trim().uppercase() }.filter { it.length == 2 }.distinct().sorted()
            return when (known.size) {
                0 -> Unknown
                1 -> One(known[0])
                else -> Conflict(known)
            }
        }
    }
}
