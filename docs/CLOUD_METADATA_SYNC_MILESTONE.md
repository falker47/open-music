# Cloud metadata sync — technical milestone proposal

**Status (26 October 2026): proposal, not running code.** Provider is not selected. There is no login/OAuth, automatic synchronization, uploaded audio, or cloud playback in this Android QA increment. Import/export through SAF remains distinct from synchronization.

## Portable contract and offline authority

- Keep portable playlist **JSON v1** unchanged: preserve playlist ID, entry IDs, order, SHA-256, metadata, missing entries and legacy duplicates. Never upload local content URIs, absolute paths, device-specific media IDs, SHA cache, manual Music/Other categories, audio bytes or credentials.
- Add an *independent* private sync journal per device: installation ID, provider/account binding, playlist revision heads, remote object IDs and change cursors, pending changes and last acknowledged revisions. Persist with atomic writes; use OS-backed credential storage for OAuth, separately from portable JSON.
- Each modification creates a unique **immutable metadata snapshot**, with schema version, playlistId, revisionId, parentRevisionIds, deviceId, payloadSha256, portableV1 and optional deletion tombstone. Validate hash and schema on fetch. Identity is by UUID, never by file name or timestamp. Do not overwrite remote snapshots.
- Implement an incremental pull of provider object metadata followed by selective download of unseen revisions; push queued immutable snapshots idempotently. Commit remote acknowledgment to the journal only after verifying the uploaded revision. Offline edits operate on the existing local storage, and interrupted sync resumes without data loss.

## Version/concurrency behavior

- Different playlists may sync independently. Concurrent edits to the **same** playlist form separate revision heads, and both must survive until the user explicitly resolves the conflict.
- Show a conflict UI to inspect local vs remote and select: keep local, keep remote, or duplicate as a separate playlist. The resolution becomes a new revision pointing to both parents. Tombstone vs edit is likewise a conflict; no silent last-writer-wins.
- No automatic SHA-256 link across devices without a fresh local inventory match. Playlist metadata can be present while audio is missing locally. For a corrupted/incompatible remote payload, keep the last good local revision untouched.

## Provider options

| Provider | Authorization and remote storage | Design consequence |
| --- | --- | --- |
| Google Drive | **drive.appdata** scope, hidden **appDataFolder**, no broad Drive access | Least privilege and isolated metadata, but snapshots cannot be browsed in the normal Drive UI or shared. Good initial implementation candidate if hidden app data is acceptable. |
| pCloud | OAuth 2.0, dedicated application folder and file IDs, correct US/EU API host | A user-visible dedicated folder is possible; same-name uploads may overwrite and create historical revisions, so use immutable unique snapshot names. Verify authorization and API quota/conditional operations before implementation. |

Official references:
- https://developers.google.com/workspace/drive/api/guides/appdata
- https://developers.google.com/workspace/drive/api/guides/api-specific-auth
- https://docs.pcloud.com/
- https://docs.pcloud.com/methods/file/uploadfile.html
- https://docs.pcloud.com/methods/revisions/listrevisions.html

## Next gated engineering increment, after provider selection and Android UX QA

1. Pure-core versioned snapshot/revision graph + journal format with two-device offline/concurrent-edit simulation and checksum/tombstone/conflict tests.
2. A single provider adapter (Google Drive **or** pCloud) with explicit login/consent, list changes, fetch, immutable upload and disconnect; no automatic background network before opt-in.
3. Manual Sync action with offline/pending/errors and conflict resolver, then measured incremental correctness. Provider-specific state lives outside the portable v1 model.

**Acceptance:** two devices independently edit/order playlists and converge without lost updates; replay is idempotent; edit-vs-delete is reported; temporary offline and revoked credentials do not corrupt local data; missing audio still leaves portable metadata intact; no local paths, URIs, tokens or audio bytes are sent remotely.

**Separate future milestone:** audio remote indexing, authenticated streaming/range requests, expiring URLs and cache policies. None are enabled here.
