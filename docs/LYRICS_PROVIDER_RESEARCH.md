# Open Music — lyrics source decision (5 November 2026)

## Decision

**Status: third-party lyrics retrieval is legally gated; local embedded-tag reading is enabled.**
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
| [lrc.red](https://lrc.red/api) | Free public API; rate-limited, contact offered for higher limits; User-Agent/contact URL/email required, attribution/link where data shown | Search + ISRC track lookups, LRC and TTML (including word timing). Site documents recent overload/uptime instability | Terms explicitly permit API use with conditions, but no explicit grant for perpetual offline copies or proof of sublicensable underlying rights located |
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
