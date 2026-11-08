// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidmessages

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.provider.Telephony
import dev.liquidmessages.mms.MmsStore
import dev.liquidmessages.mms.MmsTransport
import dev.liquidmessages.mms.pdu.EncodedStringValue
import dev.liquidmessages.mms.pdu.NotificationInd
import dev.liquidmessages.mms.pdu.PduComposer
import dev.liquidmessages.mms.pdu.PduHeaders
import dev.liquidmessages.mms.pdu.PduParser
import dev.liquidmessages.mms.pdu.RetrieveConf
import dev.liquidmessages.mms.pdu.SendReq
import dev.liquidmessages.notify.Notifier
import dev.liquidmessages.sms.DefaultSms
import java.io.ByteArrayOutputStream

/**
 * Solo debug y profile, y **solo en un emulador**: el emulador no tiene servidor de MMS, así que
 * esto prueba lo que sí se puede sin él. Escribe MMS de prueba en el proveedor, con números de los
 * rangos reservados; en un celu real se niega, porque ahí están los mensajes de una persona.
 */
object DevMms {
    private fun isEmulator(): Boolean =
        Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish" || Build.PRODUCT.startsWith("sdk") || Build.FINGERPRINT.contains("emulator")

    private fun photo(color: Int): ByteArray {
        val b = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(color)
        c.drawCircle(320f, 240f, 150f, Paint().apply { this.color = Color.WHITE; isAntiAlias = true })
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
    }

    fun run(context: Context): String {
        if (!isEmulator()) return "negado: no es un emulador"
        val out = StringBuilder()

        // 1. Ida y vuelta: armar un SendReq y volver a leerlo.
        val img = MmsTransport.Image(photo(Color.rgb(30, 120, 200)))
        val req = SendReq().apply {
            from = EncodedStringValue(MmsStore.INSERT_ADDRESS_TOKEN)
            addTo(EncodedStringValue(FakeNumbers.number(0)))
            contentType = "application/vnd.wap.multipart.related".toByteArray()
            body = MmsTransport.body("Hola ñandú 👍", listOf(img))
        }
        val bytes = PduComposer(context, req).make()
        val back = bytes?.let { PduParser(it, true).parse() } as? SendReq
        val parts = back?.body?.let { b -> (0 until b.partsNum).map { String(b.getPart(it).contentType) } }.orEmpty()
        val text = back?.body?.let { b -> (0 until b.partsNum).map { b.getPart(it) }.firstOrNull { String(it.contentType) == "text/plain" }?.data?.toString(Charsets.UTF_8) }
        val image = back?.body?.let { b -> (0 until b.partsNum).map { b.getPart(it) }.firstOrNull { String(it.contentType) == "image/jpeg" }?.data }
        out.append("idaYVuelta=").append(back != null && text == "Hola ñandú 👍" && image?.contentEquals(img.bytes) == true).append(" partes=").append(parts)

        if (!DefaultSms.isDefault(context)) return out.append(" (sin rol: no se escribe nada)").toString()
        val store = MmsStore(context)
        val self = FakeNumbers.number(40) // "mi número" de prueba
        val ana = FakeNumbers.number(2)
        val bruno = FakeNumbers.number(6)

        // 2. Un aviso que no se puede bajar (no hay servidor): tiene que quedar con "Descargar".
        val ind = NotificationInd().apply {
            from = EncodedStringValue(ana)
            contentLocation = "http://mmsc.example/prueba-${System.currentTimeMillis()}".toByteArray()
            transactionId = "prueba-${System.nanoTime()}".toByteArray()
            messageClass = PduHeaders.MESSAGE_CLASS_PERSONAL_STR.toByteArray()
            messageSize = 1000
            expiry = 3600
        }
        val indBytes = PduComposer(context, ind).make()
        val parsed = indBytes?.let { PduParser(it, true).parse() } as? NotificationInd
        val pendingRow = parsed?.let { store.insertNotification(it, null) }
        out.append(" aviso=").append(pendingRow != null)
        pendingRow?.let { MmsTransport.download(context, it) }

        // 3. Un MMS bajado, a mí solo: foto y texto.
        val one = RetrieveConf().apply {
            from = EncodedStringValue(ana)
            addTo(EncodedStringValue(self))
            date = System.currentTimeMillis() / 1000
            contentType = "application/vnd.wap.multipart.related".toByteArray()
            body = MmsTransport.body("Mirá la foto (prueba)", listOf(MmsTransport.Image(photo(Color.rgb(220, 90, 60)))))
        }
        val r1 = store.insertRetrieved(one, null, null)
        out.append(" individual=").append(r1?.second?.size)

        // 4. Un MMS de grupo: Bruno me escribe a mí y a Ana. "Yo" no tiene que quedar como participante.
        val group = RetrieveConf().apply {
            from = EncodedStringValue(bruno)
            addTo(EncodedStringValue(self))
            addTo(EncodedStringValue(ana))
            date = System.currentTimeMillis() / 1000
            contentType = "application/vnd.wap.multipart.related".toByteArray()
            body = MmsTransport.body("Hola a los dos (prueba de grupo)", emptyList())
        }
        val r2 = store.insertRetrieved(group, null, null)
        val selfInGroup = r2?.second?.any { it.filter(Char::isDigit).takeLast(8) == self.filter(Char::isDigit).takeLast(8) }
        out.append(" grupo=").append(r2?.second?.size).append(" yoEnElGrupo=").append(selfInGroup)
        for (r in listOfNotNull(r1, r2)) {
            context.contentResolver.query(r.first, arrayOf(Telephony.Mms.THREAD_ID), null, null, null)?.use { c ->
                if (c.moveToFirst()) Notifier.incoming(context, c.getLong(0), r.second)
            }
        }
        return out.toString()
    }
}
