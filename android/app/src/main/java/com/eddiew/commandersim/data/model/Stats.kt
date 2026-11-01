package com.eddiew.commandersim.data.model

import kotlinx.serialization.Serializable

/*
 * The simulation report the app displays. Field-for-field mirror of docs/STATS.md.
 * Per-game values are averages over every game the deck played; rates are 0..1;
 * "ByTurn" lists are indexed by the player's own turn (index 0 = turn 1).
 */

@Serializable
data class SimConfig(
    val candidateIds: List<String>,
    val opponentIds: List<String>,
    val gamesPerCandidate: Int,
    val podSize: Int = 4,
    val seatRotation: Boolean = true,
    val timeoutSeconds: Int = 120,
)

@Serializable
data class SimulationReport(
    val id: String,
    val createdAt: Long,
    val source: String, // "sample" until the simulator backend is connected
    val config: SimConfig,
    val decks: List<DeckReport>,
    val matchups: List<MatchupStat>,
    val meta: SimMeta,
) {
    fun deck(id: String): DeckReport? = decks.firstOrNull { it.deckId == id }
    val rankedCandidates: List<DeckReport>
        get() = decks.filter { it.role == DeckRole.CANDIDATE }.sortedByDescending { it.outcome.winRate }
}

@Serializable
data class SimMeta(
    val games: Int,
    val timeouts: Int,
    val adjudicated: Int,
    val avgTurns: Double,
    val avgGameMs: Double,
)

@Serializable
data class NamedValue(val name: String, val value: Double)

@Serializable
enum class EndCondition(val label: String) {
    COMBAT_DAMAGE("Combat"),
    COMMANDER_DAMAGE("Commander dmg"),
    NONCOMBAT_DAMAGE("Burn / direct"),
    LIFE_LOSS("Drain / life loss"),
    POISON("Poison"),
    MILL("Mill / decking"),
    ALT_WIN("Alt win"),
    TIMEOUT("Timeout"),
}

@Serializable
data class ConditionCount(val condition: EndCondition, val count: Int)

@Serializable
data class DeckReport(
    val deckId: String,
    val name: String,
    val commander: String,
    val colorIdentity: String,
    val role: DeckRole,
    val outcome: OutcomeStats,
    val damage: DamageStats,
    val cards: List<CardStat>,
    val removal: RemovalStats,
    val resources: ResourceStats,
    val commanderStats: CommanderStats,
    val combat: CombatStats,
    val board: BoardStats,
)

@Serializable
data class OutcomeStats(
    val games: Int,
    val wins: Int,
    val draws: Int,
    val winRate: Double,
    val ci95Low: Double,
    val ci95High: Double,
    /** Count of games finishing 1st, 2nd, 3rd, 4th. */
    val placements: List<Int>,
    val avgEliminationTurn: Double?,
    /** Index = turn number - 1, value = wins on that turn. */
    val winTurnHistogram: List<Int>,
    val winConditions: List<ConditionCount>,
    val lossCauses: List<ConditionCount>,
    /** Win rate from each seat (turn-order position), index 0 = goes first. */
    val seatWinRate: List<Double>,
)

@Serializable
data class DamageStats(
    val avgDealtToPlayers: Double,
    val avgCombat: Double,
    val avgNonCombat: Double,
    val avgCommanderDamage: Double,
    val avgDealtToCreatures: Double,
    val avgTaken: Double,
    val avgLifeGained: Double,
    val perTurnDealt: List<Double>,
    /** Avg damage dealt per game to each opposing deck (name = deck id). */
    val byTarget: List<NamedValue>,
    /** Avg damage per game by source card. */
    val topSources: List<NamedValue>,
)

@Serializable
data class CardStat(
    val name: String,
    val type: CardType,
    val manaValue: Double?,
    val seenRate: Double,
    val castRate: Double,
    val avgCastTurn: Double?,
    val castsPerGame: Double,
    val deadRate: Double,
    val counteredRate: Double,
    val winRateWhenCast: Double?,
    val winRateWhenNotCast: Double?,
    val damagePerGame: Double,
    val removalsPerGame: Double,
    val removedRate: Double,
) {
    /** Win-rate swing when this card gets cast. Positive = the deck wins more when it resolves. */
    val impact: Double?
        get() = if (winRateWhenCast != null && winRateWhenNotCast != null) winRateWhenCast - winRateWhenNotCast else null
}

@Serializable
data class RemovalStats(
    val spellsPerGame: Double,
    val permanentsRemovedPerGame: Double,
    val removedByType: List<NamedValue>,
    val receivedPerGame: Double,
    val counterspellsPerGame: Double,
    val boardWipesPerGame: Double,
    val sacrificedPerGame: Double,
    val targetsByOpponent: List<NamedValue>,
    val mostRemovedOwnCards: List<NamedValue>,
)

@Serializable
data class ResourceStats(
    val avgCardsDrawn: Double,
    val drawsPerTurn: List<Double>,
    val avgHandSizeByTurn: List<Double>,
    val avgMaxHandSize: Double,
    val avgDiscarded: Double,
    val avgLandsPlayed: Double,
    val landDropHitRate: List<Double>,
    val avgManaSpentByTurn: List<Double>,
    val mulliganRate: Double,
    val avgMulligans: Double,
    val avgMilled: Double,
)

@Serializable
data class CommanderStats(
    val castsPerGame: Double,
    val avgFirstCastTurn: Double?,
    val avgTaxPaid: Double,
    val removedPerGame: Double,
    val damagePerGame: Double,
    val winRateWhenCastByT5: Double?,
)

@Serializable
data class CombatStats(
    val attacksPerGame: Double,
    val avgAttackers: Double,
    val blocksPerGame: Double,
    val creaturesLostPerGame: Double,
)

@Serializable
data class BoardStats(
    val avgPermanentsByTurn: List<Double>,
    val avgCreaturesByTurn: List<Double>,
    val tokensPerGame: Double,
    val countersPerGame: Double,
)

@Serializable
data class MatchupStat(
    val deckId: String,
    val opponentId: String,
    val gamesTogether: Int,
    val winRate: Double,
    val damageTo: Double,
    val removalAt: Double,
)
