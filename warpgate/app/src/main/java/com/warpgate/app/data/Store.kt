package com.warpgate.app.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class PersistState(
    val profiles: List<Profile> = emptyList(),
    val subscriptions: List<Subscription> = emptyList(),
    val activeProfileId: String? = null
)

/**
 * Simple JSON file storage for profiles and subscriptions.
 */
object Store {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private var file: File? = null
    private var cache: PersistState? = null

    fun init(ctx: Context) {
        if (file == null) {
            file = File(ctx.filesDir, "state.json")
        }
        if (cache == null) {
            cache = runCatching {
                file?.takeIf { it.exists() }?.readText()
                    ?.let { json.decodeFromString<PersistState>(it) }
            }.getOrNull() ?: PersistState()
        }
    }

    fun load(): PersistState = cache ?: PersistState()

    fun save(state: PersistState) {
        cache = state
        runCatching {
            file?.writeText(json.encodeToString(state))
        }
    }

    fun newId(): String = UUID.randomUUID().toString().substring(0, 8)
}
