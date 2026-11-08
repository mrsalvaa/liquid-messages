// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidmessages.mms

import android.content.Context
import android.net.Uri
import android.provider.Telephony
import dev.liquidmessages.R
import dev.liquidmessages.mms.pdu.PduHeaders

/** Lo mínimo de un MMS para una notificación: quién lo mandó y una línea de resumen. */
object MmsText {
    fun sender(context: Context, mmsId: Long): String? =
        context.contentResolver.query(Uri.parse("content://mms/$mmsId/addr"), arrayOf(Telephony.Mms.Addr.ADDRESS), "${Telephony.Mms.Addr.TYPE} = ${PduHeaders.FROM}", null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() && it != "insert-address-token" } else null
        }

    /** El texto, o "Foto", "Video", "Audio" o "Adjunto" si no tiene. */
    fun summary(context: Context, mmsId: Long): String {
        val text = StringBuilder()
        var kind: Int? = null
        context.contentResolver.query(Uri.parse("content://mms/$mmsId/part"), arrayOf(Telephony.Mms.Part.CONTENT_TYPE, Telephony.Mms.Part.TEXT), null, null, Telephony.Mms.Part.SEQ)?.use { c ->
            while (c.moveToNext()) {
                val type = c.getString(0).orEmpty().lowercase()
                when {
                    type == "text/plain" -> c.getString(1)?.takeIf { it.isNotBlank() }?.let { if (text.isNotEmpty()) text.append(' '); text.append(it) }
                    type == "application/smil" -> Unit
                    kind == null -> kind = when {
                        type.startsWith("image/") -> R.string.attachment_photo
                        type.startsWith("video/") -> R.string.attachment_video
                        type.startsWith("audio/") -> R.string.attachment_audio
                        else -> R.string.attachment_file
                    }
                }
            }
        }
        return text.toString().ifBlank { context.getString(kind ?: R.string.attachment_file) }
    }
}
