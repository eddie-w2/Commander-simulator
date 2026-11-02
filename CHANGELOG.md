# Changelog

## v0.1.02
- **Layered analytics:** every stat tile opens its own detail screen. Each detail screen has the value, its rank across all decks, a comparison bar for every deck, the wins-vs-losses gap, and related metrics. It also has breakdowns specific to that kind of stat:
  - **Damage:** which cards dealt it (total / combat / non-combat / kills), damage by round for the top cards, damage per opponent, and the card × opponent matrix.
  - **Damage taken:** who and what hurts you, final blows, and life by round.
  - **Removal:** your removal cards, what you remove, who you target, your cards that get removed and by what, and countered spells.
  - **Card flow:** draws, hand size, dead cards, cards held longest, and win rate when drawn vs. not.
  - **Mana:** lands, land-drop rate, mana spent, ramp timing, and mana value vs. round cast.
  - **Commander:** cast timing, win rate by cast round, damage, and what removes it.
  - **Combat & board**, plus **game length** for the whole run.
- **Owned decks:** tag a deck as owned on import or from the deck screen.
- **Collection tab:** for owned decks:
  - The most shared cards, and the extra copies you'd need to build every deck at once.
  - A deck-overlap matrix and the most similar deck pairs.
  - Color breakdowns per deck and across all owned decks (every copy or unique cards, lands on/off).
  - Commander identities and card types.
- **Backup & restore:** saves one JSON file with all decks, owned tags, versions, settings and the latest results. You can restore it by merging or replacing.
- **Link sync:** decks imported from Moxfield/Archidekt can be re-synced, one at a time or all at once. The previous list is saved as a version before every change, and any version can be restored.
- Settings screen; GitHub Releases now carry the APK.

## v0.1.01
- Deck pool replaces candidate/opponent; A/B variant testing on identical games.
- Moxfield/Archidekt link import; automatic commander detection (99+1 / 98+2).
- Granular stats: per-card damage, kill shots, nemesis cards, wins vs losses, card detail.

## v0.1.0
- First build: deck import, sample simulator, results dashboards.
