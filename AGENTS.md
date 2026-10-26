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

Open Music is explicitly **public and open source (MIT)** by owner decision, reconfirmed 26 October 2026. Never gate builds, testing or public experimental QA releases on private repository visibility. Check core tests and any downloadable QA APK against the exact pushed commit; GitHub Actions artifact quota is exhausted. The only APK publishing gate is a **persistent, owner-controlled QA signing certificate** stored in GitHub Actions secrets. Build/test without a signing secret must still pass, but publishing ephemeral-runner-signed APKs risks incompatible updates and playlist data loss. Remind testers to export portable JSON v1 before any unavoidable one-time reinstall. Never commit signing keys, credentials, private audio or device data. Keep PR #1 draft; no merge to `main` or store release before explicit QA gate.

Source boundaries:
- core/src/main/kotlin: pure matching engine and portable models.
- app/src/main/java: Android MediaStore, JSON codec, private persistence and Compose UI.
- docs: architecture and phased roadmap.
