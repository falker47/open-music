# Open Music — lyrics source decision (updated 6 November 2026)

## Decision

**Status: provider adapter and rights-gated offline cache exist, but no automatic Internet lyrics are enabled in the public player. Local embedded-tag reading remains enabled.**
The Android app is publicly distributed as experimental QA software. A free HTTP
endpoint is not a sublicense to reproduce copyrighted song lyrics in that app,
and it does not automatically grant offline persistence. No remote lyrics
provider is enabled, no third-party lyrics are bundled, and no credentials
from other apps or scraping clients are used.

The three source rights are modelled separately in core:
1. QUERY: permission to call a source API.
2. DISPLAY: permission to show the returned third-party text in Open Music.
3. STORE_OFFLINE: permission to retain the text on the device (requires DISPLAY too).

Unspecified permission is **not** interpreted as granted.

## Providers assessed

| Provider | Public API and cost | Operational characteristics | App display and offline retention |
| --- | --- | --- | --- |
| [LRCLIB](https://lrclib.net/docs) | Public, no key required; optional app identification document, no charge advertised | /api/get accepts title, artist, recommended album/duration; ±2 s tolerance. /api/search returns at most 20, no pagination; honor 429 Retry-After, identify User-Agent, sequential requests plus 200–500 ms spacing | Source-code MIT license does **not** establish copyrights over contributed lyrics. No express blanket license for public display or permanent offline copies found |
| [LRCGET](https://github.com/tranxuanthang/lrcget) | Client, not a separate API | Official LRCLIB desktop client; supports library-wide downloads and optional export to .lrc/.txt or embedding; version 2.2.1 was listed on 5 Nov 2026 | Demonstrates technical feasibility, not a license to reuse all lyric content in a separate public app |
| [lrc.red](https://lrc.red/api) | Free public API; rate-limited, contact offered for higher limits; User-Agent/contact URL/email required, attribution/link where data shown | Search + ISRC track lookups, LRC and TTML (including word timing). Site documents recent overload/uptime instability | On 6 Nov 2026 the maintainer confirmed noncommercial API use and indefinite app-private caching with credit. He explicitly does NOT license the underlying copyrighted words. Public-app lyric content display/retention remains legally gated. |
| [Better Lyrics](https://docs.betterlyrics.org/) | Free cached hits. TTML fresh misses may return 401; API keys currently not being issued. Kugou/QQ endpoints do not require keys | /getLyrics TTML syllable; /kugou/getLyrics line-synced; /qq/getLyrics word-level; upstream and cache-dependent coverage, rate tiers | GPL license for the service code is **not** a sublicense of the retrieved words. Separate content and retention authorization absent |
| [Musixmatch](https://github.com/musixmatch/musixmatch-sdk) | Official commercial/licensed developer API requiring its own API key; available plans and permissions depend on agreement | Search/track/lyrics APIs; broad professional catalog, licensed delivery | Only written agreement establishing use, attributions and caching conditions can authorize desired behavior. No verified free unlimited public-app+offline plan |
| LyricFind / Genius | Commercial lyrics agreements / Genius API does not furnish full reusable lyrics | Search metadata possible; protected complete lyrics require licensing; scraping excluded | Not suitable as a free authorized remote source |

Primary source detail:
- LRCLIB [official API specification](https://lrclib.net/docs) and [source server](https://github.com/tranxuanthang/lrclib); a [commercial-use/cache rights question](https://github.com/tranxuanthang/lrclib/issues/111) was still open when checked.
- LRCGET [release history](https://github.com/tranxuanthang/lrcget/releases).
- [lrc.red API conditions](https://lrc.red/api).
- Better Lyrics [API intro](https://docs.betterlyrics.org/), [authentication](https://docs.betterlyrics.org/docs/authentication), [providers](https://docs.betterlyrics.org/docs/providers).
- Musixmatch [published SDK](https://github.com/musixmatch/musixmatch-sdk) and [API key documentation](https://www.postman.com/musixmatch-dev/musixmatch-apis/collection/pqm8o6w/lyrics-api).

This is a conservative product release gate rather than a legal conclusion about
private individuals saving music lyrics for personal use. Copyright exceptions
and personal use do not provide a general app-distribution license.

## Implemented independent functionality

- Read **existing** lyrics from the user's local audio file while it plays,
  using Android's existing audio permission and a ContentResolver stream.
- MP3: ID3v2.3/v2.4 USLT, lyric TXXX, and millisecond SYLT; FLAC:
  Vorbis LYRICS, UNSYNCEDLYRICS, SYNCEDLYRICS.
- Local LRC-style timestamps embedded inside those tags are parsed and
  highlighted in time with player position; untimed tags remain readable.
- UI toggle appears in Now Playing only for a track with valid embedded text;
  otherwise the previously approved Now Playing artwork and controls remain.
- Metadata reads are bounded, skip large non-lyric frames and abort on
  unsupported/unsafe encodings. Never overwrite the audio.
- A track change cancels the old read. No unlicensed remote fetch, API key,
  library-wide HTTP scan, audio modification or Google Drive operation.
- There is no download needed to keep embedded lyrics offline: they are already
  in the user's original local file. Open Music does not duplicate them in
  its app-private storage.

Known current limits: no automatic web lyrics when tags are absent; no download
massivo; ID3v2.2 and ID3 compressed/encrypted/unsynchronised frames and
non-FLAC Vorbis/Opus/Ogg/MP4 lyric tags are not covered; a source that includes
a mistimed LRC may highlight incorrectly. No inferred lyrics are fabricated.
Device QA remains required after CI.

## Implementation gate for authorized Internet lyrics

Once *specific written permissions* cover both display in this public Android
app and optional offline persistence (including attribution, TTL and revocation):
1. Attach one HTTPS provider adapter under LyricsProvider, return full metadata,
   rights and provenance; explicitly report unavailable/ambiguous/instrumental.
2. Keep strict title/artist matching; check album when known and duration ±2 s
   when known; reject conflicting live/remix/cover/edition identities and ties.
3. Cache to app-private storage only if provider allows offline retention; apply
   contractual expiry, erase/revocation policy, no audio-file writes.
4. Support on-play fetch and an **opt-in** library batch job with bounded
   sequential requests, Retry-After, cancel/resume, progress and missed-song
   summary. Never treat 404/429/offline as a permanent absence.
5. Add provider contract/JSON tests, bad-response fuzz checks, storage tests,
   Android QA and licensed source attribution. Do not enable these features
   based only on public accessibility or on LRCGET's behavior.

Scope lock: do not touch Google Drive UI, playlist/music synchronization,
portable playlist JSON v1, OAuth or backup workflows.


## Maintainer reply — 6 November 2026

Bini (lrc.red) confirmed in a direct email to the developer:
- Open Music's free non-commercial API use is allowed; contact him again before any commercial use.
- App-private caching may be indefinite and is encouraged. Clearly credit and link to lrc.red.
- No bulk endpoint or infrastructure allowance for library-wide download: request one track only as needed and avoid unnecessary calls.
- lrc.red does **not** own or grant copyright permissions for the lyric words; those must be obtained independently.
- The developer's original email is retained in the owner's mailbox, not published in this repository.

Public terms: https://lrc.red/api . They also require an identifying User-Agent with an app URL and a reachable contact email; observe rate limits. The published address api@lrc.red previously bounced; use a verified reachable address in an eventual enabled client, not a secret or guessed address.

## Code checkpoint: independent preparation, NOT a feature launch

- `LyricsRemoteCoordinator`: rights-gated, per-track on-demand lookup, cache-first results, explicit no-result / ambiguity / provider error / offline / instrumental states, manual refresh and deletion. It will **not** request or display provider text unless both QUERY and DISPLAY rights are explicitly established, and will **not** store it unless STORE_OFFLINE is established as well.
- `LyricsPrivateCache`: hashed metadata plus audio-identity key, bounded atomic binary persistence and deletion. The caller must use Android `Context.noBackupFilesDir` to ensure private device-only storage. No original MP3/FLAC is modified.
- `LrcRedLyricsProvider` and `HttpsLrcRedTransport`: strict metadata selection before fetching a single ISRC, bounded HTTPS/timeouts, 400-ms sequential pacing and 429 cooldown; LRC and TTML parsing. No full-catalog scan, no server, no API key. A contact address is required at construction.
- JVM contract tests cover caching/identity isolation/offline/restart, uncertain matches, errors/cancellation, LRC/TTML fallback and API path validation. They do **not** prove real-device operation, external API uptime, global lyrics coverage or valid copyright rights.
- **Not enabled:** Internet fetching in MainActivity, public Lyrics UI, on-device QA of online retrieval, bulk library download. The existing embedded-tag UI remains the only user-visible lyrics functionality.
- The third-party service attribution must be displayed beside the actual lyrics once an authorized feature is enabled.
- The audio-metadata-only adapter cannot itself establish the legal status of a song's text. No default permission or heuristic automatically sets `canDisplayInApp` or `canStoreOffline` to true. Do not treat the adapter or cache tests as proof that publicly displaying arbitrary songs is licensed.

## No-cost options and remaining rights gate

A fully open/copyright-cleared catalogue may allow a *limited* legal solution when each lyric's rights and territorial scope are verified. For example, PubDomainOnly publishes a declared CC0 set and a US-only public-domain collection (https://github.com/kennethyork/PubDomainOnly); those declarations do **not** establish that any ordinary commercial MP3's lyric is covered, that foreign rights have expired in Italy, or that lrc.red returned identical text. A rights-verified per-song catalogue or express permission from authors/publishers would be necessary.

Neither lrc.red, LRCLIB nor LRCGET currently establishes a verified blanket sublicence to display/cache arbitrary commercial lyrics in an independently distributed public Android app. Free API access does not equal a lyrics copyright licence. An individual's private, lawful access is a distinct legal question and not a general Play Store distribution permission.

**Massive download:** BLOCKED, not partial completion. Do not transform the on-play cache into an 800-track background downloader or evade lrc.red's documented capacity restriction. Only revisit when a separate zero-cost provider explicitly authorizes both this access pattern and underlying lyric content rights.

**Release gate:** Leave PR #1 Draft and public automatic retrieval disabled until permissions and platform compliance are independently resolved. No merge to main or Play release based on this groundwork.
