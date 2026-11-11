// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidmessages.notify

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Icon

/**
 * El avatar de quien escribe para las notificaciones, cuando no tiene foto: el de la ficha de
 * Contactos, un círculo de cristal con borde fino y la persona en blanco, centrada. Se dibuja en un
 * mapa de bits porque el sistema no sabe dibujar Compose. [name] queda por si se suma color.
 */
object NotificationAvatar {
    private const val SIZE = 192

    fun icon(context: Context, name: String): Icon = Icon.createWithBitmap(bitmap(context, name))

    fun bitmap(context: Context, name: String): Bitmap {
        val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val s = SIZE.toFloat()
        val c = s / 2
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Cristal casi transparente, un poco más claro arriba (como en la ficha de Contactos). En
        // claro lleva un velo gris para que no desaparezca sobre la tarjeta blanca del aviso.
        val top = if (dark) 0x2EFFFFFF else 0xA67C8296.toInt()
        val bottom = if (dark) 0x14FFFFFF else 0x8C7C8296.toInt()
        paint.shader = LinearGradient(0f, 0f, 0f, s, top, bottom, Shader.TileMode.CLAMP)
        canvas.drawCircle(c, c, c, paint)

        // Borde fino de cristal: más claro arriba.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = s * 0.014f
        paint.shader = LinearGradient(0f, 0f, 0f, s, 0xB3FFFFFF.toInt(), 0x4DFFFFFF, Shader.TileMode.CLAMP)
        canvas.drawCircle(c, c, c - paint.strokeWidth / 2, paint)

        // La persona en blanco: cabeza redonda y, separado, el cuerpo como una lente (cúpula arriba
        // y curva suave abajo), todo dentro del círculo.
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = if (dark) Color.WHITE else 0xFFFAFBFD.toInt()
        paint.setShadowLayer(s * 0.02f, 0f, s * 0.008f, 0x26000000)
        canvas.drawCircle(c, s * 0.385f, s * 0.175f, paint)
        val left = s * 0.19f
        val right = s * 0.81f
        val waist = s * 0.82f
        val body = Path().apply {
            moveTo(left, waist)
            // Cúpula de los hombros.
            arcTo(RectF(left, s * 0.635f, right, waist + (waist - s * 0.635f)), 180f, 180f, false)
            // Curva de abajo, más plana.
            arcTo(RectF(left, waist - s * 0.09f, right, waist + s * 0.09f), 0f, 180f, false)
            close()
        }
        canvas.drawPath(body, paint)
        paint.clearShadowLayer()
        return bmp
    }

}
