# Open Music

**Your music. Your playlists. Your cloud.**

Android-first, local-first music player with portable playlists independent of file paths. This is an early QA milestone, **not** a released app.

## What works in the first Android QA build

- Browse and search two complementary local categories, **Musica** and **Altri audio**, with persistent, reversible manual classification. All audio remains in the SHA-256 matching inventory.
- Play locally accessible tracks with Media3 ExoPlayer (play/pause/next/previous/seek); a MediaSessionService now owns playback so it can continue with the screen locked or another app in front (pending real-device QA).
- Create multiple local playlists, add/remove entries and preserve order and repetitions.
- Export/import portable JSON playlist v1 using the Android system document picker.
  - The picker may show Google Drive or pCloud if they expose a compatible DocumentsProvider. **This is manual export/import, not automatic cloud sync.**
- Automatically relink imported playlists and manually retry with **Ricollega**; an app-private SHA-256 cache reuses verified unchanged files across rescans/restarts when MediaStore volume/version/generation metadata is trustworthy. Exact renamed/moved files still match by content.
- Preserve missing items instead of deleting them; never automatically link a different hash.
- Save local working playlists in private app storage with atomic writes; local playback requires no login.
- **Opt-in manual Google Drive playlist sync** is now implemented, subject to OAuth setup and real-device QA; see [Cloud setup](docs/GOOGLE_DRIVE_SYNC.md).

## Current limitations

- No cloud audio streaming, background/automatic synchronization, pCloud adapter or Windows client yet. OAuth + manual Google Drive metadata sync are coded but not yet authenticated on a device.
- Device QA (26 October 2026) confirmed background playback and notification-shade play/pause/previous/next. Lock-screen card visibility and slight perceived notification-control latency remain **unresolved**; this follow-up build adds [targeted QA diagnostics](docs/ANDROID_MEDIA_QA.md) and event-driven UI synchronization, not an unverified platform workaround. Reboot/process-death resumption and Android Auto browsing are not implemented.
- Only audio present in MediaStore can be scanned; arbitrary downloaded document locations may be omitted.
- An initial relink can read the entire local library; verified hashes are reused across scans/restarts where the provider exposes reliable version/generation. Hashing now uses **two bounded readers** instead of holding one global cache lock during file reads, and stops early only when every entry in every playlist is already EXACT. Unresolved/hashless playlists still require the full inventory; no final MISSING is inferred early. The diagnostics distinguish indexing, SHA and matching time. These changes need device timing validation; see [Android library QA](docs/ANDROID_LIBRARY_QA.md). Do not scan remote mounts.
- Real-device QA and provider QA are still required; JVM tests and CI compilation do not prove on-device behavior.
- Import refuses to overwrite a playlist with an existing ID. Merge/conflict resolution is future work.
- Changes to file encoding or tags can change SHA-256 and require user review.

## Technology

- Android: Kotlin, Jetpack Compose, Media3 ExoPlayer; min API 26, target API 35.
- Core: independent Kotlin/JVM module with portable models and conservative matching logic.
- Playlist format: v1 JSON compatible with the Python POC provided on 24 October 2026.
- CI: GitHub Actions, Gradle 8.13, Android Gradle Plugin 8.11.1, JDK 17.

## Open source

Open Music is a **public, open-source project licensed under [MIT](LICENSE)**.
The source is available for use, modification and contributions. QA prereleases
are also public and experimental, not stable or Play Store releases.

## Getting a QA APK

This temporary device-testing stage uses **public experimental debug APKs**.
After each successful feature-branch push, GitHub Actions runs core tests,
builds the Android APK, and publishes it under **Releases** as a prerelease.
Local-player QA needs no private keystore or repository; Google Drive OAuth QA requires the signing-certificate SHA-1 and an Android OAuth client.

The GitHub Actions runner's default debug certificate can differ between
builds. If Android refuses an in-place update, the tester can **uninstall
the old version and install the new APK**, knowingly resetting local app data
(including playlists). Backups are optional for the present disposable QA
installations; use Export JSON v1 if any playlist should be retained.
Persistent signing and dependable in-place upgrades are deferred to a later
release phase. Cloud OAuth authorization requires an Android OAuth client bound
to package `app.openmusic` and the SHA-1 certificate of the exact APK. Runner
debug certificates vary between QA builds; durable cloud QA needs stable
signing configured securely, not a committed key. [Details](docs/GOOGLE_DRIVE_SYNC.md).

The source repository stays public under the MIT license. PR #1 remains
draft: public test APKs are not stable releases or Play Store publication.

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
2. Validate manual Google Drive metadata sync: OAuth consent, incremental revision checks, offline changes, restore on a second Android device and explicit conflict resolution; background sync is later.
3. Verify the new foreground media session service (background/lock-screen/headset); Android Auto library browsing and user-controlled cache are later steps.
4. Add direct pCloud/Google Drive playback and eventually a Windows client.

The existing personal project **our-soundtrack** is intentionally separate and remains untouched.
