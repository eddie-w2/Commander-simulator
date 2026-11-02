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
        return CardInfo(name, typeLine, cmc, identity, canLead || "Background" in front, partner)
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
            )
        }
    }
}
