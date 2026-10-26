# Open Music development notes

Product focus: privacy-respecting local-first player. Portable playlist v1 is a stable interoperability contract with the original Python POC. Keep core independent of Android SDK and cloud vendor APIs.

Security and behavior invariants:
- Do not export or log absolute paths, Android content URIs, provider OAuth tokens or audio bytes in portable playlists.
- Exact SHA-256 matches are safe; never automatically link a different SHA-256 based on metadata.
- Preserve missing playlist entries, order and duplicates.
- Do not scan remote mounts or upload audio without an explicit user request.
- Keep changes on feature branches until CI is verified and never modify the unrelated our-soundtrack repository.

CI uses Gradle 8.13, JDK 17, Android API 36 and runs:
~~~sh
gradle :core:test :app:assembleDebug --no-daemon --stacktrace
~~~

Open Music is a **public, MIT-licensed open-source project** by explicit owner decision. The current phase is disposable Android QA: publish public experimental debug APKs on successful feature-branch push with the runner's ordinary debug certificate. The tester expressly accepts uninstall/reinstall even when it clears local playlists, so do not gate this QA phase on persistent signing, GitHub Secrets, or private repository visibility. Do not promise that in-place updates will work across runner certificates. Stable QA/release signing and migration will be handled in a later phase. Never commit secrets, signing keys, private audio or device data. GitHub Actions artifact quota is exhausted; use GitHub Releases to distribute app-debug.apk, verify the exact run/commit and APK asset, and keep PR #1 draft with no merge to `main` or store publication before device QA.

Source boundaries:
- core/src/main/kotlin: pure matching engine and portable models.
- app/src/main/java: Android MediaStore, JSON codec, private persistence and Compose UI.
- docs: architecture and phased roadmap.
