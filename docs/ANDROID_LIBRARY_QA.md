# Open Music Android library QA

Use this checklist with the current public GitHub QA APK. CI checks JVM tests and
the Android build; actual Android UI behavior and I/O timings need device testing.

## Music / Other audio
- Check the two complementary categories and their counts: the totals should
  equal all local MediaStore audio.
- In Other audio, use the category icon to move a recording to Music. Return
  to Music and use its category icon to move it back. Repeat after restart and
  rescan; verify no physical audio file changes.
- Check artist/title search, the shorter text field and accessible touch targets.
- With one playlist, check that there is one detail view. With two, switch using
  the compact chooser. Test import, export, Ricollega and track actions.

## Hash timing measurements
The playlist view displays the most recent operation's duration in
milliseconds, how many SHA digests were recomputed and how many came from the
persistent private cache.

1. Cold run: with the same installed APK, import the first portable playlist
   and record the displayed duration and recomputed file count.
2. Warm run: import a second portable playlist with a different playlist ID
   and unchanged local audio. Record duration and recomputed count.
3. Selectively changed run: alter one device audio file, rescan and record
   the same metrics. Repeat after restarting the app if possible.
4. During analysis confirm progressive SHA results; cancel and check that
   no unverified item becomes conclusively Missing. Retry to completion.
5. Test renamed/moved exact copies and different bytes with matching tags;
   changed SHA values must never be linked automatically.

On Android 10 or below and for MediaStore providers with uncertain volume,
version or generation information, safe rehashing is expected rather than
an unsafe cache hit. Unreadable audio requires a retry before a final
missing determination.

Report these measurements and test results; unreported scenarios remain open.
Existing background playback QA cases remain separate.

## Next device QA — compact playlists and audio classification (26 October 2026)

1. With 0, 1 and 2+ playlists, check the single compact action row (Nuova/Importa), chips only with 2+, one playlist summary with accessible Ricollega/Esporta icons and tracks immediately below. Check mini-player, three-button/gesture navigation, long names, landscape/small screens and larger font scaling.
2. Promote an item from Altri audio to Musica and demote one in the opposite direction. Confirm title-bearing Snackbar only after successful save; test Annulla, full restart and rescan. Files must never move or be deleted.
3. Compare automatic classification of actual voice notes, recordings, podcasts, ringtones, notifications, music in Music folders without artist tags, tagged downloads, messenger-shared songs and a song whose title contains "Voice". Report false positives/negatives without sending private recordings.
4. Import a portable JSON v1 playlist that references content shown in Altri audio; all local audio must still participate in SHA matching. Keep prior QA open: cold/warm cache, selective invalidation, renamed/moved audio, EXACT/MISSING/AMBIGUOUS, persistence and media controls. CI does not imply real-device PASS.

## Follow-up QA — observed 26 October 2026 16:22–16:24, build qa-27b214524726

Real-device screenshots: after first start the app showed empty playlists; the Rock playlist later contained 4/4 available tracks with a reported scan of 23,876 ms, 795 freshly computed SHA-256 and 0 cached for 795 audio. The library showed 779/795 Musica and 16/795 Altri audio; 7 Years, Cheap Thrills and Come with artist/artwork appeared among Altri audio while anonymous audio names remained in Musica. These are **observations**, not verified causes or proof of all matching states.

Next iteration check: Importa must show a down arrow, Esporta an up arrow **and visible label**; playlist actions remain usable with long names and large text. Verify the displayed duration for library and playlist audio when known, without using duration as a mandatory music classifier threshold.

Compare the actual tracks above under the new scored MediaStore + embedded-metadata policy. Report any remaining incorrect categories, presence of media artwork, and whether each file had been manually overridden; avoid sharing private audio. Verify manual corrections persist after restart and rescans, never modify files, and never remove candidates from SHA-256 matching. Track cold/warm overall scanning latency (metadata enrichment may add overhead), permission cases and all earlier unverified QA gates. Cloud remains a technical **proposal only**, with no provider connection in this build.

## Performance QA — bounded parallel hashing and exact-only early completion (26 October 2026)

- Compare the same 795-file test library before/after on the **same installation**, recording cold and warm runs separately. Reinstalling a runner-signed debug APK wipes app-private cache and is always a cold start. Do not compare a fresh installation with a previously warmed one.
- Run (a) first library scan with Rock 4/4 and one other imported playlist; (b) immediate rescan; (c) import another playlist whose tracks are already known; (d) a playlist with one unknown SHA/MISSING; (e) a hashless legacy entry; (f) one edited song with the same MediaStore URI; (g) rename/move and provider version reset where reproducible.
- Confirm that the new two-line diagnostic separates **Libreria** indexing time, **SHA** time and **matching** time, alongside the count *verified/total*; do not confuse the count with the total inventory when early completion succeeds. Record counts and times, not private filenames or audio bytes.
- For (a)/(c), early completion is allowed only when all playlist entries are verified EXACT, retaining order/duplicates. For (d)/(e), all local audio must still be SHA checked before any final MISSING or metadata result. Manual add after early completion must insert a verified local copy and show it as available; it must not spuriously disappear.
- Repeat cancellation mid-analysis, corrupted/unchanged SHA cache, unavailable audio, full restart, Mini-player/background audio, classification and cloud manual sync smoke. Successful CI does not establish device speed or OAuth correctness.
