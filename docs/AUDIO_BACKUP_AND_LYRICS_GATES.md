# Audio backup & lyrics — gated implementation (4 November 2026)

This file documents code delivered on feature branch feat/android-local-player-mvp.
It does not claim working device OAuth, live audio transfer or licensed
third-party lyrics presentation.

## Audio backup — isolated from playlist metadata

- The existing metadata sync continues to use drive.appdata and playlist JSON v1.
  No local paths, URI strings, OAuth tokens or audio bytes enter playlist exports.
- AudioBackupPlanner is pure Kotlin: validates a versioned backup manifest,
  account marker and stable folder ID; deduplicates by SHA-256, checks name
  collisions, tracks confirmed offsets and verifies downloaded bytes against
  hash plus expected size. Tests use synthetic strings rather than private audio.
- AudioBackupManifestCodec encodes a versioned remote inventory without local
  file locations. The distinct AudioBackupAuthorization launcher requests the
  least-privileged drive.file scope on explicit invocation.
- DriveAudioBackupGateway supports folder creation/verification by ID,
  resumable upload session initiation, status probe after uncertain chunks,
  256 KiB-aligned chunk writes, session cancellation and SHA-checked staging
  of remote downloads in a temporary private file. No user file is deleted.
- The backup components are **not** invoked automatically and there is no
  new backup screen yet. Completing the end-to-end feature still requires:
  an owner-approved UI, persisting folder/account/session state, mobile-data
  and quota guardrails, SAF destination selection, failure recovery and live
  user-authorized device transfer tests. None is claimed as operational.
- Do not use the hidden appDataFolder to hold music; do not expand the
  ordinary playlist OAuth grant or launch audio uploads automatically.

## Lyrics — local support with licensing gate

- Fullscreen now-playing includes a collapsed-by-default lyrics expander,
  inspired only by the layout/interaction of Our Soundtrack. Its read-only
  source repository was not edited.
- Users may import their own text through Android SAF. UTF-8 .txt and .lrc
  parsing is bounded to 256 KiB, and content is atomically stored inside
  the app's private no-backup directory, keyed by an opaque hash. Lyrics are
  not exported to portable playlists, nor does the player upload private audio.
- Track-scoped UI state prevents the previous song's text appearing when
  playback changes quickly. Static text works; LRC timestamps are parsed
  but timed highlighting has not been enabled.
- Provider-independent candidate matching uses title/artist and cautious
  album/duration validation, preserving live, remix and cover qualifiers.
  An abstract external provider API exists, but **no external provider is
  enabled** and no copyrighted lyrics are bundled.
- A publicly reachable LRCLIB API does not itself establish rights for
  public display of third-party lyrics. Enable external lyrics only after
  documented permissions or an appropriate licensed provider contract.

## Verification and release

CI on this branch runs core JUnit and Android debug/QA builds. The push
workflow can publish a prerelease APK only with its established persistent
QA signing secret; PR jobs use disposable certificates that must never be
distributed. Device visuals, TalkBack, real OAuth, mobile data use, actual
Google Drive transfers and display rights require independent verification.
