package com.eddiew.commandersim

import com.eddiew.commandersim.data.DeckLinkImporter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeckLinkImporterTest {
    private val importer = DeckLinkImporter()
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test
    fun `recognises links`() {
        assertTrue(DeckLinkImporter.isDeckLink("https://moxfield.com/decks/AbC123_x"))
        assertTrue(DeckLinkImporter.isDeckLink("https://www.moxfield.com/decks/AbC123_x/primer"))
        assertTrue(DeckLinkImporter.isDeckLink("https://archidekt.com/decks/123456/my_deck"))
        assertFalse(DeckLinkImporter.isDeckLink("1 Sol Ring\n1 Arcane Signet"))
    }

    @Test
    fun `moxfield v3 boards`() {
        val d = importer.parseMoxfield(obj("""
            {"name":"Elves","boards":{
              "commanders":{"count":1,"cards":{"x1":{"quantity":1,"card":{"name":"Lathril, Blade of the Elves","type_line":"Legendary Creature — Elf Noble","cmc":4,"color_identity":["B","G"]}}}},
              "mainboard":{"count":2,"cards":{"x2":{"quantity":1,"card":{"name":"Sol Ring","type_line":"Artifact","cmc":1,"color_identity":[]}},
                                             "x3":{"quantity":30,"card":{"name":"Forest","type_line":"Basic Land — Forest","cmc":0,"color_identity":["G"]}}}},
              "maybeboard":{"count":1,"cards":{"x4":{"quantity":1,"card":{"name":"Craterhoof Behemoth"}}}}
            }}
        """), "u")
        assertEquals("Elves", d.name)
        assertEquals("Lathril, Blade of the Elves", d.commanders.single().name)
        assertEquals("BG", d.commanders.single().colors)
        assertEquals(31, d.cards.sumOf { it.quantity })
        assertEquals(1.0, d.cards.first { it.name == "Sol Ring" }.manaValue)
    }

    @Test
    fun `moxfield v2 top level boards`() {
        val d = importer.parseMoxfield(obj("""
            {"name":"Goblins","commanders":{"Krenko, Mob Boss":{"quantity":1,"card":{"name":"Krenko, Mob Boss","type_line":"Legendary Creature — Goblin Warrior","cmc":4}}},
             "mainboard":{"Goblin Matron":{"quantity":1,"card":{"name":"Goblin Matron","type_line":"Creature — Goblin","cmc":3}}}}
        """), "u")
        assertEquals("Krenko, Mob Boss", d.commanders.single().name)
        assertEquals("Goblin Matron", d.cards.single().name)
    }

    @Test
    fun `archidekt categories`() {
        val d = importer.parseArchidekt(obj("""
            {"name":"Meren","categories":[{"name":"Maybeboard","includedInDeck":false},{"name":"Commander","includedInDeck":true}],
             "cards":[
              {"quantity":1,"categories":["Commander"],"card":{"oracleCard":{"name":"Meren of Clan Nel Toth","superTypes":["Legendary"],"types":["Creature"],"subTypes":["Human","Shaman"],"cmc":4,"colorIdentity":["Black","Green"]}}},
              {"quantity":1,"categories":["Ramp"],"card":{"oracleCard":{"name":"Cultivate","types":["Sorcery"],"cmc":3,"colorIdentity":["Green"]}}},
              {"quantity":1,"categories":["Maybeboard"],"card":{"oracleCard":{"name":"Living Death","types":["Sorcery"],"cmc":5}}}
             ]}
        """), "u")
        assertEquals("Meren of Clan Nel Toth", d.commanders.single().name)
        assertEquals("BG", d.commanders.single().colors)
        assertEquals("Legendary Creature — Human Shaman", d.commanders.single().typeLine)
        assertEquals(listOf("Cultivate"), d.cards.map { it.name })
    }
}
