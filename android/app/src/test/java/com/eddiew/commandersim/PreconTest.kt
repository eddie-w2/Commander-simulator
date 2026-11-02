package com.eddiew.commandersim

import com.eddiew.commandersim.data.DeckLinkImporter
import com.eddiew.commandersim.data.PreconRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreconTest {
    @Test
    fun `deck list keeps commander decks newest first`() {
        val list = PreconRepository.parseDeckList("""
            {"meta":{},"data":[
              {"code":"C21","fileName":"LoreHoldLegacies_C21","name":"Lorehold Legacies","releaseDate":"2021-04-23","type":"Commander Deck"},
              {"code":"ONC","fileName":"RebellionRising_ONC","name":"Rebellion Rising","releaseDate":"2023-02-03","type":"Commander Deck"},
              {"code":"M21","fileName":"Something_M21","name":"Welcome","releaseDate":"2020-07-03","type":"Welcome Deck"}
            ]}
        """)
        assertEquals(listOf("Rebellion Rising", "Lorehold Legacies"), list.map { it.name })
        assertEquals("https://mtgjson.com/api/v5/decks/RebellionRising_ONC.json", list.first().url)
        assertEquals("2023", list.first().year)
    }

    @Test
    fun `mtgjson deck parses commander and main board`() {
        val d = DeckLinkImporter().parseMtgjsonDeck(Json.parseToJsonElement("""
            {"data":{"name":"Rebellion Rising","commander":[
                {"name":"Neyali, Suns' Vanguard","count":1,"type":"Legendary Creature — Human Soldier","manaValue":3,"colorIdentity":["R","W"]}],
              "mainBoard":[
                {"name":"Sol Ring","count":1,"type":"Artifact","manaValue":1,"colorIdentity":[]},
                {"name":"Plains","count":10,"type":"Basic Land — Plains","manaValue":0,"colorIdentity":["W"]},
                {"name":"Neyali, Suns' Vanguard","count":1,"type":"Legendary Creature — Human Soldier","manaValue":3}
              ]}}
        """).jsonObject, "u")
        assertEquals("Neyali, Suns' Vanguard", d.commanders.single().name)
        assertEquals("RW", d.commanders.single().colors)
        assertEquals(11, d.cards.sumOf { it.quantity })
        assertTrue(d.cards.none { it.name.startsWith("Neyali") })
    }

    @Test
    fun `mtgjson links are recognised`() {
        assertTrue(DeckLinkImporter.isDeckLink("https://mtgjson.com/api/v5/decks/RebellionRising_ONC.json"))
    }
}
