package com.eddiew.commandersim.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URLEncoder

/** One face of a card (most cards have one; double-faced and split cards have two). */
data class CardFace(
    val name: String,
    val manaCost: String?,
    val typeLine: String?,
    val oracleText: String?,
    val flavorText: String?,
    val power: String?,
    val toughness: String?,
    val loyalty: String?,
    val imageUrl: String?,
)

/** Everything shown in the long-press card popup, from Scryfall. */
data class CardInfo(
    val name: String,
    val faces: List<CardFace>,
    val manaValue: Double?,
    val colorIdentity: String,
    val keywords: List<String>,
    val setName: String?,
    val setCode: String?,
    val rarity: String?,
    val artist: String?,
    val releasedAt: String?,
    val edhrecRank: Int?,
    val priceUsd: String?,
    val priceUsdFoil: String?,
    val legalities: List<Pair<String, String>>,
    val scryfallUrl: String?,
) {
    companion object {
        fun parse(json: String): CardInfo {
            val o = AppJson.parseToJsonElement(json).jsonObject
            fun JsonObject.s(k: String) = runCatching { this[k]?.jsonPrimitive?.contentOrNull }.getOrNull()
            fun JsonObject.image() = (this["image_uris"] as? JsonObject)?.s("normal")
            val faces = (o["card_faces"] as? JsonArray)?.map { it.jsonObject }
            val topImage = o.image()
            val faceList = if (faces.isNullOrEmpty()) {
                listOf(CardFace(o.s("name") ?: "?", o.s("mana_cost"), o.s("type_line"), o.s("oracle_text"), o.s("flavor_text"),
                    o.s("power"), o.s("toughness"), o.s("loyalty"), topImage))
            } else {
                faces.mapIndexed { i, f ->
                    CardFace(f.s("name") ?: "?", f.s("mana_cost"), f.s("type_line"), f.s("oracle_text"), f.s("flavor_text"),
                        f.s("power"), f.s("toughness"), f.s("loyalty"), f.image() ?: if (i == 0) topImage else null)
                }
            }
            val prices = o["prices"] as? JsonObject
            val legal = (o["legalities"] as? JsonObject)?.let { l ->
                listOf("commander", "brawl", "oathbreaker", "vintage", "legacy", "modern", "pioneer", "standard", "pauper")
                    .mapNotNull { k -> l.s(k)?.let { k to it } }
            }.orEmpty()
            return CardInfo(
                name = o.s("name") ?: "?",
                faces = faceList,
                manaValue = o.s("cmc")?.toDoubleOrNull(),
                colorIdentity = (o["color_identity"] as? JsonArray)?.joinToString("") { it.jsonPrimitive.content } ?: "",
                keywords = (o["keywords"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty(),
                setName = o.s("set_name"),
                setCode = o.s("set")?.uppercase(),
                rarity = o.s("rarity"),
                artist = o.s("artist"),
                releasedAt = o.s("released_at"),
                edhrecRank = runCatching { o["edhrec_rank"]?.jsonPrimitive?.intOrNull }.getOrNull(),
                priceUsd = prices?.s("usd"),
                priceUsdFoil = prices?.s("usd_foil"),
                legalities = legal,
                scryfallUrl = o.s("scryfall_uri"),
            )
        }
    }
}

/** Looks cards up on Scryfall by name and keeps the answers on the phone, so each card is fetched once. */
class CardInfoRepository(dir: File) {
    private val cacheDir = File(dir, "cardinfo")
    private val memory = HashMap<String, CardInfo>()

    private fun key(name: String) = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").take(80)

    suspend fun get(name: String): CardInfo = withContext(Dispatchers.IO) {
        val k = key(name)
        synchronized(memory) { memory[k] }?.let { return@withContext it }
        val file = File(cacheDir, "$k.json")
        val json = if (file.exists()) file.readText() else {
            fun fetch(n: String) = httpGet("https://api.scryfall.com/cards/named?exact=" + URLEncoder.encode(n, "UTF-8"))
            val text = fetch(name)
                ?: fetch(name.substringBefore(" // ").trim())
                ?: httpGet("https://api.scryfall.com/cards/named?fuzzy=" + URLEncoder.encode(name, "UTF-8"))
                ?: error("Scryfall doesn't know \"$name\".")
            cacheDir.mkdirs()
            file.writeText(text)
            text
        }
        CardInfo.parse(json).also { synchronized(memory) { memory[k] = it } }
    }
}
