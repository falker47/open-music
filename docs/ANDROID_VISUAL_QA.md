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

## Session queue and visual identity — 3 November 2026

Selected real/visual references (inspiration only, no image assets copied):

- Spotify player/library/queue structure, via screenshot-based wireframes:
  https://whimsical.com/templates/music-streaming-app-wireframe
  Useful pattern: stable mini-player, compact music rows, dedicated upcoming queue.
- Refero, Luminary mini-player/playlist pattern:
  https://refero.design/screens/43afd085-26bb-42c9-8770-de044c756af4
  Useful pattern: artwork as primary visual anchor and music metadata hierarchy.
- Dribbble, Sonic Curator concept:
  https://dribbble.com/shots/27756026-Music-Player-App-UI-Mobile-App-Kit
  Useful color direction: charcoal and warm amber. Its oversized membership
  widgets/cards are *not* adopted; it is a concept, not a production reference.

Original Open Music implementation: primary `#ED8840`, restrained charcoal
surfaces, smaller now-playing title, artwork-led layout without decorative
gradient. Repeat is left of Previous; Shuffle is right of Next.

The queue sheet is session-scoped and uses the physical Media3 timeline rather
than showing an inaccurate original order when shuffled. Current track first,
then future tracks. Accessible arrow buttons supplement long-press dragging.
The library and playlist rows expose **Add to queue** separately from **Add to
playlist**. A track picker can construct a queue from library or saved lists;
editing the queue does not change the saved playlists.

New device checks — still OPEN:
- Small screen / TalkBack: header, queue button, playlist deletion action and
  modal list/dialog fit; long titles and 50+ songs remain navigable.
- Player left-to-right: Repeat, Previous, Play/Pause, Next, Shuffle; seek preview
  still works with screen rotation/recreation.
- Queue with shuffle OFF/ON: compare the next five rows to actual next tracks
  as playback advances, including after Home and reopening Activity.
- Reorder future tracks with long press and accessible up/down arrows. Current
  track plays continuously; Media3 notification Next follows the displayed order.
- Remove future item, add songs from Library and Playlist, and open empty queue;
  saved playlists must stay byte-for-byte unchanged.
- Repeat-one: visible warning in queue and no implicit advance.
- Multiple playlists: delete one only after explicit name confirmation; ensure
  music files and other playlists persist and the selection updates.
- Drive divergence: local/remote details, no automatic newest-version choice,
  append-only deletion when explicitly selected, manual restore and both.
