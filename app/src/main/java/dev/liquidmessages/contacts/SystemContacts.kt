// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidmessages.contacts

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract.CommonDataKinds
import android.provider.ContactsContract.Contacts
import android.provider.ContactsContract.Data
import dev.liquidmessages.core.model.ContactSummary
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

/**
 * Lectura de la agenda del sistema: la parte de Liquid Phone (contacts/SystemContacts.kt) que usa
 * esta app, sin cambios. Solo nombres, números, emails y fotos, para poner nombre a los números y
 * elegir a quién escribir. Esta app no escribe en la agenda: crear o editar lleva a Liquid Contacts.
 */
class SystemContacts(context: Context) {
    private val resolver: ContentResolver = context.contentResolver

    /** Todos los contactos, con lo necesario para la lista, la búsqueda y la coincidencia de números. */
    fun summaries(): List<ContactSummary> {
        val phones = HashMap<Long, MutableList<String>>()
        val emails = HashMap<Long, MutableList<String>>()
        resolver.query(
            Data.CONTENT_URI,
            arrayOf(Data.CONTACT_ID, Data.MIMETYPE, Data.DATA1),
            "${Data.MIMETYPE} IN (?, ?)",
            arrayOf(CommonDataKinds.Phone.CONTENT_ITEM_TYPE, CommonDataKinds.Email.CONTENT_ITEM_TYPE),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val value = c.getString(2)?.takeIf { it.isNotBlank() } ?: continue
                val target = if (c.getString(1) == CommonDataKinds.Phone.CONTENT_ITEM_TYPE) phones else emails
                target.getOrPut(c.getLong(0)) { ArrayList(2) }.add(value)
            }
        }
        val out = ArrayList<ContactSummary>()
        resolver.query(
            Contacts.CONTENT_URI,
            arrayOf(Contacts._ID, Contacts.LOOKUP_KEY, Contacts.DISPLAY_NAME_PRIMARY, Contacts.STARRED, Contacts.PHOTO_URI),
            null,
            null,
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                out += ContactSummary(
                    id = id,
                    lookupKey = c.getString(1) ?: continue,
                    displayName = c.getString(2).orEmpty(),
                    starred = c.getInt(3) != 0,
                    phones = phones[id].orEmpty().distinct(),
                    emails = emails[id].orEmpty().distinct(),
                    photoUri = c.getString(4),
                )
            }
        }
        return out
    }

    /** La URI estable de un contacto, para abrirlo en la app de contactos. */
    fun lookupUri(contactId: Long): Uri? =
        Contacts.getLookupUri(resolver, ContentUris.withAppendedId(Contacts.CONTENT_URI, contactId))

    /** Emite cada vez que cambia algo en la agenda (de esta app o de cualquier otra). */
    fun changes(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        resolver.registerContentObserver(Contacts.CONTENT_URI, true, observer)
        trySend(Unit)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }.conflate()
}
