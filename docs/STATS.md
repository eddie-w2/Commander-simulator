# Statistics Catalog

Every statistic the simulator records, and the Forge source each one comes from. The sim-runner listens to Forge's game event bus (`forge.game.event.*`) and also takes a state snapshot at the start of each round. The app's model classes (`android/app/src/main/java/com/eddiew/commandersim/data/model/Stats.kt`) mirror this catalog field for field.

Per-game values are averaged per deck across all games that deck played. "Per game together" values are averaged only over games where both decks were at the table. Rates are 0..1. "By round" series are indexed by round (index 0 = round 1).

## How games are scheduled
- **Pool mode:** every selected deck plays the others in 4-player pods (or fewer if the pool is smaller). Each game takes the decks with the fewest games so far, so every deck plays about the same number. Seats are shuffled every game.
- **A/B mode:** deck A and deck B (usually a variant with swapped cards) each play the same pods against the field. Every game is played twice with the **same opponents, seat and shuffle seed**, once with A and once with B, so the only difference between the two games is the swapped cards. In Forge this means passing the same `-s` seed to both runs.

## Outcome
| Stat | Field | Source |
|---|---|---|
| Win rate + 95% CI (Wilson) | `outcome.winRate`, `ci95Low/High` | `GameEventGameOutcome.winningPlayerName` |
| Wins / draws / games | `wins`, `draws`, `games` | same; timeouts count as draws |
| Finishing position 1st–4th, avg place | `placements`, `avgPlace` | elimination order from `Player.hasLost()` |
| Avg round eliminated | `avgEliminationTurn` | round at loss |
| Winning-round histogram | `winTurnHistogram` | `GameEventGameOutcome.lastTurnNumber` |
| How it wins / loses | `winConditions`, `lossCauses` | last damage/loss event before each elimination |
| Win rate by seat | `seatWinRate` | seat index from the job |

Win/loss conditions: `COMBAT_DAMAGE`, `COMMANDER_DAMAGE`, `NONCOMBAT_DAMAGE`, `LIFE_LOSS`, `POISON`, `MILL`, `ALT_WIN`, `TIMEOUT`.

## Damage dealt
| Stat | Field | Source |
|---|---|---|
| To players: total / combat / non-combat / commander | `damage.avgDealtToPlayers`, `avgCombat`, `avgNonCombat`, `avgCommanderDamage` | `GameEventPlayerDamaged(target, source, amount, combat, infect)` |
| To creatures & planeswalkers | `avgDealtToCreatures` | `GameEventCardDamaged` |
| Per round | `perTurnDealt` | damage events bucketed by `GameEventTurnBegan` |
| To each opposing deck | `byTarget` | grouped by target's deck |
| Kills (final blows dealt) per game | `killsPerGame` | the damage event that took a player to ≤0 life or 21 commander damage |
| **Per source card**: total, combat, non-combat, commander, kill shots, per opponent | `sources[]` | `source` card of every damage event |

## Damage taken
| Stat | Field | Source |
|---|---|---|
| Taken: total / combat / non-combat / commander | `avgTaken`, `avgTakenCombat`, `avgTakenNonCombat`, `avgTakenCommander` | `GameEventPlayerDamaged` with this player as target |
| Per round | `perTurnTaken` | bucketed by round |
| From each opposing deck | `takenByDeck` | grouped by source's controller |
| Opposing cards that hurt most | `takenByCard` ("Card\|deckId") | grouped by source card |
| Final blow against this deck | `lethalAgainstUs` | card + deck that dealt the lethal damage |
| Life gained | `avgLifeGained` | positive `GameEventPlayerLivesChanged` deltas |

## Life
| Stat | Field | Source |
|---|---|---|
| Life by round (all games, wins only, losses only) | `life.avgLifeByTurn`, `avgLifeByTurnWins`, `avgLifeByTurnLosses` | snapshot at end of each round; 0 once eliminated |
| Opponents' average life by round | `opponentsAvgLifeByTurn` | same snapshot, other seats |

## Cards (one row per card in the deck)
| Stat | Field | Source |
|---|---|---|
| Drawn rate / opening-hand rate | `seenRate`, `openingHandRate` | Library→Hand `GameEventCardChangeZone`, opening hand |
| Play rate, casts per game, avg first-cast round | `castRate`, `castsPerGame`, `avgCastTurn` | `GameEventSpellAbilityCast`, `GameEventLandPlayed` |
| First-cast round histogram | `castTurnHistogram` | same |
| Dead rate (drawn, never cast) | `deadRate` | drawn minus cast |
| Rounds held in hand before casting | `avgTurnsInHand` | round drawn → round cast |
| Countered rate | `counteredRate` | `GameEventSpellResolved.hasFizzled`, `GameEventSpellRemovedFromStack` |
| Win rate when cast / not cast / drawn / not drawn / in opening hand | `winRateWhenCast`, `winRateWhenNotCast`, `winRateWhenSeen`, `winRateWhenNotSeen`, `winRateInOpeningHand` | joined with outcome (null under 5 games) |
| **Impact** + 95% interval + verdict | `impact`, `impactInterval`, `verdict` | cast vs. not-cast win rate; WINNER / WEAK when the interval excludes zero |
| Win rate by first-cast round | `winRateByCastTurn` | joined with outcome |
| Damage: total / combat / non-combat, kill shots, per opponent | `damagePerGame`, `combatDamagePerGame`, `nonCombatDamagePerGame`, `killShots`, `damageToOpponents` | damage events with this card as source |
| Opposing permanents removed / countered per game | `removalsPerGame` | see Removal attribution |
| Times removed per cast, and by which opposing cards | `removedRate`, `removedBy` | same, in reverse |

## Removal & interaction
| Stat | Field | Source |
|---|---|---|
| Removal spells cast | `removal.spellsPerGame` | resolved spells that removed an opposing permanent |
| Opposing permanents removed, by type | `permanentsRemovedPerGame`, `removedByType` | Battlefield→Graveyard/Exile/Library/Hand during an opponent-controlled resolution, plus `GameEventCardDestroyed`, `GameEventCardSacrificed` |
| Own permanents lost to opponents | `receivedPerGame` | same, in reverse |
| Counterspells, board wipes, sacrificed | `counterspellsPerGame`, `boardWipesPerGame`, `sacrificedPerGame` | stack removals; one resolution removing 3+ permanents (2+ of others'); `GameEventCardSacrificed` |
| Removal aimed at each opposing deck | `targetsByOpponent` | grouped by owner's deck |
| Own cards removed most | `mostRemovedOwnCards` | grouped by card |

**Removal attribution:** the runner tracks which spell or ability is resolving (between `GameEventSpellAbilityCast` and `GameEventSpellResolved`, and during trigger resolution). Any permanent that leaves the battlefield while it resolves is credited to that item's controller and card. State-based deaths after combat damage are credited to the damage source.

## Threats
| Stat | Field | Source |
|---|---|---|
| **Nemesis cards**: opposing cards whose resolution goes with this deck losing more | `threats.nemesisCards` | for each opposing card cast in this deck's games: this deck's win rate in those games minus its overall win rate (min. 10 games or 5% of games) |
| Opposing cards the deck handles well | `threats.favourableCards` | same, positive delta |

## Wins vs. losses
| Stat | Field | Source |
|---|---|---|
| Series split by won/lost games: damage per round, life, lands in play, mana spent, permanents, hand size | `winLoss.series` | per-round snapshots |
| Per-game scalars split by won/lost games: cards drawn, removal cast, permanents removed/lost, damage dealt/taken, mulligans, commander first-cast round, lands played, counterspells | `winLoss.scalars` | per-game totals |

## Resources & card flow
| Stat | Field | Source |
|---|---|---|
| Cards drawn per game and per round | `resources.avgCardsDrawn`, `drawsPerTurn` | Library→Hand outside the opening hand |
| Hand size at start of round, avg max | `avgHandSizeByTurn`, `avgMaxHandSize` | snapshot on `GameEventTurnBegan` |
| Discarded, milled | `avgDiscarded`, `avgMilled` | Hand→Graveyard not via cast; Library→Graveyard |
| Lands played, lands in play by round, land-drop hit rate | `avgLandsPlayed`, `landsInPlayByTurn`, `landDropHitRate` | `GameEventLandPlayed`, snapshot |
| Mana spent per round | `avgManaSpentByTurn` | sum of the mana value of spells cast (approximation) |
| Mulligans | `mulliganRate`, `avgMulligans` | `GameEventMulligan` |

## Commander
| Stat | Field | Source |
|---|---|---|
| Casts per game, first-cast round, tax paid | `commander.castsPerGame`, `avgFirstCastTurn`, `avgTaxPaid` | cast events where the card is a commander |
| Times removed | `removedPerGame` | Battlefield→Command zone / Graveyard / Exile |
| Commander damage dealt | `damagePerGame` | `Player.getCommanderDamage()` |
| Win rate when cast by round 5 / never cast | `winRateWhenCastByT5`, `winRateWhenNeverCast` | joined with outcome |

## Combat & board
| Stat | Field | Source |
|---|---|---|
| Attacks per game / avg attackers / blocks | `combat.*` | `GameEventAttackersDeclared`, `GameEventBlockersDeclared` |
| Creatures lost in combat | `creaturesLostPerGame` | deaths during combat damage |
| Permanents / creatures by round | `board.avgPermanentsByTurn`, `avgCreaturesByTurn` | round-start snapshot |
| Tokens / counters | `tokensPerGame`, `countersPerGame` | `GameEventTokenCreated`, `GameEventCardCounters` |

## Matchups (deck × deck)
| Stat | Field |
|---|---|
| Games in the same pod, win rate with that deck at the table, damage to it, removal at it | `matchups[]` |

## A/B comparison
| Stat | Field |
|---|---|
| A and B win rates; B − A difference with paired 95% CI | `ab.aRate`, `bRate`, `diff`, `diffLow`, `diffHigh`, `significant` |
| Games only A won / only B won on the same seed (the games the swap decided) | `onlyA`, `onlyB` |
| Cards out (in A) and in (in B) | `cardsOut`, `cardsIn` (each card's full stats are in the A and B deck reports) |
| Side-by-side metric deltas (outcome, damage, cards, interaction, board) | `metrics[]` |
| A vs. B win rate with each field deck at the table | `perOpponent[]` |

## Insights
Generated text findings per deck (`insights`): cards that clearly drive wins, clear weak points, cards that get stuck in hand, the top nemesis card, the top damage source, the most common final blow, and commander timing.

## Simulation meta
| Stat | Field |
|---|---|
| Games played, timeouts, adjudicated games | `meta.games`, `timeouts`, `adjudicated` |
| Avg game length (rounds and wall-clock ms) | `avgTurns`, `avgGameMs` |
