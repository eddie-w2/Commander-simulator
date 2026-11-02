package com.eddiew.commandersim.ui.results

import com.eddiew.commandersim.data.model.DeckReport
import com.eddiew.commandersim.ui.components.num
import com.eddiew.commandersim.ui.components.pct

/** Which detail screen layout a metric opens. */
enum class MetricFamily { OUTCOME, DEALT, TAKEN, REMOVAL, CARD_FLOW, MANA, COMMANDER, COMBAT, BOARD, GAMES }

/**
 * Every stat that can be tapped. [value] reads it from a deck report so the detail screen can show it and compare
 * it across all decks; [winLossKey] links it to the matching wins-vs-losses scalar when there is one.
 */
enum class Metric(
    val title: String,
    val family: MetricFamily,
    val unit: String,
    val higherIsBetter: Boolean,
    val percent: Boolean = false,
    val winLossKey: String? = null,
    val value: (DeckReport) -> Double?,
) {
    WIN_RATE("Win rate", MetricFamily.OUTCOME, "of games", true, percent = true, value = { it.outcome.winRate }),
    AVG_PLACE("Average finishing place", MetricFamily.OUTCOME, "of 4", false, value = { it.outcome.avgPlace }),
    ELIMINATION("Round eliminated", MetricFamily.OUTCOME, "avg round", true, value = { it.outcome.avgEliminationTurn }),

    DAMAGE_DEALT("Damage dealt to players", MetricFamily.DEALT, "per game", true, winLossKey = "Damage dealt", value = { it.damage.avgDealtToPlayers }),
    DAMAGE_COMBAT("Combat damage", MetricFamily.DEALT, "per game", true, value = { it.damage.avgCombat }),
    DAMAGE_NONCOMBAT("Non-combat damage", MetricFamily.DEALT, "per game", true, value = { it.damage.avgNonCombat }),
    DAMAGE_COMMANDER("Commander damage", MetricFamily.DEALT, "per game", true, value = { it.damage.avgCommanderDamage }),
    DAMAGE_CREATURES("Damage to creatures", MetricFamily.DEALT, "per game", true, value = { it.damage.avgDealtToCreatures }),
    KILLS("Kills (final blows)", MetricFamily.DEALT, "per game", true, value = { it.damage.killsPerGame }),

    DAMAGE_TAKEN("Damage taken", MetricFamily.TAKEN, "per game", false, winLossKey = "Damage taken", value = { it.damage.avgTaken }),
    TAKEN_COMBAT("Combat damage taken", MetricFamily.TAKEN, "per game", false, value = { it.damage.avgTakenCombat }),
    TAKEN_NONCOMBAT("Non-combat damage taken", MetricFamily.TAKEN, "per game", false, value = { it.damage.avgTakenNonCombat }),
    TAKEN_COMMANDER("Commander damage taken", MetricFamily.TAKEN, "per game", false, value = { it.damage.avgTakenCommander }),
    LIFE_GAINED("Life gained", MetricFamily.TAKEN, "per game", true, value = { it.damage.avgLifeGained }),

    REMOVAL_CAST("Removal cast", MetricFamily.REMOVAL, "per game", true, winLossKey = "Removal cast", value = { it.removal.spellsPerGame }),
    REMOVED("Opposing permanents removed", MetricFamily.REMOVAL, "per game", true, winLossKey = "Opposing permanents removed", value = { it.removal.permanentsRemovedPerGame }),
    LOST("Own permanents lost", MetricFamily.REMOVAL, "per game", false, winLossKey = "Own permanents lost", value = { it.removal.receivedPerGame }),
    COUNTERSPELLS("Counterspells", MetricFamily.REMOVAL, "per game", true, winLossKey = "Counterspells", value = { it.removal.counterspellsPerGame }),
    WIPES("Board wipes", MetricFamily.REMOVAL, "per game", true, value = { it.removal.boardWipesPerGame }),
    SACRIFICED("Own permanents sacrificed", MetricFamily.REMOVAL, "per game", false, value = { it.removal.sacrificedPerGame }),

    CARDS_DRAWN("Cards drawn", MetricFamily.CARD_FLOW, "per game", true, winLossKey = "Cards drawn", value = { it.resources.avgCardsDrawn }),
    MAX_HAND("Max hand size", MetricFamily.CARD_FLOW, "avg", true, value = { it.resources.avgMaxHandSize }),
    DISCARDED("Cards discarded", MetricFamily.CARD_FLOW, "per game", false, value = { it.resources.avgDiscarded }),
    MILLED("Cards milled", MetricFamily.CARD_FLOW, "per game", false, value = { it.resources.avgMilled }),

    LANDS_PLAYED("Lands played", MetricFamily.MANA, "per game", true, winLossKey = "Lands played", value = { it.resources.avgLandsPlayed }),
    MULLIGANS("Mulligan rate", MetricFamily.MANA, "of games", false, percent = true, winLossKey = "Mulligans", value = { it.resources.mulliganRate }),
    MANA_SPENT("Mana spent by round 6", MetricFamily.MANA, "total", true, value = { it.resources.avgManaSpentByTurn.take(6).sum() }),

    COMMANDER_CAST("Commander first cast", MetricFamily.COMMANDER, "avg round", false, winLossKey = "Commander first cast (round)", value = { it.commanderStats.avgFirstCastTurn }),
    COMMANDER_CASTS("Commander casts", MetricFamily.COMMANDER, "per game", true, value = { it.commanderStats.castsPerGame }),
    COMMANDER_TAX("Commander tax paid", MetricFamily.COMMANDER, "mana per game", false, value = { it.commanderStats.avgTaxPaid }),
    COMMANDER_REMOVED("Commander removed", MetricFamily.COMMANDER, "per game", false, value = { it.commanderStats.removedPerGame }),

    ATTACKS("Attacks", MetricFamily.COMBAT, "per game", true, value = { it.combat.attacksPerGame }),
    ATTACKERS("Attackers per attack", MetricFamily.COMBAT, "avg", true, value = { it.combat.avgAttackers }),
    BLOCKS("Blocks", MetricFamily.COMBAT, "per game", true, value = { it.combat.blocksPerGame }),
    COMBAT_LOSSES("Creatures lost in combat", MetricFamily.COMBAT, "per game", false, value = { it.combat.creaturesLostPerGame }),

    BOARD("Permanents at round 6", MetricFamily.BOARD, "avg", true, value = { it.board.avgPermanentsByTurn.getOrElse(5) { 0.0 } }),
    TOKENS("Tokens created", MetricFamily.BOARD, "per game", true, value = { it.board.tokensPerGame }),
    COUNTERS_PLACED("+1/+1 counters placed", MetricFamily.BOARD, "per game", true, value = { it.board.countersPerGame }),

    /** Report-wide, not tied to one deck. */
    GAMES("Games and game length", MetricFamily.GAMES, "", true, value = { it.outcome.games.toDouble() });

    fun format(v: Double?): String = when {
        v == null -> "—"
        percent -> pct(v)
        else -> num(v, if (kotlin.math.abs(v) >= 100) 0 else if (kotlin.math.abs(v) >= 10) 1 else 2)
    }
}
