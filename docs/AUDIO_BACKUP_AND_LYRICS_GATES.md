# Android QA gate: Drive card, manual audio backup, licensed lyrics

Updated 4 November 2026, on the existing feat/android-local-player-mvp branch.
This is a code-level implementation, **not** a claim of real device QA.

## Drive card corrective pass

- Preserve the approved top edge and panel width. Increase the previous
  31.28 dp visual height by exactly 15% (~35.97 dp); all growth is downward.
- Change the left inset to 12 dp. Keep a 48 dp slot reserved for the overflow
  menu in both linked and disconnected states, so Sync cannot jump to the
  right edge when the menu is hidden.
- Keep the menu glyph x fixed and recenter the row on the visual card center.
  Sync stays within its independent 48 dp touch target. The full Drive status
  is available by tapping the summary if it truncates on narrow screens.
- Cloud glyph and semantic status colors remain. Actual rendered margins,
  fonts, Touch and TalkBack require Android QA.

## Audio backup: now reachable as explicit manual actions

- Inside the existing Drive overflow menu, "Backup file audio…" opens the
  Android SAF multiple-audio picker. The app asks for explicit confirmation;
  mobile/metered data are blocked unless the user enables that option.
- A distinct drive.file OAuth grant is requested only after that action,
  independently from the metadata playlist synchronization using drive.appdata.
- The backup workflow creates/verifies a visible Drive folder by persisted
  ID and verifies account binding by an opaque Drive permission ID. It refuses
  unexpected account switches.
- It hashes selected audio and checks exact size. The Drive inventory records
  SHA-256 in appProperties to avoid reuploading identical content. Versioned
  manifest/planner logic handles colliding names and content duplicates.
- Uploads are resumable by Drive sessions, with confirmed-offset probes after
  temporary network errors; the user can cancel the active session. Remote
  files that already completed are never deleted by cancellation.
- "Ripristina file audio…" lists recognized remote records. Selecting one
  stages a verified SHA-256 download before Android asks for a destination
  document. The resulting write is verified again. Playlist matching can then
  be recovered through the existing MediaStore rescan and SHA-256 mechanism.
- No bulk local scan, background upload, automatic remote deletion, or playlist
  JSON v1 changes. Real OAuth/transfer integration, cancellation race cases
  and cross-device restore still require explicit Android QA. Progress currently
  counts files, not streaming byte percentage.
- Remaining product work: owner-supplied final backup UI redesign, durable
  cross-process session recovery, remote quota/available storage policy,
  multi-file batch restoration, and enhanced retry/recovery controls.

## Lyrics: removed from player until an authorized automatic provider exists

The earlier local-text picker and expander were **not** the requested product.
They are removed from the visible/fullscreen player. There is no "Lyrics" UI,
no local manual text import, and no automatic provider request in this build.
Provider-independent matching and parser models remain private architectural
components only. LRCLIB's public endpoint is not enough to establish rights
to display third-party copyrighted song texts. A licensed provider, for
example a suitable Musixmatch developer contract, requires contractual rights
and credentials. No protected lyrics are bundled or transmitted.

## Build/release constraints

Gradle CI runs core JUnit and Android builds on push and PR. Only a trusted
push run with the established persistent QA signing secret may publish a
prerelease APK. Verify push and PR on the exact new head, signature parity,
asset integrity and source SHA. Neither CI nor static geometry proves visual
QA, remote Drive transfer, content-provider behavior, or licensing.
