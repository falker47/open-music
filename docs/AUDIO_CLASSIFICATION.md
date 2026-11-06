# Open Music audio classification — evidence-based heuristic (Android QA)

**Status:** experimental rule set, not an audio-content classifier. The 26 October 2026 device screenshots show 779/795 in Musica, 16/795 in Altri audio, with legitimate songs (including artwork and named artists) under Altri audio and uncertain filenames under Musica. This is an observed classification defect, not evidence of the raw MediaStore flag values or override history on that phone.

## Inputs and decision

Android MediaStore supplies `IS_MUSIC`, `IS_PODCAST`, `IS_AUDIOBOOK`, `IS_RECORDING`, `IS_RINGTONE`, `IS_NOTIFICATION`, `IS_ALARM`, artist, album, track number, genre (API 30+), title and relative path (API 29+). These are **signals**, not ground truth. If vendor columns are unavailable, the query falls back without dropping any audio.

`AudioKindPolicy.assess` assigns an auditable score: credible artist +3, credible album +2, music genre +1, album track number +1, Android music flag +1, known music folder +1, embedded artwork +3; messaging paths -1 or -2, known voice-style filenames -3, anonymous numeric names -2, spoken-word genre -3. The default music threshold is 4, requiring multiple independent hints. Explicit Android non-music flags and voice-only folders take precedence. Unknown, placeholder and empty tags are not meaningful evidence.

A second, bounded **in-process cached** `MediaMetadataRetriever` inspection reads embedded artist/album/genre and actual artwork presence for *borderline* candidates only. Artwork alone does not classify anything as music, but artist plus artwork can recover legitimate songs shared through WhatsApp. The in-memory evidence cache avoids repeated retriever calls for unchanged URI/size/modified metadata within the same app process. **Duration is displayed, never a mandatory inclusion/exclusion cutoff.**

## Safety and UX contracts

- `Musica` and `Altri audio` remain exact complementary views of the same complete MediaStore inventory.
- Manual choices override the heuristic, are saved atomically and are reversible; a successful update may show an Undo Snackbar. If a user manually set a category, an automatic policy revision must not silently undo it.
- Every audio file (including Altri audio) remains available for SHA-256 playlist matching. No audio is deleted or moved, and the portable JSON v1 is unchanged.
- Classification may still make errors; e.g. podcasts with music metadata or untagged genuine music. Android's album image is merely associated artwork, not proof of track identity. Users can correct mistakes through the existing reversible controls.

## References

- Android `MediaStore.Audio.AudioColumns`: https://developer.android.com/reference/android/provider/MediaStore.Audio.AudioColumns
- Android `MediaStore.MediaColumns.RELATIVE_PATH`: https://developer.android.com/reference/android/provider/MediaStore.MediaColumns
- Android `MediaMetadataRetriever`: https://developer.android.com/reference/android/media/MediaMetadataRetriever

## Required phone QA after CI

Compare screenshot examples (7 Years / Lukas Graham, Cheap Thrills / Sia, Come / Jain, 139224, 127km (2)-ai, WhatsApp voice message) before and after this build. Verify false positives and false negatives, override persistence/undo and navigation. Measure overall scan latency as well as SHA progress on cold and warm runs; probing embedded metadata is additional work and has **not** been benchmarked on the actual device. Check 0/1/2+ playlists, label/icon direction for Importa (down) and Esporta (up), and per-track duration where available. Do not infer global QA PASS from successful Gradle tasks.
