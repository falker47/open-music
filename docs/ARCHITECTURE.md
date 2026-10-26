# Open Music — Architecture and acceptance gates

**Status:** Android first QA candidate. No private music or provider account has been accessed during this work.

## Local device

Android MediaStore -> AudioFile (content URI + tags) -> Media3 ExoPlayer in PlaybackService, after a runtime audio permission is granted. The Compose activity holds only a MediaController linked to a MediaSessionService; closing the activity no longer releases the playback engine. The media foreground-service permissions are declared for playback. Media3 supplies system notification/lock-screen controls from MediaItem metadata. Screen-off/background behavior requires device QA; reboot/process-death resumption is not implemented.

Adding a file to a playlist computes a streaming SHA-256 hash in a worker coroutine. The portable JSON contains stable entry IDs, hash, metadata and optional filename hint. Its source URI is never serialized. Copies with the same exact hash may be linked after files change name or folder.

Import uses Android's system document picker and does not require provider OAuth. The user explicitly invokes **Ricollega** to compute local hashes and resolve entries. Only EXACT or METADATA may play; REVIEW, AMBIGUOUS, and MISSING remain visible but unlinked.

Working playlists are saved to app-private JSON with Android AtomicFile. A duplicate imported playlist ID is refused rather than overwritten.

Manual additions are idempotent within each playlist for a matching SHA-256 content digest. An already present recording produces feedback without changing playlist entries or timestamps; the same recording is allowed in another playlist. Imported legacy duplicates are preserved in their original order for portable v1 compatibility. Metadata-only similarity never blocks a distinct file.

## Cloud metadata synchronization — proposed, not implemented

Store only versioned portable JSON (no music) in a user-selected provider. Authentication should use least-privilege scopes or SAF, OS-protected credentials, checksums, optimistic concurrency / ETags where supported, backups and user-visible conflicts. Never choose last-writer-wins silently.

## Direct cloud music playback — proposed, not implemented

Separate logical song identity from local and remote copies. Prefer an available local file and only use authorized expiring provider links and explicit download/cache controls. Verify provider API semantics and permissions for ranges, expirations and interruptions before implementation.

## Gates

1. Pure matcher test: 50 source entries, 40 identical destination files renamed/moved, 10 missing, zero mistaken links.
2. GitHub Actions at candidate commit: core tests pass, Android debug APK assembles; the downloadable **public experimental QA prerelease** asset is available on a feature-branch build, and its download is checked by the tester. Public source/release visibility does not imply device QA success, Play Store publication, or permission to merge `main`.
3. Android device QA: permissions, scan, search, playback, seek, playlist save/restart, import/export, relink, no audio writes. Verify playback continuity after Home, screen lock and activity recreation, media notification play/pause/next/previous, Bluetooth media buttons and audio-focus/noisy-headset behavior; do not mark these PASS from a CI build alone.
4. Metadata sync QA: provider login, offline edits, concurrent changes, cross-device resume and conflict recovery.
