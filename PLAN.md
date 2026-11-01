# Commander Matchup Simulator — Plan

**Goal:** Given a set of candidate Commander decks and a known group of opponent decks (your playgroup), estimate which candidate wins most often, and which cards drive those wins, by running large numbers of headless Forge games as fast as possible.

---

## 1. What Forge already gives us

Findings from the current Forge source (`Card-Forge/forge`, commit `9cdf52a5`, Nov 2026):

| Item | Where | What it means for us |
|---|---|---|
| Headless sim mode | `forge-gui-desktop/.../view/SimulateMatch.java`, launched as `forge sim ...` | Runs AI-vs-AI games with no UI. Supports `-f Commander`, any number of decks (4-player pods work), `-n` games, `-s` seed, `-c` timeout, `-q` quiet. This is our starting point. |
| Game timeout | `GameRules.simTimeout`, default **120 s** | Long games are scored as draws. We need to track how often this happens. |
| AI | `forge-ai` (`AiController`, `AiAttackController`, ...), optional Flat Monte Carlo (`-mc`), off by default in sims | The default heuristic AI is fast. Monte Carlo is stronger but much slower, so it is only for spot checks. |
| Card data | `forge-gui/res/cardsfolder` (~135 MB of card scripts) | `FModel.initialize` parses card data at startup, so startup cost is significant. Load it once per process and reuse it. |
| RNG | `forge-core/.../util/MyRandom.java`: one **global static** `SecureRandom` | This is slow, shared by every thread, and not reproducible unless seeded. Needs replacing. |
| ID counters | `Game`, `SpellAbility`, `Trigger`, `ReplacementEffect`, `StaticAbility`: `private static int maxId; ++maxId` | **These are not thread-safe.** Running several games on threads inside one JVM is unsafe until this is fixed. |
| License | GPL-3.0 | Our fork and the sim runner (which links Forge) must be GPL-3.0 if we distribute them. An Android app that only talks to a server over an API is separate. (Not legal advice.) |

---

## 2. Architecture

```
┌──────────────┐  deck lists + pod   ┌──────────────────┐  jobs (JSON)   ┌──────────────────────┐
│ Android app  │ ──────────────────▶ │  Orchestrator    │ ─────────────▶ │ Sim workers (N JVMs) │
│ Kotlin/Compose│ ◀── results/recs ── │  (scheduler +    │ ◀── per-game ── │  forked Forge +      │
└──────────────┘                     │   stats + API)   │    results     │  SimRunner           │
                                     └────────┬─────────┘                └──────────────────────┘
                                              ▼
                                     SQLite / Parquet results store
```

**Repo layout (proposed):**
```
/forge            git submodule → our fork of Card-Forge/forge (small, upstream-friendly patches)
/sim-runner       Java module: long-lived headless worker (depends on forge-game, forge-ai, forge-core)
/orchestrator     Python: pod scheduling, worker pool, storage, statistics, REST API
/analysis         notebooks + rating models
/android          Kotlin + Jetpack Compose client
/decks            .dck files for the playgroup and candidate decks
/bench            fixed benchmark pods + scripts
```

Simulation runs on a desktop/server, not on the phone. Forge does run on Android, but thousands of 4-player Commander games need many cores, and they would drain a phone battery. The app is the front end.

---

## 3. Making simulation fast

These are ordered by payoff divided by effort. Every step is measured against the benchmark from Milestone 0.

### Tier 1 — no Forge changes
1. **Persistent workers.** Replace "one `forge sim` process per batch" with a `SimRunner` that calls `FModel.initialize` once, then reads jobs from stdin and writes JSON lines to stdout. This removes the card-loading cost from every job.
2. **Process-level parallelism.** Run one worker JVM per physical core. This is safe today despite the static counters, because each JVM has its own statics.
3. **Quiet mode.** No game log text, `setNoGUIUser()`, and no view/event updates that nothing consumes.
4. **JVM tuning.** Use a recent JDK (21+), `-XX:+UseParallelGC` for throughput, fixed heap size, and **AppCDS** (class-data sharing archive) to cut startup time and per-JVM memory.
5. **Cheap AI only.** Flat Monte Carlo stays off for bulk runs and is used only to validate a sample.

### Tier 2 — small fork patches (good candidates to send upstream)
6. **RNG:** Replace the global `SecureRandom` with a per-game seeded `SplittableRandom`/`Random` held on `Game`. This makes it faster and makes every game exactly reproducible from its seed.
7. **Thread-safe IDs:** Change the `static int maxId` counters to `AtomicInteger` (or per-`Game` counters). Then audit the remaining static mutable state in `forge-game` and `forge-ai`.
8. **In-JVM multi-threading:** Once 6 and 7 are done, run several games per JVM on a thread pool. Card data is shared, so this uses far less memory than N JVMs. Validate it by checking that a seeded game gives identical results single-threaded and multi-threaded.
9. **Turn cap and early adjudication (optional, flagged).** End stalled games at turn X. Score them by a board/life heuristic, or count them as draws. Always record that a game was adjudicated so results can be filtered.

### Tier 3 — profiling-driven
10. Profile with **async-profiler / JFR** on the benchmark pods. Likely hotspots to check: static-ability/layer recalculation, AI attack/block evaluation in 4-player games, and mana payment search. Optimize only what the profiler shows.

### Simulate fewer games (the biggest multiplier)
11. **Adaptive sampling.** Don't run a fixed N for every pod. Use successive halving / Thompson sampling: run a small batch for every candidate, drop candidates that are clearly losing, and spend the rest of the budget on close contenders. This typically cuts total games several-fold.
12. **Seat rotation.** In Commander, turn order matters. Every pod is played in all seat orders (or balanced rotations) so turn-order luck cancels out.

---

## 4. Turning games into answers

**Per-game record** (what `SimRunner` emits):
`job_id, seed, decks[seat], winner_seat | draw, end_reason, turns, elapsed_ms, timed_out, adjudicated, eliminations[turn, seat, by_seat], cards_resolved[seat] (card, turn)`

**Models:**
- **Deck strength vs. the field:** Win rate for each candidate in pods against the playgroup decks, with a confidence interval (Wilson). In 4-player games the baseline is 25%.
- **Pairwise effects in multiplayer:** A Bradley-Terry / Plackett-Luce model over the elimination order separates "deck A beats deck B" from "deck A just won the pod".
- **Card impact (the "shuts down strategy X" question):** For each card, compare win rate when it was cast/resolved against win rate when it wasn't (similar to 17lands' GIH WR). Then break that down by which opponent deck was in the pod. This surfaces hate cards that matter specifically against one opponent.
- **Card swap search:** Swap N flex slots, re-simulate with adaptive sampling, and keep the swaps that raise win rate against the field without dropping any single matchup below a threshold.

**Sanity checks:**
- Mirror pods (4 copies of the same deck) should be about 25% per seat after seat rotation.
- Track the timeout/draw rate per deck. A deck the AI can't pilot (complex combos, politics) will show as weak for the wrong reason. Flag these decks rather than trusting their numbers.

---

## 5. Milestones

| # | Milestone | Done when |
|---|---|---|
| 0 | **Baseline.** Fork Forge, build it, and run `forge sim -f Commander` on 3 fixed benchmark pods. | Games/hour, average game length, timeout rate and peak memory are recorded in `/bench/RESULTS.md`. |
| 1 | **SimRunner + worker pool** (Tier 1). | JSON-lines output and N parallel workers. Throughput vs. baseline is measured. |
| 2 | **Results store + basic stats.** | Win rates with confidence intervals for a candidate list vs. the playgroup, from the CLI. |
| 3 | **Fork patches** (Tier 2: RNG, thread-safe IDs, in-JVM threads). | Seeded games are reproducible. Throughput per GB of RAM is measured. |
| 4 | **Adaptive sampling + seat rotation.** | Same answer as a fixed-N run, using measurably fewer games. |
| 5 | **Card impact analysis + swap search.** | A ranked list of over- and under-performing cards per matchup. |
| 6 | **API + Android app.** | Import decks (Moxfield/Archidekt text export), pick the pod, see the recommended deck and why. |
| 7 | **Profiling pass** (Tier 3). | Top hotspots addressed, with a before/after benchmark. |

## 6. Open questions
- What hardware will run the sims (home PC, cloud VM)? This sets the worker count and the cost.
- How many candidate decks and playgroup decks are typical? This sets the pod count, since 4-player pods grow combinatorially.
- Should we accept the Forge AI's skill level as the "standard player", or calibrate against real playgroup results later?
- Rules on how cards are allowed (e.g., the Commander ban list, brackets) for swap search.
