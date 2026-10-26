# Android media session — QA diagnostics (26 October 2026)

This QA increment deliberately **does not** create a custom lock-screen player or change Android notification permissions. The MediaSessionService already publishes a system media notification; whether it is prominently displayed while locked is controlled by Android version, OEM lock-screen UI and device settings. A visible Quick Settings player does not prove that the lock-screen media card must look identical.

## Minimal device test (same device, same 3+ local tracks)

1. Install the current GitHub QA APK. If Android refuses to upgrade over the previous debug build, uninstall the previous app first and then reinstall; this intentionally clears app-private playlists during disposable QA. Play a local track; confirm the in-app title, artist and progress match the Android media card.
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

- For this disposable QA phase, **uninstall/reinstall is permitted** when a different debug signing certificate prevents an in-place update. Doing so clears saved playlists. Export any playlist to portable JSON v1 only if you want to keep it. `Playlist · 0` after reinstall is expected, but the earlier screenshot alone does not prove whether the app was reinstalled.
- Test with **three-button navigation**: the mini-player and its seek thumb must sit completely above the system navigation area; drag to 25% and 75% and verify audible position. Repeat with **gesture navigation**. Do not hide system controls.
- Tap the mini-player title: **In riproduzione** opens. Check current title, artist, artwork if embedded locally, seek position/duration, play/pause, previous/next, system Back and **Libreria** return. Test after Home/reopening the Activity while audio keeps playing.
- Play two local tracks with different embedded covers. `OpenMusicPlayback` now traces `track_transition` and `metadata_changed art_data/art_uri` with monotonic timestamps, but neither proves when the OEM System UI draws the media card. If the card lags and metadata was already current, capture a screen recording for device-side diagnosis. No ExoPlayer tuning or artificial lock-screen overlay is included.
- Test playlist persistence **within a single installation**: create or import a small playlist, fully close and reopen the app and confirm it is still there. Do not interpret missing playlists after an uninstall as a persistence defect. Former lists `Rock` and `aq` can only survive if they were exported and imported again.

## Temporary debug QA distribution

During this device-testing phase, GitHub Actions publishes a **public experimental
prerelease** after a successful feature-branch build, without requiring a
persistent signing secret. It uses the runner's standard Android debug key,
which may differ between CI runs. The tester has explicitly opted to
uninstall/reinstall when needed, accepting that app-private playlists are erased.
Permanent signing, seamless upgrades and data migration remain deferred.
No keystore, credentials, personal audio files or private device data are
committed or uploaded. CI and a downloadable APK alone do not constitute
successful device QA. Actions artifacts remain disabled due to quota.
