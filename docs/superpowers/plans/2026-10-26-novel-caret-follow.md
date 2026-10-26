# Optional caret following implementation plan

**Goal:** Persist a reader menu switch that keeps the novel at its current document position when disabled.
**Architecture:** Keep followCaret=true for existing users. Retain one RangeMarker across inlay rebuilds and hide/show. Reset it when enabled, state reloaded, disposed or a different document becomes active. Do not persist file paths or modify documents.
**Tech Stack:** Existing Java/IntelliJ APIs and ToggleAction; no dependencies.

- [x] Add persisted followCaret and menu ToggleAction in NovelReader, ReaderActions and plugin.xml. Use the current inlay offset when disabling; render from the marker while fixed.
- [x] Add platform tests for caret movement, paging, layout, hide/show, document insertion/deletion, file switch, state round trip, default and menu checkbox.
- [x] Run plugin tests, build ZIP and baseline verifier; update reader instructions and release metadata for0.5.1.
- [x] Submit source to ZhuGeTian/inlay-games and a focused upstream PR to datouggg/missNovel, as explicitly requested. Publish the updated package and verify download metadata.

User follow-up: rename public plugin, menus, dialogs and current documentation to 摸鱼合集. Keep plugin/action IDs, cache directories and artifact filenames unchanged for upgrade compatibility. Upstream PR retains Novel Inlay branding.

Validation:26 plugin tests and minimum-platform Plugin Verifier passed. Chinese plugin descriptor name is rejected by verifier; use Moyu Collection in plugin metadata and 摸鱼合集 for menus/documentation.

Delivery: own source/tag v0.5.1 published at a24ccbb; release asset digest and public ZIP/checksum/install.md/updatePlugins.xml match local files. Desktop copy created. Upstream fork commit a009f50 submitted as https://github.com/datouggg/missNovel/pull/1 (open, not merged); upstream build and isolated headless import/restart130 assertions passed. Game services remain active, no restart performed.
