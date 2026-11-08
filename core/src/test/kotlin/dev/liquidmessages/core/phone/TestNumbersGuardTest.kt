// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidmessages.core.phone

import com.google.i18n.phonenumbers.PhoneNumberUtil
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Ningún test de :core usa un número argentino que pueda ser de alguien: el abonado empieza con 1 y
 * nunca con 15. Lo que esto no puede cumplir queda en [interior], con el motivo en DESIGN.md
 * ("Números de los tests").
 */
class TestNumbersGuardTest {
    private val util = PhoneNumberUtil.getInstance()

    /** libphonenumber solo da por válido el abonado que empieza con 2 a 8 fuera del área 11. */
    private val interior = setOf(
        "0351 15 234-5678", "+54 9 351 234-5678", "+543512345678", "0223 15 234-5678",
        "02966 15 42-3456", "+54 9 2966 42-3456", "+542966423456", "+54 9 2966 23-4567",
        "+543514210000",
    )

    private val tests = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }
        .resolve("core/src/test/kotlin")

    private val literals: List<String> = tests.walkTopDown()
        .filter { it.extension == "kt" && it.name != "TestNumbersGuardTest.kt" }
        .flatMap { Regex(""" "([^"\n]*)" """.trim()).findAll(it.readText()).map { m -> m.groupValues[1] } }
        .toList()

    /** Abonado, sin código de área, de un número argentino completo; null si no lo es. */
    private fun subscriber(raw: String): String? {
        if (raw.count(Char::isDigit) < 10) return null
        val parsed = try {
            util.parse(raw, "AR")
        } catch (e: Exception) {
            return null
        }
        if (parsed.countryCode != 54 || !util.isValidNumber(parsed)) return null
        val nsn = util.getNationalSignificantNumber(parsed)
        // En móviles, el 9 de adelante no es parte del área.
        val national = if (nsn.startsWith("9")) nsn.drop(1) else nsn
        val area = when {
            national.startsWith("11") -> 2
            national.startsWith("800") || national.startsWith("810") -> 3
            else -> return "interior"
        }
        return national.drop(area)
    }

    @Test
    fun losNumerosCompletosNoSonAsignables() {
        val checked = literals.mapNotNull { raw -> subscriber(raw)?.let { raw to it } }
        assertTrue(checked.size > 40, "la guardia tiene que recorrer números reales de los tests: ${checked.size}")
        for ((raw, sub) in checked) {
            if (raw in interior) continue
            assertTrue(sub != "interior" && sub.startsWith("1") && !sub.startsWith("15"), raw)
        }
    }

    @Test
    fun losLocalesSinAreaTampoco() {
        val local = Regex("""(?:15[ -]?)?\d{4}[ -]?\d{4}""")
        val t9Query = "22334455"
        for (raw in literals.filter { local.matches(it) && it != t9Query }) {
            val sub = raw.filter(Char::isDigit).let { if (it.length == 10) it.drop(2) else it }
            assertTrue(sub.startsWith("1") && !sub.startsWith("15"), raw)
        }
    }

    @Test
    fun laListaDeExcepcionesNoTieneDeMas() {
        assertEquals(emptyList(), interior.filter { it !in literals })
    }
}
