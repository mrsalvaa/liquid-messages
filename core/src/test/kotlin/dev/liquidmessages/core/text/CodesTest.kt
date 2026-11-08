// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidmessages.core.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CodesTest {
    @Test
    fun codigosComunes() {
        assertEquals("482913", Codes.find("Tu código de verificación es 482913. No lo compartas."))
        assertEquals("4829", Codes.find("Tu clave de acceso: 4829"))
        assertEquals("13291377", Codes.find("Código 13291377 para ingresar"))
        assertEquals("482913", Codes.find("Your verification code is 482913"))
        assertEquals("482913", Codes.find("482913 es tu código de Ejemplo"))
        assertEquals("482913", Codes.find("PIN: 482913"))
    }

    @Test
    fun conPrefijoOSeparado() {
        assertEquals("482913", Codes.find("G-482913 es tu código de verificación"))
        assertEquals("482913", Codes.find("Tu código es 482 913"))
        assertEquals("482913", Codes.find("Tu código es 482-913"))
    }

    @Test
    fun sinPalabraClaveNoHayCodigo() {
        assertNull(Codes.find("Te paso 4829 cuando llegue"))
        assertNull(Codes.find("Nos vemos a las 18:30 en la puerta 4"))
    }

    @Test
    fun montosFechasHorasYTelefonosNo() {
        assertNull(Codes.find("Tu clave vence el 12/05/2026"))
        assertNull(Codes.find("Código de seguridad enviado. Saldo: $1234"))
        assertNull(Codes.find("Código de pago: 1.234,50"))
        assertNull(Codes.find("Si no pediste el código, llamá al 0800 133 1234"))
        assertNull(Codes.find("Tu código llega a las 10:30"))
        assertNull(Codes.find("Descuento del 2500% con tu código"))
    }

    @Test
    fun conTelefonoYCodigoEligeElCodigo() {
        assertEquals("482913", Codes.find("Tu código es 482913. Si no fuiste vos, llamá al 0800 133 1234."))
    }

    @Test
    fun elMasCercanoDespuesDeLaPalabra() {
        assertEquals("482913", Codes.find("Pedido 5521 listo. Código de retiro: 482913"))
    }

    @Test
    fun anioSoloDesempata() {
        assertEquals("4829", Codes.find("Código 2026: tu PIN es 4829"))
        assertEquals("2024", Codes.find("Tu código es 2024"))
    }

    @Test
    fun emojisNoCorrenLasPosiciones() {
        assertEquals("482913", Codes.find("👍️👍️ Tu código es 482913"))
        assertEquals("482913", Codes.find("Tu código es 482913"))
    }
}
