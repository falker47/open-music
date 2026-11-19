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


## Italian/EU rights analysis — 6 November 2026 (public-source review)

**Decision (release gate): no universal free permission for display and offline storage of ordinary copyrighted commercial lyrics has been independently verified.** Keep remote retrieval unavailable in the public player. The existing lrc.red integration remains inactive; the QA APK is not proof of the feature. No wholesale download, scraping, backend or redistribution. This section is product-risk research, not a definitive legal opinion.

### Different acts, different legal questions

| Operation | Practical assessment |
| --- | --- |
| Distribute Open Music without lyrics bundled | Not itself a distribution of lyrics; ordinary app/player code is distinct from content licensing. |
| A user device requests one lyric from lrc.red | API access is permitted non-commercially by the provider, on demand. Provider permission is **not** a copyright license for third-party song text. |
| Privately show a fetched song lyric on the device | Not necessarily a public communication or independently unlawful just because it is on screen. However, the text remains protected, and the legality of obtaining, reproducing and displaying it depends on source authorization, the specific facts and any applicable exceptions. No blanket permission established for the app's intended general-purpose behavior. |
| Keep an offline private copy | A persistent reproduction (not merely transient technical caching). Source-specific lyrics permission or a demonstrably applicable statutory exception is needed. The provider allows caching as an API matter but cannot sublicense the words. |
| Redistribute lyrics, centralize them, bundle them, share/export them | Distinct copying/distribution risk; **not supported** or authorized in this project. |
| Synchronize scrolling text with playback | Timing UX alone does not create a new license. For karaoke, video, adaptation or synchronization rights beyond timed display, assess separately; do not presume that display permission covers all variants. |

Primary legal sources:
- [SIAE GOAL online music conditions, **Vers. 1_2026**, arts. 7.4(b), 7.5](https://d2aod8qfhzlk6j.cloudfront.net/SITOIS/SIAE_CGL_Musica_Utilizzazioni_Online_GOAL_a917ec7db5.pdf): graphic reproduction, on-screen display, downloading, printing of lyrics and karaoke text scrolling are expressly **excluded** from this particular license because SIAE does not administer those rights under it; the licensee must seek the relevant rightsholder's prior authorization. The document also says mere technical transport, caching or providing technical means does not *by itself* make a Digital Service Provider (art. 1(g)); this classification rule is **not** an affirmative copyright permission.
- [Italy, Law 633/1941, consolidated](https://www.normattiva.it/eli/id/1941/07/16/041U0633/CONSOLIDATED/20260612), including reproduction rights and [art. 71-sexies](https://def.finanze.it/DocTribFrontend/getAttoNormativoDetail.do?ACTION=getArticolo&codiceOrdinamento=0000000000000719999900006000000000000000000000000000000000000000000000000000000000000000000000000000000000&id=%7B012E634A-9F52-4452-B7F7-980D83EDE0C6%7D), which specifically concerns private copying of **phonograms/videograms** and does not provide a general blanket authorization to download separate literary lyrics from a web API.
- [Directive 2001/29/EC, Article 5(1)](https://eur-lex.europa.eu/legal-content/IT/TXT/?uri=CELEX%3A32001L0029): temporary, incidental, integral technical reproductions only where enabling network intermediary transmission or a **lawful use**; does not generally encompass indefinitely retained offline lyric copies. Article 5(2)(b) permits Member State personal-copying exceptions subject to conditions, rather than a general cross-EU authorization for a distributed lyrics app.
- [Google Play Intellectual Property policy](https://support.google.com/googleplay/android-developer/answer/9888072?hl=en): copyright infringement and encouragement of infringement are restricted, and developers may be asked to demonstrate rights. A text-free APK is not automatically noncompliant; the proposed automatically enabled third-party feature needs a separately defensible basis.

The **device-only** design removes developer hosting and public redistribution but cannot independently prove the lawful origin, display and retention of every fetched lyric; nor does the fact that users supply their own audio file automatically authorize lyric reproduction from a separate web service. This is an unresolved legal clearance gate, **not** a finding that every individual act of private viewing must be infringing.

### Free-source and repertoire limits (6 November 2026)

- **lrc.red:** maintainer expressly permits free noncommercial API access, per-song requests and indefinite app-private cache with credit; **does not grant content copyrights**. Do not prefetch an entire library. Actual fraction of a real user's commercial repertoire with legally sublicensable texts: **not verified**.
- **LRCLIB/LRCGET:** open public lyrics endpoint and open-source client; open-source code license is not an explicit blanket permission for copyright-protected contributed words. No verified rights bundle for third-party public Android display plus offline retention. LRCGET's bulk functionality must not be treated as authorization for Open Music to perform batch downloads.
- **Better Lyrics and other aggregators:** technical access, cached responses or timings are not sufficient proof of underlying rights, territorial coverage and perpetual retention terms.
- **Musixmatch / LyricFind:** licensed-catalog businesses; no publicly established **zero-cost, no-server, open Android client** permission for full commercial lyrics **and offline cache**. Written free-use and rights coverage terms are being explored. **UMPG** controls only its actual authorized shares/work, not all music worldwide.
- **Explicitly open/per-work repertoire:** e.g. [PubDomainOnly](https://github.com/kennethyork/PubDomainOnly) describes a 4,987-item corpus (3,653 declared CC0 originals and 1,334 US-focused public-domain examples). Individual lyric copyright status, creator rights, Italian territory, correspondence to commercial recordings and timing are **not independently verified**. This is not a substitute for the user's normal commercial catalog. **No responsible percentage coverage** of a user library can be estimated from this count, and no arbitrary commercial track is thereby authorized.

### Minimum actionable clearance for possible future integration

For each allowed source/territory/work or bounded catalog, obtain **written provenance** that covers: (1) provider HTTP QUERY rights; (2) full-text in-app DISPLAY of third-party words, including the specific requested rendering; (3) optional app-private STORE_OFFLINE rights with duration/deletion/revocation; (4) attribution, API limits and downstream rights for coauthors/shares. Ambiguous or absent proof => **deny**. User-triggered per-playing-track fetch, strict artist/title/duration identity matching, local no-backup cache, opt-in offline storage and a visible lrc.red attribution only after cleared. No unqualified unrestricted fetch flag, no default grant, no library-wide prefetch.

### Rights outreach status (non-binding; no commercial commitments)

On 6 November 2026, exploratory inquiries were sent to official contacts at **LyricFind**, **Universal Music Publishing Group** and **Musixmatch**, requesting an expressly free noncommercial Android permission for both lyrics display and offline caching, with territory/coverage/key restrictions. No rights grant or substantive response was received at this checkpoint. A published SIAE online licensing mailbox rejected delivery with **SMTP 550 5.1.10 RecipientNotFound**; SIAE's public help form could not be submitted due to reCAPTCHA; a separate public SIAE departmental contact was asked to route the query to the proper lyrics-rights office. All original correspondence and delivery diagnostics remain in the developer's private email, not in this repository. Do not describe an outgoing inquiry as a license or successful SIAE submission.

**Status:** Rights clearance pending. Internet Lyrics UI **blocked**, bulk retrieval **blocked**, PR **Draft**, no merge/Play publication, no new live-device QA claim. A documented rights grant or a separately well-supported lawful exception must precede activation and device-level tests.

## Additional provider and rights-owner check — 6 November 2026

**Independent check after the initial legal review; no rights gate opened.**

- [Spicy Lyrics API, Developer Terms dated 5 November 2026](https://developers.spicylyrics.org/terms) §§4–8 expressly permits in-app display of returned material and app-private caching of responses, **limited to 30 days** with refresh or deletion; it prohibits bulk extraction and external lyrics download/export and requires visible source-specific attribution. Section 8 explicitly **does not license underlying song words**. Only contributor-generated timing/formatting **sync data** has a limited sublicense. Apple Music/Spotify-sourced word content carries no rights warranty. Therefore this service **cannot clear mainstream lyrics text** for the proposed public Open Music app, nor can its 30-day cache permission override copyrights.
- [LRCLIB API docs](https://lrclib.net/docs) mention optional registration and API-authorization documents; these concern API access/rate limits and **do not demonstrate underlying song-word rights**. No rights-granting LRCLIB registration attempted.
- [Warner Chappell Music official digital licensing FAQ](https://warnerchappell.com/faqs/) identifies `wcmdigital@warnerchappell.com` for digital-license questions. On 6 November an **individual, non-binding** request was sent asking specifically whether a zero-cost public-client grant could cover full lyric display **and** app-private offline retention for any Warner-controlled works in Italy/EU; awaiting substantive reply. No fees or terms accepted. This is separate from the inquiries to LyricFind, UMPG, Musixmatch and SIAE already described.
- [Google's public explanation of lyrics rights](https://blog.google/products-and-platforms/products/search/how-we-help-you-find-lyrics-google-search/) confirms that lawful large-scale public lyrics display is addressed with payments to the publishers and separate licenses from content providers, unlike simply accessing a free endpoint. This does not exclude narrower individual exceptions; it is evidence about publisher-controlled display licensing.
- **Provider licensing ≠ copyright clearance.** Technical display is currently feasible, but no commercial-text rights or zero-cost sublicensable catalog for this exact app+offline use are evidenced. A restricted open-content-only path would not accomplish the desired broad, ordinary MP3/FLAC catalog and would require per-work clearance and matching before activation; do not count source-reported open catalog size as verified Italy/EU coverage.

**Execution snapshot:** the docs-only commit `df5bb8e` had a successful CI run `42230875312`; at this checkpoint the existing Android adapter/cache are still technically prepared but disabled. Do not enable public retrieval, add a bulk downloader, merge to main, or make a Play release without clearance. Keep the existing Todoist mission in WAITING pending a substantive written licensing response.
