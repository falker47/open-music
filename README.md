# Open Music

**Your music. Your playlists. Your cloud.**

Android-first, local-first music player with portable playlists independent of file paths. This is an early QA milestone, **not** a released app.

## What works in the first Android QA build

- Browse and search local audio indexed by Android MediaStore.
- Play locally accessible tracks with Media3 ExoPlayer (play/pause/next/previous/seek); a MediaSessionService now owns playback so it can continue with the screen locked or another app in front (pending real-device QA).
- Create multiple local playlists, add/remove entries and preserve order and repetitions.
- Export/import portable JSON playlist v1 using the Android system document picker.
  - The picker may show Google Drive or pCloud if they expose a compatible DocumentsProvider. **This is manual export/import, not automatic cloud sync.**
- Relink imported playlists with streaming SHA-256 hashes even after renaming or moving exact files.
- Preserve missing items instead of deleting them; never automatically link a different hash.
- Save local working playlists in private app storage with atomic writes. No sign-in, tracker or hosted database.

## Current limitations

- No direct cloud streaming, OAuth, automatic synchronization or Windows client yet.
- Device QA (26 October 2026) confirmed background playback and notification-shade play/pause/previous/next. Lock-screen card visibility and slight perceived notification-control latency remain **unresolved**; this follow-up build adds [targeted QA diagnostics](docs/ANDROID_MEDIA_QA.md) and event-driven UI synchronization, not an unverified platform workaround. Reboot/process-death resumption and Android Auto browsing are not implemented.
- Only audio present in MediaStore can be scanned; arbitrary downloaded document locations may be omitted.
- Relink reads every local audio file to calculate exact hashes. Start with **5–20 small local files**, not an entire virtual pCloud drive.
- Real-device QA and provider QA are still required; JVM tests and CI compilation do not prove on-device behavior.
- Import refuses to overwrite a playlist with an existing ID. Merge/conflict resolution is future work.
- Changes to file encoding or tags can change SHA-256 and require user review.

## Technology

- Android: Kotlin, Jetpack Compose, Media3 ExoPlayer; min API 26, target API 35.
- Core: independent Kotlin/JVM module with portable models and conservative matching logic.
- Playlist format: v1 JSON compatible with the Python POC provided on 24 October 2026.
- CI: GitHub Actions, Gradle 8.13, Android Gradle Plugin 8.11.1, JDK 17.

## Getting a QA APK

**Open Music is developed in a public GitHub repository** (confirmed by the owner on 26 October 2026). When [Android build and core tests](../../actions/workflows/android.yml) succeeds on a feature-branch push, the job creates a **public experimental QA prerelease** and attaches `app-debug.apk`. Anyone may view and download the asset from **Assets**; GitHub sign-in is not required for a public release. These are **debug test builds**, not a stable app, Play Store listing or approval to merge the draft PR into `main`. Draft releases were discontinued for QA distribution because GitHub Mobile can show them as empty even with an uploaded APK. GitHub Actions artifacts are not used while the account's artifact storage quota is exhausted. Verify both CI and actual accessibility of the APK on the tester's device. Preserve existing app data: avoid uninstalling a previous version merely to install a new QA build; APK signing continuity is a separate open QA gate.

Android Studio can import this Gradle project. In CI we use the pinned Gradle distribution (no unverified Gradle wrapper binary is committed):

~~~sh
gradle :core:test :app:assembleDebug
~~~

## Portable JSON playlist schema

~~~json
{
  "kind": "portable_playlist",
  "schema_version": 1,
  "playlist_id": "some-uuid",
  "name": "Viaggio",
  "created_at": "2026-10-25T11:00:00Z",
  "updated_at": "2026-10-25T11:00:00Z",
  "tracks": [
    {
      "entry_id": "some-uuid",
      "identity": {
        "sha256": "64 hexadecimal characters for the exact file",
        "filename_hint": "example.mp3",
        "metadata": {
          "artist": "Artist",
          "title": "Song",
          "album": "Album",
          "duration_ms": 180000,
          "source": "tags"
        }
      }
    }
  ]
}
~~~

The snippet illustrates fields; actual hashes must have 64 lowercase or uppercase hex digits.
Portable JSON contains **no Android content URIs, absolute paths, credentials, OAuth tokens or audio bytes**. Sources and cached links stay on the device.

## Match decisions

| Outcome | Meaning | Action |
| --- | --- | --- |
| EXACT | Identical SHA-256 despite a moved/renamed file | Can play |
| METADATA | Old hashless playlist; unique matching trusted metadata | Can play (lower confidence) |
| REVIEW | Hash differs but metadata suggests a candidate | Never auto-link |
| AMBIGUOUS | Multiple distinct candidates | Never auto-link |
| MISSING | No eligible candidate | Preserve entry |

## Next milestones

1. Run device QA with 5–20 real local MP3/FLAC files and verify permissions, playback, restart, import/export and relink.
2. Add optional metadata-only cloud synchronization, provider permissions, revision checks and conflict handling.
3. Verify the new foreground media session service (background/lock-screen/headset); Android Auto library browsing and user-controlled cache are later steps.
4. Add direct pCloud/Google Drive playback and eventually a Windows client.

The existing personal project **our-soundtrack** is intentionally separate and remains untouched.
