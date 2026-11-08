// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidmessages.ui.screens

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.provider.ContactsContract
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.liquidmessages.R
import dev.liquidmessages.core.text.Links
import dev.liquidmessages.data.Repo
import dev.liquidmessages.ui.glass.DialogSpec
import dev.liquidmessages.ui.glass.GlassButton
import dev.liquidmessages.ui.glass.LocalGlass
import dev.liquidmessages.util.str

/**
 * Lo que la app pide a otras apps. Ninguna acción marca, envía ni abre algo sin que se vea: llamar
 * deja el número escrito en el teclado, y un enlace web se confirma mostrando su dominio real.
 */
object Actions {
    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    }

    /** El teclado del teléfono con el número escrito (Liquid Phone u otro): nunca marca. */
    fun dial(context: Context, number: String) = start(context, Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)))

    fun openContact(context: Context, contactId: Long) {
        val uri = Repo.contacts.lookupUri(contactId) ?: return
        start(context, Intent(Intent.ACTION_VIEW, uri))
    }

    /** Crear un contacto con este número, en la app de contactos (esta app no escribe en la agenda). */
    fun addContact(context: Context, number: String) = start(
        context,
        Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI).putExtra(ContactsContract.Intents.Insert.PHONE, number),
    )

    /** Pasa el mensaje a la app de SMS actual, escrito y sin enviar. */
    fun handToSystemApp(context: Context, address: String, body: String): Boolean =
        start(context, Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", address, null)).putExtra("sms_body", body))

    /** Copia sin que el sistema muestre el texto en la vista previa si es un código (Android 13+). */
    fun copy(context: Context, text: String, sensitive: Boolean = false) {
        val clip = ClipData.newPlainText(str(R.string.app_name), text)
        if (sensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        }
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
    }

    /**
     * Tocar un enlace: un email o un teléfono abren su app (el teléfono, solo escrito); un enlace
     * web pide confirmación mostrando el dominio, que es lo que importa ante una estafa.
     */
    fun link(context: Context, l: Links.Link, dialog: (DialogSpec) -> Unit) {
        when (l.kind) {
            Links.Kind.PHONE -> dial(context, l.text)
            Links.Kind.EMAIL -> start(context, Intent(Intent.ACTION_SENDTO, Uri.parse(l.target)))
            Links.Kind.WEB -> dialog(
                DialogSpec(str(R.string.open_link_title)) { dismiss ->
                    val p = LocalGlass.current
                    Text(str(R.string.open_link_text, l.host.orEmpty()), color = p.content, fontSize = 15.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(l.target, color = p.contentSecondary, fontSize = 13.sp, maxLines = 4)
                    Spacer(Modifier.height(16.dp))
                    GlassButton(str(R.string.open), {
                        dismiss()
                        start(context, Intent(Intent.ACTION_VIEW, Uri.parse(l.target)).addCategory(Intent.CATEGORY_BROWSABLE))
                    }, Modifier.fillMaxWidth(), prominent = true)
                    Spacer(Modifier.height(8.dp))
                    GlassButton(str(R.string.cancel), dismiss, Modifier.fillMaxWidth())
                },
            )
        }
    }
}
