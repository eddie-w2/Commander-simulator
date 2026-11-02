package com.eddiew.commandersim.sim

import com.eddiew.commandersim.data.model.CardType
import com.eddiew.commandersim.data.model.EndCondition

/*
 * Types shared by the app and the Forge pack (which is loaded at runtime with the app as its parent class loader).
 * Changing anything here that the pack uses requires bumping [ENGINE_API_VERSION].
 */

/** Packs built against a different value are not loaded. */
const val ENGINE_API_VERSION = 1

/** Per-round series length. */
const val MAX_TURNS = 20

class CardTrack : java.io.Serializable {
    var seen = false
    var inOpening = false
    var drawnRound = -1
    var casts = 0
    var firstCastRound = -1
    var damage = 0
    var combatDamage = 0
    var nonCombatDamage = 0
    var killShots = 0
    var removals = 0
    var removed = 0
    var countered = 0
    var dmgBySeat: IntArray? = null
    var dmgByRound: IntArray? = null
    val removedBy = HashMap<String, Int>() // "card|seat"
}

/** Everything one player did in one game. */
class PlayerTrack(val seats: Int) : java.io.Serializable {
    var drawn = 0
    val drawsByTurn = IntArray(MAX_TURNS)
    val handByTurn = IntArray(MAX_TURNS) { -1 }
    val landsInPlayByTurn = IntArray(MAX_TURNS) { -1 }
    val lifeByTurn = IntArray(MAX_TURNS) { -1 }
    var maxHand = 0
    var discarded = 0
    var landsPlayed = 0
    val landByTurn = BooleanArray(MAX_TURNS)
    val manaByTurn = IntArray(MAX_TURNS)
    var turnsTaken = 0
    var mulligans = 0
    var milled = 0

    var dmgPlayers = 0
    var dmgCombat = 0
    var dmgNonCombat = 0
    var dmgCommander = 0
    var dmgCreatures = 0
    var taken = 0
    var takenCombat = 0
    var takenNonCombat = 0
    var takenCommander = 0
    var lifeGained = 0
    val dmgByTurn = IntArray(MAX_TURNS)
    val takenByTurn = IntArray(MAX_TURNS)
    val dmgBySeat = IntArray(seats)
    val takenBySeat = IntArray(seats)
    val takenByCard = HashMap<String, Int>() // "card|seat"
    var kills = 0
    var lethalBy: String? = null // "card|seat"

    val cards = HashMap<String, CardTrack>()
    fun card(name: String) = cards.getOrPut(name) { CardTrack() }

    var removalSpells = 0
    var permsRemoved = 0
    val removedByType = HashMap<CardType, Int>()
    var received = 0
    var counters = 0
    var wipes = 0
    var sacrificed = 0
    val removalAtSeat = IntArray(seats)
    val ownRemoved = HashMap<String, Int>()

    var cmdCasts = 0
    var cmdFirstCast = -1
    var cmdRemoved = 0

    var attacks = 0
    var attackersTotal = 0
    var blocks = 0
    var creaturesLost = 0

    val permsByTurn = IntArray(MAX_TURNS) { -1 }
    val creaturesByTurn = IntArray(MAX_TURNS) { -1 }
    var tokens = 0
    var counterPlaced = 0

    var place = 0
    var eliminatedTurn = -1
    var lossCause: EndCondition? = null
}

class GameResult(
    val winnerSeat: Int?,
    val winCondition: EndCondition?,
    val rounds: Int,
    val tracks: List<PlayerTrack>,
    /** Seat (deck order) → actual turn-order position, when the engine chose who goes first. */
    val turnPosition: IntArray? = null,
    /** The game hit the round/time limit and the winner was scored on life and board. */
    val adjudicated: Boolean = false,
) : java.io.Serializable


/** A deck as the engine sees it. Names are the app's card names. */
data class BridgeDeck(
    val id: String,
    val name: String,
    val commanders: List<String>,
    /** Card name to quantity. */
    val cards: List<Pair<String, Int>>,
) : java.io.Serializable

/** Implemented by com.eddiew.commandersim.forgepack.Bridge inside a Forge pack. */
interface ForgeBridge {
    /** Forge commit (plus bundle revision) the pack was built from. */
    fun forgeVersion(resRoot: java.io.File): String

    /** Loads the card data for these decks. Returns the card names Forge doesn't know. */
    fun prepare(resRoot: java.io.File, workDir: java.io.File, decks: List<BridgeDeck>, log: (String) -> Unit): List<String>

    /** Plays one game; seats are deck ids in seat order. */
    fun play(seatDeckIds: List<String>, seed: Long, maxRounds: Int, maxMillis: Long, status: (String) -> Unit): GameResult
}
