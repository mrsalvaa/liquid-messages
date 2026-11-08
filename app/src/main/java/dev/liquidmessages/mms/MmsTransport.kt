// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidmessages.mms

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.content.FileProvider
import dev.liquidmessages.R
import dev.liquidmessages.core.sim.SimChip
import dev.liquidmessages.mms.pdu.CharacterSets
import dev.liquidmessages.mms.pdu.EncodedStringValue
import dev.liquidmessages.mms.pdu.NotifyRespInd
import dev.liquidmessages.mms.pdu.PduBody
import dev.liquidmessages.mms.pdu.PduComposer
import dev.liquidmessages.mms.pdu.PduHeaders
import dev.liquidmessages.mms.pdu.PduParser
import dev.liquidmessages.mms.pdu.PduPart
import dev.liquidmessages.mms.pdu.RetrieveConf
import dev.liquidmessages.mms.pdu.SendConf
import dev.liquidmessages.mms.pdu.SendReq
import dev.liquidmessages.notify.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Bajar y enviar MMS. La conexión con el operador la hace el servicio de MMS del sistema
 * (SmsManager): esta app no tiene permiso de internet. Se le pasan los datos en un archivo de la
 * caché, compartido con un FileProvider solo con los paquetes de telefonía y del servicio de MMS,
 * y se borra al terminar.
 */
object MmsTransport {
    const val ACTION_DOWNLOADED = "dev.liquidmessages.MMS_DOWNLOADED"
    const val ACTION_SENT = "dev.liquidmessages.MMS_SENT"
    private const val EXTRA_FILE = "file"
    private const val EXTRA_SUB = "sub"

    /** Paquetes del sistema que leen o escriben el archivo del MMS. */
    private val SYSTEM_READERS = listOf("com.android.phone", "com.android.mms.service")

    private fun dir(context: Context) = File(context.cacheDir, "mms").apply { mkdirs() }

    private fun share(context: Context, file: File, write: Boolean): Uri {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.mms", file)
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0
        SYSTEM_READERS.forEach { runCatching { context.grantUriPermission(it, uri, flags) } }
        return uri
    }

    private fun manager(context: Context, subscriptionId: Int?): SmsManager {
        val base = context.getSystemService(SmsManager::class.java)
        return if (subscriptionId != null) base.createForSubscriptionId(subscriptionId) else base
    }

    private fun pending(context: Context, action: String, data: Uri, file: File, sub: Int?): PendingIntent {
        val intent = Intent(context, MmsResultReceiver::class.java)
            .setAction(action)
            .setData(data)
            .putExtra(EXTRA_FILE, file.name)
            .putExtra(EXTRA_SUB, sub ?: -1)
        return PendingIntent.getBroadcast(context, (ContentUris.parseId(data) and 0x7FFFFFFF).toInt(), intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Pide bajar un MMS guardado como aviso. El resultado llega a [MmsResultReceiver]. */
    fun download(context: Context, notification: Uri) {
        val (location, _, sub) = MmsStore(context).pendingInfo(notification) ?: return
        val file = File(dir(context), "in-${ContentUris.parseId(notification)}.pdu")
        val uri = share(context, file, write = true)
        manager(context, sub).downloadMultimediaMessage(context, location, uri, null, pending(context, ACTION_DOWNLOADED, notification, file, sub))
    }

    internal fun onDownloaded(context: Context, notification: Uri, fileName: String, sub: Int?, ok: Boolean) {
        val file = File(dir(context), fileName)
        try {
            val bytes = if (ok && file.exists()) file.readBytes() else null
            val conf = bytes?.let { runCatching { PduParser(it, true).parse() }.getOrNull() } as? RetrieveConf
            if (conf == null) {
                downloadFailed(context, notification)
                return
            }
            val (uri, people) = MmsStore(context).insertRetrieved(conf, sub, notification) ?: run {
                downloadFailed(context, notification)
                return
            }
            context.contentResolver.query(uri, arrayOf(Telephony.Mms.THREAD_ID), null, null, null)?.use { c ->
                if (c.moveToFirst()) Notifier.incoming(context, c.getLong(0), people)
            }
            // Avisarle al operador que se bajó, como pide el estándar (si no, puede volver a mandarlo).
            conf.transactionId?.let { notifyResponse(context, it, sub) }
        } finally {
            file.delete()
        }
    }

    private fun downloadFailed(context: Context, notification: Uri) {
        val thread = context.contentResolver.query(notification, arrayOf(Telephony.Mms.THREAD_ID), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getLong(0) else null
        } ?: return
        Notifier.notice(context, thread, null, context.getString(R.string.notif_mms_failed_title), context.getString(R.string.notif_mms_failed_text))
    }

    /** "m-notifyresp.ind" con estado "bajado". Si falla no pasa nada grave: el MMS ya está guardado. */
    private fun notifyResponse(context: Context, transactionId: ByteArray, sub: Int?) {
        runCatching {
            val ind = NotifyRespInd(PduHeaders.CURRENT_MMS_VERSION, transactionId, PduHeaders.STATUS_RETRIEVED)
            val bytes = PduComposer(context, ind).make() ?: return
            val file = File(dir(context), "ack-${System.nanoTime()}.pdu").apply { writeBytes(bytes) }
            manager(context, sub).sendMultimediaMessage(context, share(context, file, write = false), null, null, null)
        }
    }

    /** Una foto lista para mandar: JPEG reducido hasta entrar en el tamaño que acepta el operador. */
    class Image(val bytes: ByteArray, val contentType: String = "image/jpeg")

    /** Tamaño máximo de un MMS según el operador (300 KB si no lo dice). */
    fun maxSize(context: Context, subscriptionId: Int?): Int =
        runCatching { manager(context, subscriptionId).carrierConfigValues.getInt(SmsManager.MMS_CONFIG_MAX_MESSAGE_SIZE) }.getOrNull()?.takeIf { it > 0 } ?: 300 * 1024

    /**
     * Reduce una foto elegida hasta [maxBytes]: primero el lado mayor a 1600 px y después baja la
     * calidad y el tamaño de a poco. null si no entra ni así.
     */
    fun prepareImage(context: Context, source: Uri, maxBytes: Int): Image? {
        val bitmap = runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, source)) { decoder, info, _ ->
                val side = maxOf(info.size.width, info.size.height)
                if (side > 1600) decoder.setTargetSampleSize(maxOf(1, side / 1600))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }.getOrNull() ?: return null
        var current: Bitmap = bitmap
        repeat(8) {
            for (quality in intArrayOf(85, 70, 55)) {
                val out = ByteArrayOutputStream()
                current.compress(Bitmap.CompressFormat.JPEG, quality, out)
                if (out.size() <= maxBytes) return Image(out.toByteArray())
            }
            current = Bitmap.createScaledBitmap(current, (current.width * 0.75f).toInt().coerceAtLeast(1), (current.height * 0.75f).toInt().coerceAtLeast(1), true)
        }
        return null
    }

    /**
     * Envía un MMS (texto y fotos, a una o varias personas). Lo guarda como "saliendo" y lo pasa al
     * servicio de MMS; el resultado llega a [MmsResultReceiver]. Devuelve la fila guardada.
     */
    fun send(context: Context, recipients: List<String>, text: String, images: List<Image>, chip: SimChip?): Uri? {
        val req = SendReq()
        req.from = EncodedStringValue(MmsStore.INSERT_ADDRESS_TOKEN)
        recipients.forEach { req.addTo(EncodedStringValue(it)) }
        req.date = System.currentTimeMillis() / 1000
        req.messageClass = PduHeaders.MESSAGE_CLASS_PERSONAL_STR.toByteArray()
        req.expiry = 7 * 24 * 60 * 60L
        req.priority = PduHeaders.PRIORITY_NORMAL
        req.deliveryReport = PduHeaders.VALUE_NO
        req.readReport = PduHeaders.VALUE_NO
        req.contentType = ContentType.MMS_MULTIPART_RELATED.toByteArray()
        req.body = body(text, images)
        val bytes = PduComposer(context, req).make() ?: return null
        req.messageSize = bytes.size.toLong()
        val store = MmsStore(context)
        val row = store.insertOutgoing(req, recipients, chip?.subscriptionId) ?: return null
        val file = File(dir(context), "out-${ContentUris.parseId(row)}.pdu").apply { writeBytes(bytes) }
        try {
            manager(context, chip?.subscriptionId).sendMultimediaMessage(context, share(context, file, write = false), null, null, pending(context, ACTION_SENT, row, file, chip?.subscriptionId))
        } catch (e: RuntimeException) {
            store.setBox(row, Telephony.Mms.MESSAGE_BOX_FAILED)
            file.delete()
        }
        return row
    }

    /** Cuerpo con presentación SMIL (como lo arma la app de AOSP): las fotos arriba y el texto abajo. */
    internal fun body(text: String, images: List<Image>): PduBody {
        val body = PduBody()
        val pars = StringBuilder()
        images.forEachIndexed { i, img ->
            val name = "image$i.jpg"
            body.addPart(
                PduPart().apply {
                    contentType = img.contentType.toByteArray()
                    contentLocation = name.toByteArray()
                    contentId = "<image$i>".toByteArray()
                    this.name = name.toByteArray()
                    data = img.bytes
                },
            )
            pars.append("<par dur=\"5000ms\"><img src=\"$name\" region=\"Image\"/></par>")
        }
        if (text.isNotEmpty()) {
            body.addPart(
                PduPart().apply {
                    contentType = "text/plain".toByteArray()
                    charset = CharacterSets.UTF_8
                    contentLocation = "text_0.txt".toByteArray()
                    contentId = "<text_0>".toByteArray()
                    data = text.toByteArray(Charsets.UTF_8)
                },
            )
            pars.append("<par dur=\"5000ms\"><text src=\"text_0.txt\" region=\"Text\"/></par>")
        }
        val smil = "<smil><head><layout><root-layout/>" +
            "<region id=\"Image\" fit=\"meet\" top=\"0\" left=\"0\" height=\"80%\" width=\"100%\"/>" +
            "<region id=\"Text\" top=\"80%\" left=\"0\" height=\"20%\" width=\"100%\"/>" +
            "</layout></head><body>$pars</body></smil>"
        body.addPart(
            0,
            PduPart().apply {
                contentType = "application/smil".toByteArray()
                contentLocation = "smil.xml".toByteArray()
                contentId = "<smil>".toByteArray()
                data = smil.toByteArray()
            },
        )
        return body
    }

    internal fun onSent(context: Context, row: Uri, fileName: String, ok: Boolean, response: ByteArray?) {
        File(dir(context), fileName).delete()
        val store = MmsStore(context)
        val conf = response?.let { runCatching { PduParser(it, true).parse() }.getOrNull() } as? SendConf
        val accepted = ok && (conf == null || conf.responseStatus == PduHeaders.RESPONSE_STATUS_OK)
        if (accepted) {
            store.setBox(row, Telephony.Mms.MESSAGE_BOX_SENT, conf?.messageId?.let(::String))
        } else {
            store.setBox(row, Telephony.Mms.MESSAGE_BOX_FAILED)
            val thread = context.contentResolver.query(row, arrayOf(Telephony.Mms.THREAD_ID), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getLong(0) else null
            } ?: return
            Notifier.notice(context, thread, null, context.getString(R.string.notif_failed_title), context.getString(R.string.notif_failed_text, "MMS"))
        }
    }

    internal fun fileOf(intent: Intent): String = intent.getStringExtra(EXTRA_FILE).orEmpty()
    internal fun subOf(intent: Intent): Int? = intent.getIntExtra(EXTRA_SUB, -1).takeIf { it >= 0 }

    /** Leer una foto de un MMS guardado, reducida para mostrarla. */
    fun loadPart(context: Context, partId: Long, maxSide: Int): Bitmap? = runCatching {
        val uri = Uri.parse("content://mms/part/$partId")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
    }.getOrNull()
}

/** Resultado de bajar o enviar un MMS. No es exportado: solo lo llama el sistema por el PendingIntent. */
class MmsResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val row = intent.data ?: return
        val ok = resultCode == Activity.RESULT_OK
        val file = MmsTransport.fileOf(intent)
        val sub = MmsTransport.subOf(intent)
        val response = intent.getByteArrayExtra(SmsManager.EXTRA_MMS_DATA)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    MmsTransport.ACTION_DOWNLOADED -> MmsTransport.onDownloaded(context, row, file, sub, ok)
                    MmsTransport.ACTION_SENT -> MmsTransport.onSent(context, row, file, ok, response)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
