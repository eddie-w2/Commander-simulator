package com.eddiew.commandersim.data.model

import kotlinx.serialization.Serializable
import kotlin.math.sqrt

/*
 * The simulation report the app displays. Field-for-field mirror of docs/STATS.md.
 * Per-game values are averages over every game the deck played; rates are 0..1;
 * "ByTurn" lists are indexed by round (index 0 = round 1).
 */

/** Two versions of a deck to compare on identical pods and shuffles. */
@Serializable
data class AbConfig(val aId: String, val bId: String)

@Serializable
data class SimConfig(
    /** Every deck in the pool. In A/B mode this is the field A and B play against. */
    val deckIds: List<String>,
    val gamesPerDeck: Int,
    val podSize: Int = 4,
    val seatRotation: Boolean = true,
    val timeoutSeconds: Int = 120,
    val ab: AbConfig? = null,
)

@Serializable
data class SimulationReport(
    val id: String,
    val createdAt: Long,
    val source: String, // "sample" until the Forge backend is connected
    val config: SimConfig,
    val decks: List<DeckReport>,
    val matchups: List<MatchupStat>,
    val meta: SimMeta,
    val ab: AbComparison? = null,
    /** Things the user should know about this run (missing cards, stopped early…). */
    val notes: List<String> = emptyList(),
    /** Games the run set out to play; 0 in reports from before v0.3.05. */
    val plannedGames: Int = 0,
) {
    /** Fewer games than planned (stopped early, interrupted or failing); can be resumed while its checkpoint exists. */
    val partial: Boolean get() = plannedGames > 0 && meta.games < plannedGames
    fun deck(id: String): DeckReport? = decks.firstOrNull { it.deckId == id }
    val ranked: List<DeckReport> get() = decks.sortedByDescending { it.outcome.winRate }
    fun name(id: String): String = deck(id)?.name ?: id
}

@Serializable
data class SimMeta(
    val games: Int,
    val timeouts: Int,
    val adjudicated: Int,
    val avgTurns: Double,
    val avgGameMs: Double,
    /** Index = rounds - 1, value = games that lasted that long. */
    val roundHistogram: List<Int> = emptyList(),
)

@Serializable
data class NamedValue(val name: String, val value: Double)


@Serializable
data class ConditionCount(val condition: EndCondition, val count: Int)

@Serializable
data class DeckReport(
    val deckId: String,
    val name: String,
    val commander: String,
    val colorIdentity: String,
    /** "A" or "B" in an A/B test, otherwise null. */
    val abSide: String? = null,
    val outcome: OutcomeStats,
    val damage: DamageStats,
    val cards: List<CardStat>,
    val removal: RemovalStats,
    val resources: ResourceStats,
    val commanderStats: CommanderStats,
    val combat: CombatStats,
    val board: BoardStats,
    val life: LifeStats,
    val winLoss: WinLossSplit,
    val threats: ThreatStats,
    val insights: List<Insight>,
    /** Mana and colour stats; only from engines that record them (the quick model). */
    val mana: ManaStats? = null,
) {
    fun card(name: String): CardStat? = cards.firstOrNull { it.name == name }
}

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
    val avgPlace: Double,
    val avgEliminationTurn: Double?,
    /** Index = round - 1, value = wins on that round. */
    val winTurnHistogram: List<Int>,
    val winConditions: List<ConditionCount>,
    val lossCauses: List<ConditionCount>,
    /** Win rate from each seat (turn order), index 0 = goes first. */
    val seatWinRate: List<Double>,
)

/** Damage from one source card of this deck. Values are per game. */
@Serializable
data class SourceDamage(
    val card: String,
    val perGame: Double,
    val combat: Double,
    val nonCombat: Double,
    val commander: Double,
    /** Total eliminations this card dealt the final point of damage for. */
    val killShots: Int,
    /** Per game, keyed by opposing deck id. */
    val perOpponent: List<NamedValue>,
    /** Avg damage per game in each round. */
    val byRound: List<Double> = emptyList(),
)

/** A card (from any deck) that dealt the final damage to a player. */
@Serializable
data class LethalSource(val card: String, val deckId: String, val count: Int)

@Serializable
data class DamageStats(
    val avgDealtToPlayers: Double,
    val avgCombat: Double,
    val avgNonCombat: Double,
    val avgCommanderDamage: Double,
    val avgDealtToCreatures: Double,
    val avgTaken: Double,
    val avgTakenCombat: Double,
    val avgTakenNonCombat: Double,
    val avgTakenCommander: Double,
    val avgLifeGained: Double,
    val perTurnDealt: List<Double>,
    val perTurnTaken: List<Double>,
    /** Avg damage dealt per game to each opposing deck (name = deck id), over games together. */
    val byTarget: List<NamedValue>,
    /** Avg damage taken per game from each opposing deck (name = deck id), over games together. */
    val takenByDeck: List<NamedValue>,
    /** Opposing cards that hurt this deck most, per game ("Card|deckId"). */
    val takenByCard: List<NamedValue>,
    val sources: List<SourceDamage>,
    val killsPerGame: Double,
    /** Cards that dealt the final damage to this deck. */
    val lethalAgainstUs: List<LethalSource>,
)

@Serializable
data class CardStat(
    val name: String,
    val type: CardType,
    val manaValue: Double?,
    /** Games the deck played. */
    val games: Int,
    val gamesSeen: Int,
    val gamesCast: Int,
    val seenRate: Double,
    val openingHandRate: Double,
    val castRate: Double,
    val avgCastTurn: Double?,
    val castsPerGame: Double,
    val deadRate: Double,
    val counteredRate: Double,
    /** Avg turns between drawing the card and first casting it. */
    val avgTurnsInHand: Double?,
    val winRateWhenSeen: Double?,
    val winRateWhenNotSeen: Double?,
    val winRateWhenCast: Double?,
    val winRateWhenNotCast: Double?,
    val winRateInOpeningHand: Double?,
    /** Index = round - 1 of first cast, value = games. */
    val castTurnHistogram: List<Int>,
    /** Win rate by round of first cast; null where fewer than 5 games. */
    val winRateByCastTurn: List<Double?>,
    val damagePerGame: Double,
    val combatDamagePerGame: Double,
    val nonCombatDamagePerGame: Double,
    val killShots: Int,
    val damageToOpponents: List<NamedValue>,
    val removalsPerGame: Double,
    val removedRate: Double,
    /** Opposing cards that removed this card, total count ("Card|deckId"). */
    val removedBy: List<NamedValue>,
    /** Share of games where it was drawn that it sat in hand affordable but missing a colour (quick model only). */
    val colorLockedRate: Double? = null,
) {
    /** Win-rate swing when this card gets cast. Positive = the deck wins more when it resolves. */
    val impact: Double?
        get() = if (winRateWhenCast != null && winRateWhenNotCast != null) winRateWhenCast - winRateWhenNotCast else null

    /** 95% interval on [impact] (normal approximation for a difference of proportions). */
    val impactInterval: Pair<Double, Double>?
        get() {
            val i = impact ?: return null
            val p1 = winRateWhenCast!!
            val p2 = winRateWhenNotCast!!
            val n1 = gamesCast.coerceAtLeast(1)
            val n2 = (games - gamesCast).coerceAtLeast(1)
            val se = sqrt(p1 * (1 - p1) / n1 + p2 * (1 - p2) / n2)
            return (i - 1.96 * se) to (i + 1.96 * se)
        }

    /** Clearly helps / clearly hurts / unclear, using the impact interval. */
    val verdict: CardVerdict
        get() {
            val iv = impactInterval ?: return CardVerdict.TOO_FEW_GAMES
            return when {
                iv.first > 0 -> CardVerdict.WINNER
                iv.second < 0 -> CardVerdict.WEAK
                else -> CardVerdict.NEUTRAL
            }
        }
}

@Serializable
enum class CardVerdict(val label: String) {
    WINNER("Drives wins"),
    WEAK("Weak point"),
    NEUTRAL("No clear effect"),
    TOO_FEW_GAMES("Too few games"),
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
    val landsInPlayByTurn: List<Double>,
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
    val winRateWhenNeverCast: Double?,
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
data class LifeStats(
    val avgLifeByTurn: List<Double>,
    val avgLifeByTurnWins: List<Double>,
    val avgLifeByTurnLosses: List<Double>,
    /** Average life of everyone else at the table. */
    val opponentsAvgLifeByTurn: List<Double>,
)

@Serializable
data class SplitSeries(val name: String, val wins: List<Double>, val losses: List<Double>)

@Serializable
data class SplitScalar(val name: String, val wins: Double, val losses: Double, val higherIsBetter: Boolean = true, val percent: Boolean = false)

/** How games this deck won differ from games it lost. */
@Serializable
data class WinLossSplit(
    val wins: Int,
    val losses: Int,
    val series: List<SplitSeries>,
    val scalars: List<SplitScalar>,
)

/** An opposing card whose resolution goes with this deck losing more often. */
@Serializable
data class NemesisStat(
    val card: String,
    val deckId: String,
    val gamesFaced: Int,
    val winRateWhenCast: Double,
    val delta: Double,
)

@Serializable
data class ThreatStats(
    val nemesisCards: List<NemesisStat>,
    val favourableCards: List<NemesisStat>,
)

@Serializable
enum class InsightKind { GOOD, BAD, INFO }

@Serializable
data class Insight(val kind: InsightKind, val title: String, val detail: String)

@Serializable
data class MatchupStat(
    val deckId: String,
    val opponentId: String,
    val gamesTogether: Int,
    val winRate: Double,
    val damageTo: Double,
    val removalAt: Double,
)

@Serializable
data class MetricDelta(
    val group: String,
    val name: String,
    val a: Double,
    val b: Double,
    val higherIsBetter: Boolean = true,
    val percent: Boolean = false,
)

@Serializable
data class AbOpponent(val deckId: String, val games: Int, val aRate: Double, val bRate: Double)

/** Paired comparison of two deck versions run on identical pods and shuffles. */
@Serializable
data class AbComparison(
    val aId: String,
    val bId: String,
    val games: Int,
    val aWins: Int,
    val bWins: Int,
    val aRate: Double,
    val bRate: Double,
    /** B minus A, with a 95% paired interval. */
    val diff: Double,
    val diffLow: Double,
    val diffHigh: Double,
    /** Games won only by A / only by B on the same seed (the games the swap changed). */
    val onlyA: Int,
    val onlyB: Int,
    val cardsOut: List<String>,
    val cardsIn: List<String>,
    val metrics: List<MetricDelta>,
    val perOpponent: List<AbOpponent>,
) {
    val significant: Boolean get() = diffLow > 0 || diffHigh < 0
}

/** One colour of a deck's mana base. */
@Serializable
data class ColorLine(
    /** "W", "U", "B", "R" or "G". */
    val color: String,
    /** Coloured pips of this colour across the deck (hybrid pips count for each colour). */
    val pips: Int,
    val pipShare: Double,
    /** Lands, rocks and dorks that can make this colour. */
    val sources: Int,
    val landSources: Int,
    val sourceShare: Double,
    /** Cards with at least one pip of this colour. */
    val cards: Int,
    /** Most pips of this colour on one card. */
    val heaviest: Int,
    /** Rough source target for [heaviest]. */
    val suggested: Int,
)

/** Static analysis of a deck list's mana. */
@Serializable
data class ManaBaseStats(
    val colors: List<ColorLine>,
    val lands: Int,
    val rocks: Int,
    val dorks: Int,
    val rampSpells: Int,
    val tappedLands: Int,
    val avgManaValue: Double,
    /** Non-land cards by mana value 0..7+ (commanders excluded). */
    val curve: List<Int>,
    /** Card counts by role (ramp, draw, removal…). */
    val roles: List<NamedValue>,
    val warnings: List<String>,
    val dataComplete: Boolean,
)

/** Simulated mana: how often the deck had its colours and its mana. */
@Serializable
data class ManaStats(
    /** Colour letter → share of games that colour was available at each of the deck's turns. */
    val colorByTurn: Map<String, List<Double>>,
    /** Share of games with every deck colour available, by turn. */
    val allColorsByTurn: List<Double>,
    /** Avg mana sources in play (lands, rocks, dorks) by turn. */
    val sourcesByTurn: List<Double>,
    /** Games with at least one turn (in the first 8) where an affordable spell was stuck for colours. */
    val colorScrewRate: Double,
    val avgColorScrewTurns: Double,
    /** Games with fewer than 3 lands on turn 4. */
    val landScrewRate: Double,
    /** Games that drew 9+ lands by turn 8 (opening hand included): flooding. */
    val floodRate: Double,
    val commanderManaValue: Int,
    /** Commander castable by the turn equal to its mana value. */
    val commanderOnCurveRate: Double?,
    val avgCommanderCastableTurn: Double?,
    /** Cards most often stuck in hand for colours (share of games drawn). */
    val colorLocked: List<NamedValue>,
    val base: ManaBaseStats,
)
