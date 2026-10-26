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

**Local reliability gate:** The pure Kotlin revision planner checks the complete
parent graph for missing ancestors, duplicate IDs and cycles (including cycles
outside the apparent remote heads). A saved checkpoint is trusted only when
both its revision ID and recorded playlist digest match remote history.
Regression tests cover two-device forks, explicit merge, corrupted revisions
and inconsistent checkpoints. These unit tests do not prove an Android OAuth
session, remote upload or device-to-device restoration.

## Stable QA signing / OAuth activation (external prerequisites)

**QA signing is prepared in code but not provisioned until the owner adds the
secret.** Production signing is independent and not set up by this workflow.

1. On a trusted Windows computer with Java `keytool`, run
   [`scripts/prepare-qa-signing.ps1`](../scripts/prepare-qa-signing.ps1)
   locally. It creates `~/.open-music/open-music-qa.p12`, refuses to overwrite
   an existing key, puts a JSON secret payload on the clipboard (not on disk),
   and prints the **public** SHA-1 fingerprint. Keep an **encrypted backup**
   of the `.p12` and its password offline; never paste either into a PR,
   issue, log, chat or public file.
2. In [GitHub Actions repository secrets](https://github.com/falker47/open-music/settings/secrets/actions),
   add exactly one **repository** secret called `OPEN_MUSIC_QA_SIGNING`,
   pasting the clipboard payload as its value. This is a JSON object holding
   `keystoreBase64` and `password`; Base64 is encoding, **not encryption**,
   so it belongs only in the GitHub encrypted secret field. The script uses
   the fixed alias `openmusic-qa`. Never use a production keystore here.
3. Subsequent pushes to `feat/**` build the signed `app-qa.apk` and publish
   it with `qa-signing-info.txt`; the latter records the actual certificate
   SHA-1 and commit without disclosing the key. A PR or a push **without**
   the secret exercises the QA Gradle variant with a disposable certificate
   only; **no QA APK is published**. The ordinary debug CI remains green.
4. In [Google Cloud Console](https://console.cloud.google.com/apis/library/drive.googleapis.com)
   enable Google Drive API in a project controlled by the owner. Configure
   [Google Auth Platform](https://console.cloud.google.com/auth/overview)
   (Branding/Audience, test accounts while in Testing) and an **Android OAuth
   client**, package name `app.openmusic`, SHA-1 copied from the
   **published QA release's** `qa-signing-info.txt`. Configure/declare the
   **`https://www.googleapis.com/auth/drive.appdata`** scope. No OAuth
   client secret or `google-services.json` is needed by the Android
   AuthorizationClient used here.
5. Install the exact `app-qa.apk` on an Android device with Google Play
   services. Previous experimental runner-debug APKs can have a different
   signing certificate, so upgrading directly may fail. Export JSON v1
   before a deliberate one-time uninstall if the local playlists matter.
   Grant Drive access only to a chosen test account and validate the actual
   upload/download/conflict/offline gates below.

**Privacy:** the signing payload must remain solely in GitHub encrypted
secrets and the owner's secure backup. The GitHub workflow masks the password
before passing it to Gradle and never publishes the private key. The *public*
SHA-1 alone is safe to place into Google Cloud Console.

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
