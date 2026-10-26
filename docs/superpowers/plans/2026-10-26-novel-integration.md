# Novel Reader Integration Implementation Plan

**Goal:** Integrate missNovel0.8.2 features into Inlay Games0.5.0 with existing baseline and common visibility controls.
**Architecture:** Adapt upstream Java reader inside plugin; original code stays under Apache2.0. Minimal Kotlin/Java bridges hide only display, not live game rooms. Independent persistent settings and bookshelf cache avoid standalone collisions.
**Tech Stack:** Existing IntelliJ SDK, Java17/Kotlin, Swing; no added runtime dependencies.

- [x] Copy upstream main source and three original core/network tests; exclude application-starter smoke entry from distribution. Add source headers, bundled license/notice and third-party documentation.
- [x] Integrate reader menu/service/startup, common Ctrl+G and mutually exclusive rendering. Keep hidden-reader mode distinct from switching to games; startup restoration respects enabled state. Adapt new Java/IDE APIs to232.
- [x] Run original sample-only tests via JUnit adapters and BasePlatform integration tests for real inlay, unchanged Document, pagination, persistence, game/reader switching and shared toggle. Existing101tests must remain green.
- [x] Build0.5.0 ZIP and run lowest-platform Plugin Verifier. Inspect bundled reader license and plugin metadata, verify no test entry/sample novels shipped.
- [ ] Update README, reader rules/help, AI install.md, checksum/update XML and verification record. Publish GitHub release and static website files, preserving older ZIPs and all live game services.
- [ ] Confirm remote release digest and public download/install metadata. Report GUI/full matrix and upstream online-provider verification limits explicitly.
