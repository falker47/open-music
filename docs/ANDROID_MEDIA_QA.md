# Android media session — QA diagnostics (26 October 2026)

This QA increment deliberately **does not** create a custom lock-screen player or change Android notification permissions. The MediaSessionService already publishes a system media notification; whether it is prominently displayed while locked is controlled by Android version, OEM lock-screen UI and device settings. A visible Quick Settings player does not prove that the lock-screen media card must look identical.

## Minimal device test (same device, same 3+ local tracks)

1. Update the existing app **without clearing its data**. Play a local track; confirm the in-app title, artist and progress match the Android media card.
2. While playing, send the app Home, lock the device and wake the screen **without unlocking**. Note whether a player card is visible on the lock screen. Unlock and verify the card still exists in the notification shade. Check Settings > Notifications > Lock screen (wording varies by OEM), any media-controls/lock-screen switches and, if available, compare another media player. Do not assume the missing card is an Open Music bug.
3. Repeat Play/Pause and Next/Previous from **(a)** inside Open Music, **(b)** the notification shade with Open Music backgrounded, and **(c)** the locked screen if the OEM offers a player. For each, note approximate tap-to-audible-change delay, whether the title switches immediately and whether a spinner/buffering occurs. Compare cold first playback versus a track already playing.
4. While music plays, reopen the activity from Home; verify current title, transport state, position and progress, then skip and seek. Rotate the phone if auto-rotation is enabled; check playback continuity and UI synchronization.
5. If available, try a Bluetooth/headset transport key, incoming competing audio focus (another audio app) and unplugging wired headphones. These are separate unverified cases, not assumed PASS.

## Optional diagnostic log (debug APK only)

When a computer with Android platform-tools is available, capture filtered logcat during **three repeated controls**:

```sh
adb logcat -c
adb logcat -v time -s OpenMusicPlayback:D '*:S'
```

The debug-only tag `OpenMusicPlayback` reports a monotonic `elapsedMs` timestamp with `transport` commands that reached the session, `state=buffering/ready`, `isPlaying` and track transitions. It does not log filenames, track names, file paths or URIs. The `transport` callback fires **after Media3 has dispatched the controller interaction**, not at the actual tap: timestamps can help isolate post-dispatch buffering, but they **cannot alone measure tap-to-session dispatch latency**. A screen recording with visible touches and sound can supplement it.

If audio changes promptly while the system card animates late, consider System UI/OEM presentation latency; if player stays buffering after command dispatch, investigate local decoding and disk reads. Do not attempt speculative performance tuning before gathering evidence.

## Scope

Existing JSON v1, local-only scanning, playlist persistence rules, matching/anti-duplicates and the service-owned ExoPlayer remain unchanged. This QA build improves the Compose progress/title reflection on Media3 events and adds debug logs only; it does **not** claim a fix for OEM lock-screen visibility or measured notification response time. No device QA result is implied by CI success.

## Navigation / expanded player / artwork (next device gate)

- Install **in-place**, only if the installed APK certificate matches the QA certificate. If Android refuses the update, **do not uninstall** until every playlist is exported to portable JSON v1. An uninstall clears private app data; `Playlist · 0` alone does not establish whether this occurred or a separate persistence bug exists. Old GitHub runner debug keys cannot be reconstructed from an APK.
- Test with **three-button navigation**: the mini-player and its seek thumb must sit completely above the system navigation area; drag to 25% and 75% and verify audible position. Repeat with **gesture navigation**. Do not hide system controls.
- Tap the mini-player title: **In riproduzione** opens. Check current title, artist, artwork if embedded locally, seek position/duration, play/pause, previous/next, system Back and **Libreria** return. Test after Home/reopening the Activity while audio keeps playing.
- Play two local tracks with different embedded covers. `OpenMusicPlayback` now traces `track_transition` and `metadata_changed art_data/art_uri` with monotonic timestamps, but neither proves when the OEM System UI draws the media card. If the card lags and metadata was already current, capture a screen recording for device-side diagnosis. No ExoPlayer tuning or artificial lock-screen overlay is included.
- Verify **Rock** and **aq** after closing/reopening without uninstall. Backup each playlist with **Esporta** before any signing migration. A first new stable-key build may require one explicit migration reinstall if the previous debug certificate differs, and this may destroy unexported local data.

## Stable QA signing gate (no key in source)

The push workflow always compiles/tests. It publishes a QA prerelease **only when the repository is private and all four GitHub Actions secrets are available**:
`OPEN_MUSIC_QA_KEYSTORE_B64` (base64 of a user-owned, securely backed-up JKS),
`OPEN_MUSIC_QA_STORE_PASSWORD`, `OPEN_MUSIC_QA_KEY_ALIAS`, and `OPEN_MUSIC_QA_KEY_PASSWORD`.
The keystore is reconstructed into runner temporary storage, not committed or uploaded as a build artifact. Preserve it long term: losing the key requires a new migration. The old GitHub default debug certificate cannot be assumed equal to the new key. CI PASS without a released signed APK is **not** an on-device PASS. GitHub Actions artifacts remain disabled due to quota.
