# Texas Simulation Bots Implementation Plan

> **For agentic workers:** Implement the approved spec task-by-task; independent core, server and plugin changes use the contract below, with parent integration and verification.

**Goal:** Deliver Monte Carlo Texas bots with one-/two-human starts, an installable release and matching public service.

**Architecture:** TexasBot consumes only a private TexasState and returns a legal TexasClientMessage. TexasRooms reserves a snapshot, computes outside the lock and revalidates before applying. Plugin mirrors existing DDZ start and bot markers.

**Tech Stack:** Existing Kotlin / Ktor / IntelliJ SDK; no new dependencies.

## Contract

```kotlin
object TexasBot {
    fun decide(state: TexasState, seat: Int): TexasClientMessage
}
// TexasState: bots: List<Boolean> = listOf(false, false, false)
// TEXAS_PROTOCOL_VERSION = 2
// Server: TexasRooms.tick(), command "start", /gomoku/texas/v2/ws
// Plugin: init() -> start, auto[] -> bots, version 0.4.0
```

- [x] Core: add TexasBot.kt, TexasBotTest.kt; Monte Carlo192 draws, shared-pool ties, pot-layer eligible expected returns, legal-action policy; deterministic tests and complete games. Leave TexasGame settlement unchanged.
- [x] Server: extend TexasRooms.kt/Main.kt; bot-only seats, start authority/revision/idempotency; no computation under lock; revalidation after thought; Main uses Dispatchers.Default for bot loop. Add TexasBotRoomsTest.kt and transport tests.
- [x] Plugin: extend TexasProtocol.kt, TexasActions.kt, TexasView.kt, TexasValidation.kt, GomokuSession.kt, Actions.kt endpoint mapping and plugin.xml; add controls/validation tests. Keep old route normalization and use v2 for all new Texas connections.
- [x] Integration: run `./gradlew :core:test :server:test :plugin:test :plugin:buildPlugin :server:installDist -PlocalIdePath='/Applications/IntelliJ IDEA CE.app'` under JDK21. Resolve failures and verify published artifact with minimum SDK.
- [x] Network: create scripts/texas-bots-smoke.mjs against local new server; exercise 1/2-human starts, natural finish, next hand, offline resume, no token/hand leaks; run existing smoke scripts against new routes.
- [x] Release: bump root version0.4.0; update README, docs/texas.md, array-guide.md, glossary.md, installation.md, deploy README, verification.md, AI install.md and updatePlugins.xml. Build SHA from ZIP, deploy new isolated Texas service/path, preserve old active games, publish GitHub release and download metadata atomically where possible.
- [x] Final: inspect git diff and docs links, confirm remote commit/release digest and public endpoint/package; report actual evidence and skipped GUI/matrix testing.

## Delivery evidence

2026-10-16: 101 tests passed; lowest-platform verifier Compatible; local and public one-/two-human bots and three-human smoke passed. GitHub v0.4.0 and public website ZIP SHA-256 both ca7d0055a4ab2f2c3b1f8f4588d226fc2c35a6c45e0ab050a2d34187a9d3dfea. Public AI install/update metadata match source. Existing game processes preserved; local test server stopped. Native manual GUI, full IDE matrix and concurrency load testing were not run.
