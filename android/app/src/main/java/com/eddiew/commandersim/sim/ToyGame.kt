package com.eddiew.commandersim.sim

import com.eddiew.commandersim.data.DeckParser
import com.eddiew.commandersim.data.model.CardType
import com.eddiew.commandersim.data.model.Deck
import com.eddiew.commandersim.data.model.DeckCard
import com.eddiew.commandersim.data.model.EndCondition
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/*
 * A deliberately simplified Commander game model used ONLY to generate sample data until the
 * Forge sim-runner is connected. It is not a rules engine: cards get a coarse "role" from their
 * type, mana value and a few well-known names, and the turn loop is draw → land → cast → attack.
 * It exists so every statistic in docs/STATS.md has internally consistent numbers to display.
 */

/** Round cap of the sample model. */
internal const val TOY_MAX_ROUNDS = 16

internal enum class Role { LAND, CREATURE, REMOVAL, COUNTER, DRAW, WIPE, BURN, DRAIN, ROCK, ENGINE, OTHER }

internal class ToyCard(
    val name: String,
    val type: CardType,
    val mv: Int,
    val role: Role,
    val power: Int,
    val makesToken: Boolean,
    val evasive: Boolean,
    val isCommander: Boolean = false,
)

internal class DeckProfile(val deck: Deck, val library: List<ToyCard>, val commanders: List<ToyCard>) {
    companion object {
        private val wipeNames = listOf("wrath", "damnation", "blasphemous", "toxic deluge", "cyclonic rift", "farewell",
            "austere command", "doomskar", "vanquish the horde", "sunfall", "hour of revelation", "pernicious deed", "evacuation")
        private val counterNames = listOf("counterspell", "negate", "swan song", "fierce guardianship", "mana drain",
            "dovin's veto", "arcane denial", "an offer you can't refuse", "pact of negation", "force of will", "delay", "dispel")
        private val removalNames = listOf("swords to plowshares", "path to exile", "beast within", "chaos warp", "generous gift",
            "anguished unmaking", "vindicate", "assassin's trophy", "rapid hybridization", "pongify", "nature's claim",
            "krosan grip", "terminate", "putrefy", "infernal grasp", "go for the throat", "feed the swarm", "oblivion ring",
            "despark", "deadly rollick", "rabid bite", "song of the dryads", "darksteel mutation", "reality shift", "chupacabra")
        private val rampNames = listOf("sol ring", "arcane signet", "mind stone", "fellwar stone", "talisman", "signet",
            "cultivate", "kodama's reach", "rampant growth", "farseek", "nature's lore", "three visits", "skyshroud claim",
            "commander's sphere", "thought vessel", "wayfarer's bauble", "chromatic lantern", "mana crypt", "llanowar elves",
            "explore", "growth spiral", "sakura-tribe elder")
        private val engineNames = listOf("rhystic study", "mystic remora", "phyrexian arena", "sylvan library",
            "esper sentinel", "smothering tithe", "the one ring", "necropotence", "guardian project", "beast whisperer")

        fun from(deck: Deck): DeckProfile {
            val lib = deck.cards.flatMap { c -> List(c.quantity) { toToy(c, false) } }
            val cmd = deck.commanders.map { toToy(it, true) }
            return DeckProfile(deck, lib, cmd)
        }

        private fun hash(name: String): Int = name.lowercase().hashCode() and 0x7fffffff

        internal fun toToy(card: DeckCard, commander: Boolean): ToyCard {
            val lower = card.name.lowercase()
            val h = hash(card.name)
            val type = when {
                card.type != CardType.UNKNOWN -> card.type
                lower in DeckParser.basicLands -> CardType.LAND
                commander -> CardType.CREATURE
                else -> when (h % 100) {
                    in 0..35 -> CardType.LAND
                    in 36..65 -> CardType.CREATURE
                    in 66..74 -> CardType.INSTANT
                    in 75..81 -> CardType.SORCERY
                    in 82..92 -> CardType.ARTIFACT
                    else -> CardType.ENCHANTMENT
                }
            }
            val mv = when {
                type == CardType.LAND -> 0
                card.manaValue != null -> card.manaValue.toInt()
                else -> 1 + (h / 7) % 6
            }
            val role = when {
                type == CardType.LAND -> Role.LAND
                wipeNames.any { it in lower } -> Role.WIPE
                counterNames.any { it in lower } -> Role.COUNTER
                removalNames.any { it in lower } -> Role.REMOVAL
                rampNames.any { it in lower } -> Role.ROCK
                engineNames.any { it in lower } -> Role.ENGINE
                type == CardType.CREATURE -> Role.CREATURE
                type == CardType.INSTANT -> when (h % 100) {
                    in 0..39 -> Role.REMOVAL
                    in 40..59 -> Role.COUNTER
                    in 60..79 -> Role.DRAW
                    else -> Role.BURN
                }
                type == CardType.SORCERY -> when (h % 100) {
                    in 0..34 -> Role.REMOVAL
                    in 35..49 -> if (mv >= 4) Role.WIPE else Role.REMOVAL
                    in 50..74 -> Role.DRAW
                    in 75..87 -> Role.DRAIN
                    else -> Role.BURN
                }
                type == CardType.ARTIFACT -> if (mv <= 3) Role.ROCK else Role.ENGINE
                type == CardType.ENCHANTMENT -> when (h % 3) {
                    0 -> Role.ENGINE
                    1 -> Role.DRAIN
                    else -> Role.REMOVAL
                }
                type == CardType.PLANESWALKER -> Role.ENGINE
                else -> Role.OTHER
            }
            val power = if (role == Role.CREATURE) max(1, mv + (h % 3) - 1 + if (commander) 2 else 0) else 0
            return ToyCard(
                card.name, type, mv, role, power,
                makesToken = role == Role.CREATURE && h % 4 == 0,
                evasive = role == Role.CREATURE && h % 5 < 2,
                isCommander = commander,
            )
        }
    }
}

/** Something that can play one game between decks: the sample model or Forge. */
internal interface GamePlayer {
    /** Called once per simulation with every deck that will play. */
    fun prepare(decks: List<Deck>, control: SimControl) {}
    /** Must be safe to call from [parallelism] threads at once. */
    fun play(seats: List<Deck>, seed: Long): GameResult
    /** How many games can run at the same time. */
    val parallelism: Int get() = 1
    /** Called when the simulation ends. */
    fun close() {}
}

/** The simplified sample model. */
internal class ToyGamePlayer : GamePlayer {
    private val profiles = HashMap<String, DeckProfile>()
    override fun prepare(decks: List<Deck>, control: SimControl) { profiles.clear() }
    override fun play(seats: List<Deck>, seed: Long): GameResult =
        ToyGame(seats.map { d -> profiles.getOrPut(d.id) { DeckProfile.from(d) } }, Random(seed)).play()
}

private class Perm(val card: ToyCard, var power: Int, var sick: Boolean, val token: Boolean = false)

private class PlayerState(val seat: Int, val profile: DeckProfile, rng: Random, seats: Int) {
    val library = ArrayDeque(profile.library.shuffled(rng))
    val hand = mutableListOf<ToyCard>()
    val creatures = mutableListOf<Perm>()
    val others = mutableListOf<Perm>()
    val commandZone = profile.commanders.toMutableList()
    val cmdCastCount = HashMap<String, Int>()
    val cmdDamageFrom = IntArray(seats)
    var lands = 0
    var life = 40
    var alive = true
    var manaLeft = 0
    val track = PlayerTrack(seats)

    val rocks get() = others.count { it.card.role == Role.ROCK }
    val boardPower get() = creatures.sumOf { it.power }
}

internal class ToyGame(private val profiles: List<DeckProfile>, private val rng: Random) {
    private val n = profiles.size
    private val players = profiles.mapIndexed { i, p -> PlayerState(i, p, rng, n) }
    private var round = 0
    private var lastCause: EndCondition? = null

    fun play(): GameResult {
        players.forEach { openingHand(it) }
        var winner: Int? = null
        loop@ while (round < TOY_MAX_ROUNDS) {
            for (p in players) {
                if (!p.alive) continue
                takeTurn(p)
                val alive = players.filter { it.alive }
                if (alive.size <= 1) {
                    winner = alive.firstOrNull()?.seat
                    break@loop
                }
            }
            for (p in players) p.track.lifeByTurn[round] = if (p.alive) p.life else 0
            round++
        }
        if (round < TOY_MAX_ROUNDS) for (p in players) p.track.lifeByTurn[round] = if (p.alive) p.life else 0
        val alive = players.filter { it.alive }
        if (winner != null) {
            players[winner].track.place = 1
        } else {
            // Timeout: surviving players ranked by life, the game counts as a draw.
            alive.sortedByDescending { it.life }.forEachIndexed { i, p -> p.track.place = i + 1 }
        }
        return GameResult(winner, if (winner != null) lastCause else EndCondition.TIMEOUT, min(round + 1, TOY_MAX_ROUNDS), players.map { it.track })
    }

    private fun draw(p: PlayerState, count: Int, turnIdx: Int?) {
        repeat(count) {
            val c = p.library.removeFirstOrNull()
            if (c == null) {
                eliminate(p, EndCondition.MILL, null)
                return
            }
            p.hand += c
            val ct = p.track.card(c.name)
            if (!ct.seen) { ct.seen = true; ct.drawnRound = round }
            if (turnIdx != null) {
                p.track.drawn++
                p.track.drawsByTurn[turnIdx]++
            }
        }
    }

    private fun openingHand(p: PlayerState) {
        var mulls = 0
        while (true) {
            repeat(7) { p.library.removeFirstOrNull()?.let { p.hand += it } }
            val lands = p.hand.count { it.role == Role.LAND }
            if ((lands < 2 || lands > 5) && mulls < 2) {
                p.library.addAll(p.hand)
                p.hand.clear()
                val reshuffled = p.library.shuffled(rng)
                p.library.clear()
                p.library.addAll(reshuffled)
                mulls++
            } else break
        }
        // First mulligan is free in Commander; bottom one card per extra mulligan.
        repeat(max(0, mulls - 1)) {
            if (p.hand.isNotEmpty()) p.library.addLast(p.hand.removeAt(rng.nextInt(p.hand.size)))
        }
        p.track.mulligans = mulls
        p.hand.forEach { val ct = p.track.card(it.name); ct.seen = true; ct.inOpening = true; ct.drawnRound = 0 }
    }

    private fun takeTurn(p: PlayerState) {
        val t = round
        p.track.turnsTaken = t + 1
        p.creatures.forEach { it.sick = false }
        p.track.handByTurn[t] = p.hand.size
        if (!(round == 0 && p.seat == 0)) draw(p, 1, t)
        if (!p.alive) return

        // Upkeep-style engines: extra cards, a little life, +1/+1 counters.
        for (e in p.others.filter { it.card.role == Role.ENGINE }) {
            val h = e.card.name.hashCode() and 0x7fffffff
            if (rng.nextFloat() < 0.35f) draw(p, 1, t)
            if (h % 4 == 1) { p.life += 1; p.track.lifeGained += 1 }
            if (h % 3 == 0 && p.creatures.isNotEmpty()) {
                p.creatures.random(rng).power += 1; p.track.counterPlaced++
            }
        }
        // Drain enchantments ping every opponent each turn.
        for (e in p.others.filter { it.card.role == Role.DRAIN }) {
            for (q in opponents(p)) damagePlayer(p, q, 1, e.card, combat = false, t = t, cause = EndCondition.LIFE_LOSS)
        }
        if (!p.alive) return

        p.hand.firstOrNull { it.role == Role.LAND }?.let { land ->
            p.hand.remove(land)
            p.lands++
            p.track.landsPlayed++
            p.track.landByTurn[t] = true
            noteCast(p, land, t)
        }
        p.manaLeft = p.lands + p.rocks
        castSpells(p, t)
        if (!p.alive) return
        combat(p, t)

        while (p.hand.size > 7) {
            p.hand.removeAt(rng.nextInt(p.hand.size)); p.track.discarded++
        }
        p.track.maxHand = max(p.track.maxHand, p.hand.size)
        p.track.permsByTurn[t] = p.lands + p.others.size + p.creatures.size
        p.track.creaturesByTurn[t] = p.creatures.size
        p.track.landsInPlayByTurn[t] = p.lands
    }

    private fun noteCast(p: PlayerState, c: ToyCard, t: Int) {
        val ct = p.track.card(c.name)
        ct.casts++
        if (ct.firstCastRound < 0) ct.firstCastRound = t + 1
    }

    private fun opponents(p: PlayerState) = players.filter { it.alive && it !== p }
    private fun biggestThreat(p: PlayerState) =
        opponents(p).maxByOrNull { it.boardPower * 2 + it.others.size + (40 - it.life) / 4 }

    private fun castSpells(p: PlayerState, t: Int) {
        // Keep mana open for a counterspell if we hold one.
        val reserve = p.hand.filter { it.role == Role.COUNTER }.minOfOrNull { it.mv } ?: 0
        var progress = true
        while (progress && p.alive) {
            progress = false
            val budget = p.manaLeft - if (rng.nextFloat() < 0.6f) reserve else 0
            val cmd = p.commandZone.firstOrNull()
            if (cmd != null) {
                val tax = 2 * (p.cmdCastCount[cmd.name] ?: 0)
                if (cmd.mv + tax <= budget && (p.creatures.size < 3 || rng.nextFloat() < 0.5f)) {
                    p.commandZone.remove(cmd)
                    p.manaLeft -= cmd.mv + tax
                    p.track.manaByTurn[t] += cmd.mv + tax
                    p.cmdCastCount[cmd.name] = (p.cmdCastCount[cmd.name] ?: 0) + 1
                    p.track.cmdCasts++
                    if (p.track.cmdFirstCast < 0) p.track.cmdFirstCast = t + 1
                    noteCast(p, cmd, t)
                    if (!tryCounter(p, cmd)) resolve(p, cmd, t) else p.commandZone += cmd
                    progress = true
                    continue
                }
            }
            val playable = p.hand.filter { it.role != Role.LAND && it.role != Role.COUNTER && it.mv <= budget && wantsToCast(p, it) }
            val pick = playable.maxByOrNull { priority(it) } ?: break
            p.hand.remove(pick)
            p.manaLeft -= pick.mv
            p.track.manaByTurn[t] += pick.mv
            noteCast(p, pick, t)
            if (!tryCounter(p, pick)) resolve(p, pick, t)
            progress = true
        }
    }

    private fun priority(c: ToyCard) = when (c.role) {
        Role.ROCK -> 100 - c.mv
        Role.REMOVAL, Role.WIPE -> 60 + c.mv
        Role.ENGINE, Role.DRAW -> 50 + c.mv
        else -> 40 + c.mv
    }

    private fun wantsToCast(p: PlayerState, c: ToyCard): Boolean = when (c.role) {
        Role.REMOVAL -> opponents(p).any { it.creatures.isNotEmpty() || it.others.isNotEmpty() }
        Role.WIPE -> opponents(p).sumOf { it.creatures.size } >= p.creatures.size + 3
        else -> true
    }

    /** An opponent holding a counterspell may counter a meaningful spell. */
    private fun tryCounter(caster: PlayerState, spell: ToyCard): Boolean {
        if (spell.mv < 3) return false
        for (q in opponents(caster)) {
            val counter = q.hand.firstOrNull { it.role == Role.COUNTER && it.mv <= q.lands + q.rocks } ?: continue
            if (rng.nextFloat() < 0.45f) {
                q.hand.remove(counter)
                noteCast(q, counter, max(0, round))
                q.track.counters++
                q.track.card(counter.name).removals++
                caster.track.card(spell.name).countered++
                return true
            }
        }
        return false
    }

    private fun resolve(p: PlayerState, c: ToyCard, t: Int) {
        when (c.role) {
            Role.CREATURE -> {
                p.creatures += Perm(c, c.power, sick = true)
                if (c.makesToken) {
                    val k = 1 + rng.nextInt(2)
                    repeat(k) { p.creatures += Perm(c, 1, sick = true, token = true) }
                    p.track.tokens += k
                }
            }
            Role.ROCK, Role.ENGINE, Role.OTHER -> p.others += Perm(c, 0, sick = false)
            Role.DRAIN -> {
                if (c.type == CardType.ENCHANTMENT || c.type == CardType.ARTIFACT) p.others += Perm(c, 0, sick = false)
                drain(p, c, t)
            }
            Role.DRAW -> draw(p, 2 + rng.nextInt(2), t)
            Role.REMOVAL -> removeOne(p, c)
            Role.WIPE -> wipe(p, c)
            Role.BURN -> burn(p, c, t)
            Role.LAND, Role.COUNTER -> Unit
        }
    }

    private fun removeOne(p: PlayerState, c: ToyCard) {
        val target = biggestThreat(p) ?: return
        val victim = target.creatures.maxByOrNull { it.power + if (it.card.isCommander) 3 else 0 }
            ?: target.others.maxByOrNull { it.card.mv }
            ?: return
        p.track.removalSpells++
        p.track.removalAtSeat[target.seat]++
        p.track.card(c.name).removals++
        leaveBattlefield(target, victim, byOpponent = p, by = c)
    }

    private fun wipe(p: PlayerState, c: ToyCard) {
        var others = 0
        var total = 0
        for (q in players.filter { it.alive }) {
            for (victim in q.creatures.toList()) {
                total++
                if (q !== p) {
                    others++
                    p.track.removalAtSeat[q.seat]++
                    leaveBattlefield(q, victim, byOpponent = p, by = c)
                } else {
                    p.track.sacrificed++
                    leaveBattlefield(q, victim, byOpponent = null, by = c)
                }
            }
        }
        p.track.removalSpells++
        p.track.card(c.name).removals += others
        if (total >= 3 && others >= 2) p.track.wipes++
    }

    private fun leaveBattlefield(owner: PlayerState, victim: Perm, byOpponent: PlayerState?, by: ToyCard) {
        owner.creatures.remove(victim)
        owner.others.remove(victim)
        if (byOpponent != null) {
            byOpponent.track.permsRemoved++
            byOpponent.track.removedByType.merge(victim.card.type, 1, Int::plus)
            owner.track.received++
            if (!victim.token) {
                val ct = owner.track.card(victim.card.name)
                ct.removed++
                ct.removedBy.merge("${by.name}|${byOpponent.seat}", 1, Int::plus)
                owner.track.ownRemoved.merge(victim.card.name, 1, Int::plus)
            }
        }
        if (victim.card.isCommander && !victim.token) {
            owner.track.cmdRemoved++
            owner.commandZone += victim.card
        }
    }

    private fun burn(p: PlayerState, c: ToyCard, t: Int) {
        val amount = c.mv + 2
        val target = biggestThreat(p) ?: return
        val creature = target.creatures.filter { it.power in 3..amount }.maxByOrNull { it.power }
        if (creature != null && rng.nextFloat() < 0.4f) {
            p.track.dmgCreatures += amount
            p.track.card(c.name).removals++
            p.track.removalAtSeat[target.seat]++
            leaveBattlefield(target, creature, byOpponent = p, by = c)
        } else {
            damagePlayer(p, target, amount, c, combat = false, t = t, cause = EndCondition.NONCOMBAT_DAMAGE)
        }
    }

    private fun drain(p: PlayerState, c: ToyCard, t: Int) {
        val amount = max(1, c.mv / 2)
        val targets = opponents(p)
        for (q in targets) damagePlayer(p, q, amount, c, combat = false, t = t, cause = EndCondition.LIFE_LOSS)
        p.life += amount * targets.size
        p.track.lifeGained += amount * targets.size
    }

    private fun damagePlayer(src: PlayerState, dst: PlayerState, amount: Int, card: ToyCard, combat: Boolean, t: Int, cause: EndCondition) {
        if (!dst.alive || amount <= 0) return
        dst.life -= amount
        dst.track.taken += amount
        dst.track.takenByTurn[t] += amount
        dst.track.takenBySeat[src.seat] += amount
        dst.track.takenByCard.merge("${card.name}|${src.seat}", amount, Int::plus)
        src.track.dmgPlayers += amount
        if (combat) {
            src.track.dmgCombat += amount; dst.track.takenCombat += amount
        } else {
            src.track.dmgNonCombat += amount; dst.track.takenNonCombat += amount
        }
        src.track.dmgByTurn[t] += amount
        src.track.dmgBySeat[dst.seat] += amount
        val ct = src.track.card(card.name)
        ct.damage += amount
        if (combat) ct.combatDamage += amount else ct.nonCombatDamage += amount
        val bySeat = ct.dmgBySeat ?: IntArray(n).also { ct.dmgBySeat = it }
        bySeat[dst.seat] += amount
        val byRound = ct.dmgByRound ?: IntArray(MAX_TURNS).also { ct.dmgByRound = it }
        byRound[t] += amount
        var why = cause
        if (combat && card.isCommander) {
            src.track.dmgCommander += amount
            dst.track.takenCommander += amount
            dst.cmdDamageFrom[src.seat] += amount
            if (dst.cmdDamageFrom[src.seat] >= 21) why = EndCondition.COMMANDER_DAMAGE
        }
        if (dst.life <= 0 || why == EndCondition.COMMANDER_DAMAGE) {
            ct.killShots++
            src.track.kills++
            eliminate(dst, why, "${card.name}|${src.seat}")
        }
    }

    private fun combat(p: PlayerState, t: Int) {
        val attackers = p.creatures.filter { !it.sick }
        if (attackers.isEmpty()) return
        // Go after whoever is closest to dying, with some noise.
        val defender = opponents(p).minByOrNull { it.life + it.boardPower + rng.nextInt(12) } ?: return
        if (attackers.sumOf { it.power } * 2 < defender.boardPower && rng.nextFloat() < 0.5f) return
        p.track.attacks++
        p.track.attackersTotal += attackers.size
        val blockers = defender.creatures.toMutableList()
        for (a in attackers) {
            if (!defender.alive) break
            val blocker = if (a.card.evasive) null else blockers.filter { it.power >= a.power - 1 }.randomOrNull(rng)
            if (blocker != null && rng.nextFloat() < 0.35f) {
                blockers.remove(blocker)
                defender.track.blocks++
                p.track.dmgCreatures += a.power
                defender.track.dmgCreatures += blocker.power
                if (a.power >= blocker.power) {
                    defender.creatures.remove(blocker); defender.track.creaturesLost++
                    if (blocker.card.isCommander && !blocker.token) defender.commandZone += blocker.card
                }
                if (blocker.power >= a.power) {
                    p.creatures.remove(a); p.track.creaturesLost++
                    if (a.card.isCommander && !a.token) p.commandZone += a.card
                }
            } else {
                damagePlayer(p, defender, a.power, a.card, combat = true, t = t, cause = EndCondition.COMBAT_DAMAGE)
            }
        }
    }

    private fun eliminate(p: PlayerState, cause: EndCondition, by: String?) {
        if (!p.alive) return
        p.alive = false
        p.track.place = players.count { it.alive } + 1
        p.track.eliminatedTurn = round + 1
        p.track.lossCause = cause
        p.track.lethalBy = by
        lastCause = cause
        p.creatures.clear()
        p.others.clear()
    }
}
