# missNovel integration

User requested integrating https://github.com/datouggg/missNovel into the existing Inlay Games plugin, correcting an unrelated URL. Preserve upstream TXT import, local bookshelf, chapter navigation, manual/timed paging, line count, local progress and explicit online search/download. Render as comments in a block inlay, never edit source. Keep upstream license and attribution.

Use dev.gomoku.novel package and InlayGames.Novel action IDs. Store state/cache separately from the standalone Novel Inlay plugin, leaving its existing data untouched; do not silently migrate or overwrite it. Switching to reader hides game inlays but keeps rooms connected; showing a game hides the reader. Ctrl+G toggles the most recently chosen content, including when hidden. Preserve standalone paging defaults and editor font. No reading data is sent to the game server; search only runs on explicit user action.

Compile for Java17 / minimum232 SDK; replace newer Java conveniences and any unavailable SDK calls, then verify the actual bundled ZIP. Version0.5.0 includes all existing games. No server protocol or game process change is required. Publish plugin, docs, upstream attribution and install metadata after passing existing tests and focused reader integration tests.

New reader cache/settings do not automatically import standalone cache/progress. Document this and suggest disabling the standalone plugin if both render simultaneously. No GUI automation on the user's everyday IDE, no bundled copyrighted novels, no new download source or credential handling.
