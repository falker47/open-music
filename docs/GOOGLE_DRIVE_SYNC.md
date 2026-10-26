# Open Music — Google Drive playlist metadata synchronization (QA milestone)

**Status 26 October 2026:** opt-in **manual** synchronization implemented in the Android client; compiling unit tests and assembling an APK does **not** validate an OAuth grant or a real Google Drive transfer. QA requires a Google Cloud OAuth client for the exact Android signing certificate. Streaming music from cloud and full background sync remain outside this milestone.

## Provider and permissions

The first adapter uses **Google Drive**, deliberately choosing the narrow non-sensitive `https://www.googleapis.com/auth/drive.appdata` scope rather than broad Drive access. Only the hidden `appDataFolder` is accessed; playlist files are not shown in the ordinary Drive UI. The app asks for authorization on explicit user tap of *Collega Google Drive / Sincronizza Drive*. It does not store access/refresh tokens. A connected ChatGPT Google Drive account does **not** authorize this Android app.

- No audio bytes, user file paths, MediaStore URIs, artwork images, local hash caches or manual Music/Other classifications are sent.
- Portable playlist JSON v1 lives unchanged inside each cloud-only revision envelope. Entry IDs, duplicate entries, order, exact SHA-256 and missing files are preserved.
- Local playback, editing, JSON import/export and playlist storage work offline with no Google consent.
- Disconnect revokes OAuth access (Google may revoke all the app's granted scopes), erases the local cloud journal and leaves *both* local playlists and cloud snapshots intact.

## First manual sync

1. Read local playlists and private checkpoint journal; list **metadata only** from the Drive appData folder (pagination).
2. Verify a remote account-binding UUID stored in the appData folder, to avoid writing a previously synced playlist into a different Google account accidentally.
3. Build an append-only per-playlist revision graph with a canonical SHA-256 digest of all portable fields. Compare local state with the last acknowledged revision and remote heads. Unchanged playlists produce no upload; new local playlists create an immutable snapshot; remote-only edits download and validate before replacing the local copy.
4. On divergent edits or parallel heads, stop before any playlist writes and ask the user to choose: **keep local**, **use selected cloud revision**, or **keep both by duplicating the local playlist**. Choices are revalidated against the remote head IDs on retry; no silent timestamp-based last-writer-wins. Existing cloud revisions are never overwritten.
5. Persist playlist changes atomically before acknowledging downloaded revisions; after a successful append-only upload, save the new checkpoint atomically. An interrupted upload can be recovered on the next manual sync by matching its digest instead of clobbering the previous head.

**Limitations:** the first adapter lists remote revision metadata on each manual sync and downloads only the required snapshots; a Drive Changes incremental cursor, always-on background scheduling, tombstone deletion UI, pCloud adapter and cloud audio playback are future work. A multi-device conflict remains unresolved until an explicit choice; missing/corrupt remote history blocks writes. No cloud data is accessed in the development environment.

## Required setup to test Google authorization

1. In [Google Cloud Console](https://console.cloud.google.com/apis/library/drive.googleapis.com), enable **Google Drive API** in a Cloud project under the publisher's control.
2. Configure Google Auth Platform Branding/Audience (external test users if in testing) and create an **Android OAuth client** using the **package name `app.openmusic`** and the **SHA-1 fingerprint of the exact test APK's signing certificate**. The SHA-1 can be read with `keytool -printcert -jarfile app-debug.apk` or Android `apksigner verify --print-certs` on the built APK.
3. The current GitHub Actions QA builds are signed by a *new runner debug key* and that SHA-1 may change between runs. A **stable QA keystore stored securely as a GitHub Actions secret** is needed for reusable authorization across future QA releases; do not commit a signing key or client secret to the public repository. If Google Cloud is not configured, the app should keep local functionality and show an authorization error. The SHA-1 / OAuth project cannot be inferred from a successful build.
4. Install the APK on an Android device with Google Play services and test *Collega Google Drive*. Consent must grant **drive.appdata only**; then *Sincronizza Drive* can upload/download metadata. Use a test account and no irreplaceable playlist data until conflict/restore QA passes.

Official references:
- https://developer.android.com/identity/authorization
- https://developers.google.com/android/guides/client-auth
- https://developers.google.com/workspace/drive/api/guides/appdata
- https://developers.google.com/workspace/drive/api/guides/manage-uploads

## Device QA gates — remain open until explicit feedback

- First installation: local playlist -> explicit sync -> re-run without changes -> no extra revisions.
- Second device/test installation: consent to **the same Drive account/app OAuth client**, restore portable JSON IDs/order/duplicates, perform local SHA matching without uploading audio. Imported tracks can be `MISSING` locally without losing metadata.
- Device A edits offline and uploads; device B fetches; simultaneous offline edits must show explicit conflict choices; verify chosen resolution and local duplicate option.
- Incorrect Google account, denied permission, network outage, stale token, corrupt snapshot, interrupted upload, removed file, long/duplicate playlists and permanent local offline playback.
- Confirm local reclassification and SHA cache aren't uploaded. Do not treat Android compile as successful authorization, remote transfer or second-device recovery.

**Not done:** automatic background sync, pCloud adapter, streaming or cloud audio upload; no main merge/Play Store release.
