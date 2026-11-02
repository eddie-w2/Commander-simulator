package com.eddiew.commandersim.data

import com.eddiew.commandersim.data.model.DeckCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/**
 * Imports a deck from a Moxfield or Archidekt link. Neither site documents its API, so the JSON is read
 * defensively, and Moxfield's plain-text export is tried as a last resort.
 */
class DeckLinkImporter(private val json: Json = Json { ignoreUnknownKeys = true }) {

    data class Imported(val name: String, val commanders: List<DeckCard>, val cards: List<DeckCard>, val url: String)

    class ImportException(message: String) : Exception(message)

    companion object {
        private val moxfield = Regex("""moxfield\.com/decks/([A-Za-z0-9_-]+)""")
        private val archidekt = Regex("""archidekt\.com/(?:decks|api/decks)/(\d+)""")
        private val mtgjson = Regex("""mtgjson\.com/api/v5/decks/([^/?#]+?)(?:\.json)?(?:$|[?#])""")

        /** MTGJSON file for one precon deck. */
        fun mtgjsonUrl(fileName: String) = "https://mtgjson.com/api/v5/decks/$fileName.json"

        fun isDeckLink(text: String): Boolean {
            val t = text.trim()
            return !t.contains('\n') && (moxfield.containsMatchIn(t) || archidekt.containsMatchIn(t) || mtgjson.containsMatchIn(t))
        }

        private val colourNames = mapOf("white" to 'W', "blue" to 'U', "black" to 'B', "red" to 'R', "green" to 'G')
    }

    suspend fun import(link: String): Imported = withContext(Dispatchers.IO) {
        val url = link.trim()
        moxfield.find(url)?.let { return@withContext importMoxfield(it.groupValues[1], url) }
        archidekt.find(url)?.let { return@withContext importArchidekt(it.groupValues[1], url) }
        mtgjson.find(url)?.let {
            val body = get(mtgjsonUrl(it.groupValues[1]))
            return@withContext parseMtgjsonDeck(json.parseToJsonElement(body).jsonObject, mtgjsonUrl(it.groupValues[1]))
        }
        throw ImportException("That link isn't a Moxfield or Archidekt deck.")
    }

    // ─────────────────────────── Moxfield ───────────────────────────

    private fun importMoxfield(id: String, url: String): Imported {
        var lastError: String? = null
        for (endpoint in listOf("https://api2.moxfield.com/v3/decks/all/$id", "https://api2.moxfield.com/v2/decks/all/$id")) {
            val body = runCatching { get(endpoint) }.getOrElse { lastError = it.message; null } ?: continue
            val parsed = runCatching { parseMoxfield(json.parseToJsonElement(body).jsonObject, url) }.getOrNull()
            if (parsed != null && parsed.cards.isNotEmpty()) return parsed
        }
        // Last resort: Moxfield's own text export, through the normal parser.
        val text = runCatching { get("https://api2.moxfield.com/v2/decks/all/$id/export") }.getOrNull()
        if (!text.isNullOrBlank()) {
            val r = DeckParser.parse(text)
            if (r.cards.isNotEmpty()) return Imported("Moxfield deck", r.commanders, r.cards, url)
        }
        throw ImportException(
            "Moxfield didn't return the deck (${lastError ?: "unexpected response"}). Check that the deck is public, " +
                "or use More → Export → Copy plain text on Moxfield and paste the list instead.",
        )
    }

    internal fun parseMoxfield(root: JsonObject, url: String): Imported {
        val name = root.str("name") ?: "Moxfield deck"
        // v3 nests boards under "boards" (each with a "cards" map); v2 has boards at the top level.
        val boards = (root["boards"] as? JsonObject) ?: root
        fun board(key: String): List<DeckCard> {
            val b = boards[key] as? JsonObject ?: return emptyList()
            val entries = (b["cards"] as? JsonObject) ?: b
            return entries.values.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val card = (o["card"] as? JsonObject) ?: o
                val cardName = card.str("name") ?: return@mapNotNull null
                DeckCard(
                    name = cardName,
                    quantity = o["quantity"]?.jsonPrimitive?.intOrNull ?: 1,
                    setCode = card.str("set")?.uppercase(),
                    collectorNumber = card.str("cn") ?: card.str("collector_number"),
                    typeLine = card.str("type_line"),
                    manaValue = card["cmc"]?.jsonPrimitive?.doubleOrNull,
                    colors = (card["color_identity"] as? JsonArray)?.joinToString("") { it.jsonPrimitive.content },
                )
            }
        }
        return Imported(name, board("commanders"), board("mainboard"), url)
    }

    // ─────────────────────────── Archidekt ───────────────────────────

    private fun importArchidekt(id: String, url: String): Imported {
        val body = runCatching { get("https://archidekt.com/api/decks/$id/") }.getOrElse {
            throw ImportException("Archidekt didn't return the deck (${it.message}). Check that the deck is public.")
        }
        return parseArchidekt(json.parseToJsonElement(body).jsonObject, url)
    }

    internal fun parseArchidekt(root: JsonObject, url: String): Imported {
        val name = root.str("name") ?: "Archidekt deck"
        // Categories like Maybeboard/Sideboard are marked as not included in the deck.
        val excluded = (root["categories"] as? JsonArray)?.mapNotNull { c ->
            val o = c.jsonObject
            val included = o["includedInDeck"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
            if (!included) o.str("name")?.lowercase() else null
        }?.toSet() ?: setOf("maybeboard", "sideboard")
        val commanders = mutableListOf<DeckCard>()
        val cards = mutableListOf<DeckCard>()
        for (el in root["cards"]?.jsonArray ?: JsonArray(emptyList())) {
            val o = el.jsonObject
            val cats = (o["categories"] as? JsonArray)?.map { it.jsonPrimitive.content.lowercase() } ?: emptyList()
            if (cats.any { it in excluded }) continue
            val card = o["card"] as? JsonObject ?: continue
            val oracle = (card["oracleCard"] as? JsonObject) ?: card
            val cardName = oracle.str("name") ?: card.str("name") ?: continue
            fun words(key: String) = (oracle[key] as? JsonArray)?.joinToString(" ") { it.jsonPrimitive.content } ?: ""
            val base = listOf(words("superTypes"), words("types")).filter { it.isNotBlank() }.joinToString(" ")
            val sub = words("subTypes")
            val typeLine = (if (sub.isNotBlank()) "$base — $sub" else base).ifBlank { null }
            val colours = (oracle["colorIdentity"] as? JsonArray)?.mapNotNull { c ->
                val v = c.jsonPrimitive.content
                colourNames[v.lowercase()] ?: v.firstOrNull()?.uppercaseChar()?.takeIf { it in "WUBRG" }
            }?.joinToString("")
            val dc = DeckCard(
                name = cardName,
                quantity = o["quantity"]?.jsonPrimitive?.intOrNull ?: 1,
                typeLine = typeLine,
                manaValue = oracle["cmc"]?.jsonPrimitive?.doubleOrNull,
                colors = colours,
            )
            if (cats.any { it == "commander" }) commanders += dc else cards += dc
        }
        if (cards.isEmpty()) throw ImportException("Archidekt returned an empty deck.")
        return Imported(name, commanders, cards, url)
    }

    // ─────────────────────────── MTGJSON (official precons) ───────────────────────────

    internal fun parseMtgjsonDeck(root: JsonObject, url: String): Imported {
        val data = (root["data"] as? JsonObject) ?: root
        val name = data.str("name") ?: "Precon"
        fun board(key: String): List<DeckCard> {
            val arr = data[key] as? JsonArray ?: return emptyList()
            val merged = LinkedHashMap<String, DeckCard>()
            for (el in arr) {
                val o = el as? JsonObject ?: continue
                val cardName = o.str("faceName")?.takeIf { o.str("layout") == "meld" } ?: o.str("name") ?: continue
                val c = DeckCard(
                    name = cardName,
                    quantity = o["count"]?.jsonPrimitive?.intOrNull ?: 1,
                    setCode = o.str("setCode"),
                    collectorNumber = o.str("number"),
                    typeLine = o.str("type"),
                    manaValue = (o["manaValue"] ?: o["convertedManaCost"])?.jsonPrimitive?.doubleOrNull,
                    colors = (o["colorIdentity"] as? JsonArray)?.joinToString("") { it.jsonPrimitive.content },
                )
                val k = c.name.lowercase()
                merged[k] = merged[k]?.let { it.copy(quantity = it.quantity + c.quantity) } ?: c
            }
            return merged.values.toList()
        }
        val commanders = board("commander")
        val main = board("mainBoard").filterNot { m -> commanders.any { it.name.equals(m.name, ignoreCase = true) } }
        if (main.isEmpty()) throw ImportException("MTGJSON returned an empty deck.")
        return Imported(name, commanders, main, url)
    }

    // ─────────────────────────── HTTP ───────────────────────────

    internal fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Accept", "application/json, text/plain, */*")
            // Moxfield rejects requests without a browser-like user agent.
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36 CommanderSim/0.2",
            )
            val code = conn.responseCode
            if (code !in 200..299) throw ImportException("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonElement)?.let {
        runCatching { it.jsonPrimitive.contentOrNull }.getOrNull()
    }
}
