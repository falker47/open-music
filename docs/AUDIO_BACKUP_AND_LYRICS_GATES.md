# Android QA gate: Drive card, manual audio backup, licensed lyrics

Updated 4 November 2026, on the existing feat/android-local-player-mvp branch.
This is a code-level implementation, **not** a claim of real device QA.

## Drive card corrective pass

- Preserve the approved top edge and panel width. Increase the previous
  31.28 dp visual height by exactly 15% (~35.97 dp); all growth is downward.
- Keep the approved 12 dp left inset, vertical alignment and visual height.
  The controls flow right-to-left: while disconnected the Connect/Sync button
  occupies the rightmost 48 dp slot (same center as the eventual overflow menu);
  after linking, Sync moves one 48 dp slot LEFT and ⋮ occupies the rightmost
  slot. No hidden placeholder pushes the disconnected button left.
- Keep the linked menu glyph x fixed and preserve linked-state icon spacing
  exactly as previously approved; remove the extra Sync glyph offset only
  while disconnected. The full Drive status remains accessible by tapping
  the summary when the label truncates.
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

## Lyrics: local embedded tags only; Internet source remains gated

The previously removed manual text picker has not returned. Open Music now
reads lyric tags **automatically from the playing local MP3/FLAC** (when present),
with a Now Playing toggle and timing highlight if embedded timestamps exist.
The default album artwork is unchanged when no tags are available. This
requires no server, Google Drive, file modification or manual import.

No third-party Internet lyrics provider is active; free API access alone does
not establish rights to display or retain copyrighted text. The requested
optional bulk offline download cannot be enabled without a provider agreement
that explicitly allows app display and retention. Read
[the lyrics provider research and implementation gate](LYRICS_PROVIDER_RESEARCH.md)
for rights, source-by-source findings, matching rules and technical scope.
Device QA of tag readers and timed display is not claimed.

## Build/release constraints

Gradle CI runs core JUnit and Android builds on push and PR. Only a trusted
push run with the established persistent QA signing secret may publish a
prerelease APK. Verify push and PR on the exact new head, signature parity,
asset integrity and source SHA. Neither CI nor static geometry proves visual
QA, remote Drive transfer, content-provider behavior, or licensing.
