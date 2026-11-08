// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidmessages.data

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** Estado observable persistido como JSON en un archivo. */
class JsonStore<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
    default: T,
) {
    private val _state = MutableStateFlow(load(default))
    val state: StateFlow<T> = _state.asStateFlow()
    val value: T get() = _state.value

    private fun load(default: T): T = try {
        if (file.exists()) json.decodeFromString(serializer, file.readText()) else default
    } catch (e: Exception) {
        default
    }

    @Synchronized
    fun update(transform: (T) -> T): T {
        val new = transform(_state.value)
        _state.value = new
        val atomic = AtomicFile(file)
        val out = atomic.startWrite()
        try {
            out.write(json.encodeToString(serializer, new).toByteArray())
            atomic.finishWrite(out)
        } catch (e: Exception) {
            atomic.failWrite(out)
        }
        return new
    }
}

object Stores {
    lateinit var settings: JsonStore<AppSettings>

    /**
     * Borradores: lo escrito y no enviado, por clave de conversación (Conversations.key). Viven en
     * la app (files/drafts.json), no en el proveedor del sistema: ninguna otra app los ve.
     */
    lateinit var drafts: JsonStore<Map<String, String>>

    private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        settings = JsonStore(File(context.filesDir, "settings.json"), AppSettings.serializer(), AppSettings())
        drafts = JsonStore(
            File(context.filesDir, "drafts.json"),
            kotlinx.serialization.builtins.MapSerializer(kotlinx.serialization.builtins.serializer<String>(), kotlinx.serialization.builtins.serializer<String>()),
            emptyMap(),
        )
        initialized = true
    }
}
