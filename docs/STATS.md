# Statistics Catalog

Every statistic the simulator records, and the Forge source each one comes from. The sim-runner listens to Forge's game event bus (`forge.game.event.*`) and also takes a state snapshot at each turn start. The app's model classes (`android/app/src/main/java/com/eddiew/commandersim/data/model/Stats.kt`) mirror this catalog field for field.

Per-game values are averaged per deck across all games that deck played. Rates are in 0..1. "Per turn" series are indexed by the player's own turn number (index 0 = turn 1).

## Outcome
| Stat | Field | Source |
|---|---|---|
| Win rate + 95% CI (Wilson) | `outcome.winRate`, `ci95Low/High` | `GameEventGameOutcome.winningPlayerName` |
| Wins / draws / games | `wins`, `draws`, `games` | same; timeouts count as draws |
| Finishing position 1st–4th | `placements` | elimination order from `Player.hasLost()` checks at each state change |
| Avg turn eliminated | `avgEliminationTurn` | turn number at loss |
| Winning-turn histogram | `winTurnHistogram` | `GameEventGameOutcome.lastTurnNumber` |
| How it wins | `winConditions` | last damage/loss event before each opponent lost (see below) |
| How it loses | `lossCauses` | `GameLossReason` + last damage event against it |
| Win rate by seat (turn order) | `seatWinRate` | seat index from the pod's job |

Win/loss conditions: `COMBAT_DAMAGE`, `COMMANDER_DAMAGE`, `NONCOMBAT_DAMAGE`, `LIFE_LOSS` (drain/pay life), `POISON`, `MILL` (drew from empty library), `ALT_WIN` (card says "you win"), `TIMEOUT`.

## Damage
| Stat | Field | Source |
|---|---|---|
| Damage to players (combat / non-combat / commander) | `damage.avgDealtToPlayers`, `avgCombat`, `avgNonCombat`, `avgCommanderDamage` | `GameEventPlayerDamaged(target, source, amount, combat, infect)`; commander = source is a commander |
| Damage to creatures & planeswalkers | `avgDealtToCreatures` | `GameEventCardDamaged(card, source, amount, type)` |
| Damage taken / life gained | `avgTaken`, `avgLifeGained` | `GameEventPlayerDamaged`, `GameEventPlayerLivesChanged` (positive deltas) |
| Damage dealt per turn | `perTurnDealt` | damage events bucketed by `GameEventTurnBegan` |
| Damage to each opposing deck | `byTarget` | damage events grouped by target's deck |
| Which cards dealt it | `topSources` | `source` card of every damage event |

## Cards (one row per card in the deck)
| Stat | Field | Source |
|---|---|---|
| Seen rate (drawn or in opening hand) | `cards[].seenRate` | `GameEventCardChangeZone` Library→Hand, plus the opening hand |
| Play rate (cast/played in a game) | `castRate` | `GameEventSpellAbilityCast` (spells), `GameEventLandPlayed` (lands) |
| Avg turn first cast | `avgCastTurn` | cast events + current turn |
| Casts per game | `castsPerGame` | cast events (recasts and commander included) |
| Dead-card rate (seen, never cast) | `deadRate` | seen minus cast |
| Countered / fizzled rate | `counteredRate` | `GameEventSpellResolved.hasFizzled`, `GameEventSpellRemovedFromStack` |
| Win rate when cast vs. not cast | `winRateWhenCast`, `winRateWhenNotCast`, `impact` | joined with outcome |
| Damage per game | `damagePerGame` | damage events with this card as source |
| Opposing permanents removed per game | `removalsPerGame` | see Removal attribution below |
| Times this card got removed (per cast) | `removedRate` | same, in reverse |

## Removal & interaction
| Stat | Field | Source |
|---|---|---|
| Removal spells cast | `removal.spellsPerGame` | resolved spells that removed an opposing permanent |
| Opposing permanents removed, by type | `permanentsRemovedPerGame`, `removedByType` | `GameEventCardChangeZone` Battlefield→Graveyard/Exile/Library/Hand during an opponent-controlled resolution, plus `GameEventCardDestroyed`, `GameEventCardSacrificed` |
| Our permanents removed by opponents | `receivedPerGame` | same, in reverse |
| Counterspells cast | `counterspellsPerGame` | stack items removed by our resolving spell |
| Board wipes | `boardWipesPerGame` | one resolution that removes 3+ permanents, including 2+ of other players' |
| Sacrificed (own) | `sacrificedPerGame` | `GameEventCardSacrificed` |
| Removal aimed at each opposing deck | `targetsByOpponent` | removal grouped by owner's deck |
| Our most-removed cards | `mostRemovedOwnCards` | removal grouped by card |

**Removal attribution:** The runner tracks which spell or ability is resolving (between `GameEventSpellAbilityCast` and `GameEventSpellResolved`, and during trigger resolution). Any permanent that leaves the battlefield while that's happening is credited to the controller of the resolving item. State-based deaths after combat damage are credited to the damage source.

## Resources & card flow
| Stat | Field | Source |
|---|---|---|
| Cards drawn per game | `resources.avgCardsDrawn` | Library→Hand zone changes outside the opening hand |
| Draws per turn | `drawsPerTurn` | bucketed by turn |
| Hand size at start of each turn | `avgHandSizeByTurn` | snapshot on `GameEventTurnBegan` |
| Avg max hand size | `avgMaxHandSize` | max over the snapshots |
| Cards discarded | `avgDiscarded` | Hand→Graveyard not via cast |
| Lands played / land-drop hit rate by turn | `avgLandsPlayed`, `landDropHitRate` | `GameEventLandPlayed` |
| Mana spent per turn | `avgManaSpentByTurn` | sum of the CMC of spells cast each turn (approximation) |
| Mulligan rate / avg mulligans | `mulliganRate`, `avgMulligans` | `GameEventMulligan` |
| Cards milled | `avgMilled` | Library→Graveyard |

## Commander
| Stat | Field | Source |
|---|---|---|
| Commander casts per game | `commander.castsPerGame` | cast events where the card is a commander |
| First cast turn | `avgFirstCastTurn` | same |
| Commander tax paid | `avgTaxPaid` | 2 × (casts − 1) per game |
| Times commander removed | `removedPerGame` | Battlefield→Command zone / Graveyard / Exile |
| Commander damage dealt | `damagePerGame` | `Player.getCommanderDamage()` at game end |
| Win rate when cast by turn 5 | `winRateWhenCastByT5` | joined with outcome |

## Combat & board
| Stat | Field | Source |
|---|---|---|
| Attacks per game / avg attackers | `combat.attacksPerGame`, `avgAttackers` | `GameEventAttackersDeclared` |
| Blocks per game | `blocksPerGame` | `GameEventBlockersDeclared` |
| Creatures lost in combat | `creaturesLostPerGame` | creature deaths during the combat damage step |
| Permanents / creatures on board by turn | `board.avgPermanentsByTurn`, `avgCreaturesByTurn` | turn-start snapshot |
| Tokens created / counters placed | `tokensPerGame`, `countersPerGame` | `GameEventTokenCreated`, `GameEventCardCounters` |

## Matchups (deck × opposing deck)
| Stat | Field |
|---|---|
| Games in the same pod | `matchups[].gamesTogether` |
| Win rate of the deck when the opponent is at the table | `winRate` |
| Damage dealt to that opponent per game | `damageTo` |
| Removal aimed at that opponent per game | `removalAt` |

## Simulation meta
| Stat | Field |
|---|---|
| Games played, timeouts, adjudicated games | `meta.games`, `timeouts`, `adjudicated` |
| Avg game length (turns and wall-clock ms) | `avgTurns`, `avgGameMs` |

## Ideas for later
- Tutor targets (what the deck searched for)
- Mana screw and flood rates (land counts vs. turn)
- "Threat assessment": which deck's permanents the AI attacked or removed most
- Time-to-first-threat, and the turn when total board power first reaches 10
