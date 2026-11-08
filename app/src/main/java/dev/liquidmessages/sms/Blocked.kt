// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidmessages.sms

import android.content.ContentValues
import android.content.Context
import android.provider.BlockedNumberContract
import android.provider.BlockedNumberContract.BlockedNumbers

/**
 * La lista de números bloqueados del sistema (la misma que usa Liquid Phone y el teléfono del
 * sistema), no una propia: el sistema descarta los SMS y llamadas de esos números antes de que
 * lleguen, y suspende el bloqueo después de una llamada de emergencia. Solo la app de SMS o de
 * teléfono predeterminada puede leerla y cambiarla.
 */
class Blocked(context: Context) {
    private val app = context.applicationContext

    fun canUse(): Boolean = runCatching { BlockedNumberContract.canCurrentUserBlockNumbers(app) }.getOrDefault(false) && DefaultSms.isDefault(app)

    fun list(): List<Pair<Long, String>> = runCatching {
        app.contentResolver.query(BlockedNumbers.CONTENT_URI, arrayOf(BlockedNumbers.COLUMN_ID, BlockedNumbers.COLUMN_ORIGINAL_NUMBER), null, null, null)?.use { c ->
            buildList { while (c.moveToNext()) add(c.getLong(0) to c.getString(1).orEmpty()) }
        }.orEmpty()
    }.getOrDefault(emptyList())

    fun isBlocked(number: String): Boolean = runCatching { BlockedNumberContract.isBlocked(app, number) }.getOrDefault(false)

    fun block(number: String): Boolean = runCatching {
        app.contentResolver.insert(BlockedNumbers.CONTENT_URI, ContentValues().apply { put(BlockedNumbers.COLUMN_ORIGINAL_NUMBER, number) }) != null
    }.getOrDefault(false)

    fun unblock(number: String): Boolean = runCatching { BlockedNumberContract.unblock(app, number) > 0 }.getOrDefault(false)
}
