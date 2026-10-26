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
