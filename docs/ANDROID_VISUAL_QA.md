# Open Music — Android visual QA (2 November 2026)

This pass is deliberately **visual-only** on the existing feature branch. The
player, playlist handlers, SHA-256 matching, JSON v1 format, Google Drive
authorization and manual cloud sync retain their previous behavior.

## What changed
- Dark charcoal replaces the brown-heavy background; warm amber remains the
  action accent, with artwork as the most colorful element.
- Library: a smaller, single header shows the inventory and an explicit
  **Scansiona** action. The redundant **Brani locali** heading is removed.
- More compact artwork and song rows; **Add to playlist** is visually distinct.
- Playlist: full-width title, secondary information and a separate action row
  so long names do not compete with **Trova file** and **Esporta**.
- Ordinary verified tracks no longer repeat **Disponibile**; exceptions
  (**Mancante**, **Da verificare**, **Ambiguo**, analysis) remain visible.
- Google Drive status sits in a compact card with an obvious sync action;
  the mini-player, bottom navigation, empty states and now-playing view use
  more consistent spacing.

## Manual device checks — OPEN until explicitly confirmed
- Library on a narrow display: long titles, large audio collection, artwork
  present/missing, filter chips, search input and add buttons.
- Playlist with zero/one/many lists: long names, locally missing tracks,
  **Trova file** vs **Sincronizza** and import/export.
- Mini-player and expanded player: artwork, seek, play/pause and skip, with
  gesture navigation, system buttons and keyboard/content scaling.
- Drive connected/disconnected/pending/error/conflict states must be legible.
- Inspect icon/button touch targets and TalkBack descriptions.

Build and core tests are necessary but do **not** establish this visual QA.
No Play Store promotion or merge into main before actual device feedback.

## Follow-up from actual device screenshots (3 November 2026)

- Primary action color is exactly `#ED8840`. Purple-selected filter chips are replaced with subdued orange/dark selection.
- **Scansiona** uses the folder-open icon (local media discovery); Drive uses a thin, icon+label **Sincronizza** action, without a tall pill button.
- Playlist name/track count, **Trova file** and **Esporta** share the same line; verify long names still ellipsize and both actions are tappable on narrow displays.
- A custom vertically stacked conflict dialog replaces the overlapping Material 3 AlertDialog action footer. Verify each choice and **Più tardi** are independently tappable with long/multiple cloud versions.
- Full player: drag the seek thumb and confirm the target timestamp stays visible **while dragging** and jumps to that exact point after release.
- Media3 controls: shuffle on/off, repeat-one on/off; verify playback sequence changes and current state survives opening/closing the app UI while the service is active.
- The album title appears below the artist in smaller text when present.
- **Coda** opens a sheet showing the current Media3 session's tracks; tapping an item seeks to that item. Under shuffle, the sheet explicitly says it lists the original order (the upcoming random order is not falsely predicted).

Keep previous Drive metadata-only, offline local-first and JSON v1 QA unchanged. Android compile/test is not physical UI or playback acceptance; record real-device observations separately.
