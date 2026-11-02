package com.eddiew.commandersim.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** One official preconstructed deck, as listed by MTGJSON. */
@Serializable
data class Precon(
    val name: String,
    val code: String,
    val fileName: String,
    val releaseDate: String? = null,
    val type: String,
) {
    val year: String get() = releaseDate?.take(4) ?: "Unreleased"
    val url: String get() = DeckLinkImporter.mtgjsonUrl(fileName)
}

@Serializable
data class PreconCatalog(val syncedAt: Long, val precons: List<Precon>)

/**
 * The list of official Commander precons, synced from MTGJSON's DeckList.json and cached on the phone so it's
 * always available offline. Individual decks are fetched when added.
 */
class PreconRepository(private val dir: File, private val importer: DeckLinkImporter = DeckLinkImporter()) {
    private val file get() = File(dir, "precons.json")
    private val _catalog = MutableStateFlow<PreconCatalog?>(null)
    val catalog: StateFlow<PreconCatalog?> = _catalog.asStateFlow()

    companion object {
        const val DECK_LIST_URL = "https://mtgjson.com/api/v5/DeckList.json"
        const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

        /** Commander precons from a DeckList.json body, newest first. */
        fun parseDeckList(json: String): List<Precon> {
            val root = AppJson.parseToJsonElement(json).jsonObject
            val data = root["data"] as? JsonArray ?: return emptyList()
            return data.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                fun str(k: String) = runCatching { o[k]?.jsonPrimitive?.contentOrNull }.getOrNull()
                val type = str("type") ?: return@mapNotNull null
                if (!type.contains("Commander", ignoreCase = true)) return@mapNotNull null
                Precon(
                    name = str("name") ?: return@mapNotNull null,
                    code = str("code") ?: "",
                    fileName = str("fileName") ?: return@mapNotNull null,
                    releaseDate = str("releaseDate"),
                    type = type,
                )
            }.sortedWith(compareByDescending<Precon> { it.releaseDate ?: "" }.thenBy { it.name })
        }
    }

    suspend fun load() = withContext(Dispatchers.IO) {
        _catalog.value = runCatching {
            if (file.exists()) AppJson.decodeFromString(PreconCatalog.serializer(), file.readText()) else null
        }.getOrNull()
    }

    val isStale: Boolean get() = _catalog.value?.let { System.currentTimeMillis() - it.syncedAt > MAX_AGE_MS } ?: true

    /** Downloads the latest list. Returns how many precons are known. */
    suspend fun sync(): Int = withContext(Dispatchers.IO) {
        val list = parseDeckList(importer.get(DECK_LIST_URL))
        require(list.isNotEmpty()) { "MTGJSON returned no Commander decks." }
        val cat = PreconCatalog(System.currentTimeMillis(), list)
        dir.mkdirs()
        file.writeText(AppJson.encodeToString(PreconCatalog.serializer(), cat))
        _catalog.value = cat
        list.size
    }
}
