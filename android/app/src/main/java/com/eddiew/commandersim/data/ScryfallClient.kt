package com.eddiew.commandersim.data

import com.eddiew.commandersim.data.model.DeckCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL

/**
 * Looks up card type, mana value and color identity from Scryfall's collection endpoint
 * (max 75 cards per request; Scryfall asks for ≤10 requests/second).
 */
class ScryfallClient(private val json: Json = Json { ignoreUnknownKeys = true }) {

    data class CardInfo(
        val name: String,
        val typeLine: String?,
        val manaValue: Double?,
        val colorIdentity: String,
        val canBeCommander: Boolean = false,
        val partner: Boolean = false,
        val manaCost: String? = null,
        val produces: String? = null,
        val oracle: String? = null,
        val power: String? = null,
        val toughness: String? = null,
        val keywords: List<String> = emptyList(),
    ) {
        fun facts() = DeckParser.CardFacts(colorIdentity, canBeCommander, partner)
    }

    suspend fun lookup(names: List<String>): Map<String, CardInfo> = withContext(Dispatchers.IO) {
        val found = HashMap<String, CardInfo>()
        for (chunk in names.distinct().chunked(75)) {
            val body = buildJsonObject {
                put("identifiers", buildJsonArray {
                    chunk.forEach { add(buildJsonObject { put("name", it) }) }
                })
            }.toString()
            val response = post("https://api.scryfall.com/cards/collection", body)
            val data = json.parseToJsonElement(response).jsonObject["data"]?.jsonArray ?: JsonArray(emptyList())
            for (el in data) {
                val obj = el.jsonObject
                val info = parseCard(obj)
                found[info.name.lowercase()] = info
                // Requests may use only the front face name of a double-faced card.
                found.putIfAbsent(info.name.substringBefore(" // ").lowercase(), info)
            }
            delay(120)
        }
        found
    }

    private fun parseCard(obj: JsonObject): CardInfo {
        val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: ""
        val typeLine = obj["type_line"]?.jsonPrimitive?.contentOrNull
            ?: obj["card_faces"]?.jsonArray?.firstOrNull()?.jsonObject?.get("type_line")?.jsonPrimitive?.contentOrNull
        val cmc = obj["cmc"]?.jsonPrimitive?.doubleOrNull
        val identity = obj["color_identity"]?.jsonArray?.joinToString("") { it.jsonPrimitive.content } ?: ""
        val faces = obj["card_faces"]?.jsonArray?.map { it.jsonObject } ?: emptyList()
        val oracle = (listOfNotNull(obj["oracle_text"]?.jsonPrimitive?.contentOrNull) +
            faces.mapNotNull { it["oracle_text"]?.jsonPrimitive?.contentOrNull }).joinToString("\n")
        val keywords = obj["keywords"]?.jsonArray?.map { it.jsonPrimitive.content.lowercase() } ?: emptyList()
        val front = typeLine?.substringBefore("//") ?: ""
        val canLead = ("Legendary" in front && "Creature" in front) || oracle.contains("can be your commander", ignoreCase = true)
        val partner = keywords.any { it.startsWith("partner") || it == "friends forever" || it == "doctor's companion" } ||
            oracle.contains("Choose a Background", ignoreCase = true) || "Background" in front
        fun str(o: JsonObject, k: String) = runCatching { o[k]?.jsonPrimitive?.contentOrNull }.getOrNull()
        val front0 = faces.firstOrNull()
        val produced = obj["produced_mana"]?.jsonArray?.joinToString("") { it.jsonPrimitive.content }
        return CardInfo(
            name, typeLine, cmc, identity, canLead || "Background" in front, partner,
            manaCost = str(obj, "mana_cost")?.takeIf { it.isNotBlank() } ?: front0?.let { str(it, "mana_cost") },
            produces = produced,
            oracle = oracle.ifBlank { null },
            power = str(obj, "power") ?: front0?.let { str(it, "power") },
            toughness = str(obj, "toughness") ?: front0?.let { str(it, "toughness") },
            keywords = keywords,
        )
    }

    private fun post(url: String, body: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "CommanderSim/0.1 (Android)")
            conn.outputStream.use { it.write(body.toByteArray()) }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (conn.responseCode !in 200..299) error("Scryfall returned ${conn.responseCode}")
            return text
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        fun apply(card: DeckCard, info: Map<String, CardInfo>): DeckCard {
            val hit = info[card.name.lowercase()] ?: info[card.name.substringBefore(" // ").lowercase()] ?: return card
            return card.copy(
                name = if (hit.name.isNotBlank()) hit.name else card.name,
                typeLine = hit.typeLine,
                manaValue = hit.manaValue,
                colors = hit.colorIdentity,
                manaCost = hit.manaCost,
                produces = hit.produces,
                oracle = hit.oracle,
                power = hit.power,
                toughness = hit.toughness,
                keywords = hit.keywords,
            )
        }

        /** Scryfall functional tags we look up, mapped to the quick model's roles. */
        val TAGS = linkedMapOf(
            "removal" to "removal", "counterspell" to "counter", "ramp" to "ramp", "mana-rock" to "ramp",
            "mana-dork" to "ramp", "draw" to "draw", "card-advantage" to "draw", "sweeper" to "wipe",
            "board-wipe" to "wipe", "tutor" to "tutor", "burn" to "burn", "lifegain" to "lifegain",
        )
    }

    /**
     * Which of [names] carry each Scryfall functional tag (Tagger "otag:"), as card name (lowercase) → roles.
     * A tag Scryfall doesn't recognise just finds nothing; network errors leave the result partial.
     */
    suspend fun tags(names: List<String>): Map<String, Set<String>> = withContext(Dispatchers.IO) {
        val out = HashMap<String, MutableSet<String>>()
        val unique = names.distinct()
        for ((tag, role) in TAGS) {
            for (chunk in unique.chunked(25)) {
                val q = "otag:$tag (" + chunk.joinToString(" or ") { "!\"" + it.replace("\"", "") + "\"" } + ")"
                var url: String? = "https://api.scryfall.com/cards/search?q=" + java.net.URLEncoder.encode(q, "UTF-8")
                var pages = 0
                while (url != null && pages < 3) {
                    val body = runCatching { httpGet(url) }.getOrNull() ?: break
                    val root = json.parseToJsonElement(body).jsonObject
                    root["data"]?.jsonArray?.forEach { el ->
                        val n = el.jsonObject["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                        out.getOrPut(n.lowercase()) { mutableSetOf() } += role
                        out.getOrPut(n.substringBefore(" // ").lowercase()) { mutableSetOf() } += role
                    }
                    url = if (root["has_more"]?.jsonPrimitive?.contentOrNull == "true") root["next_page"]?.jsonPrimitive?.contentOrNull else null
                    pages++
                    delay(110)
                }
                delay(110)
            }
        }
        out
    }
}
