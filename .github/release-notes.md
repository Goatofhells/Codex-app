Login compatibility update for Android (candidate fix).

- Updates bundled Codex from 0.114.0 to **0.163.0**, matching the user's working Ubuntu/proot installation.
- Uses the bundled **`codex login` CLI flow** directly, rather than the custom app-server client's authorization flow.
- Opens the CLI-generated URL unchanged, including its standard CLI originator, PKCE/state, and `127.0.0.1` callback.
- Keeps sign-in alive with a temporary foreground notification while Chrome is in front; stops on completion, cancellation, or timeout.
- Restarts app-server after CLI login and verifies the saved account.
- Retains project-folder/full-storage controls and file browsing/editing/attachments.

Fourteen tests pass locally, including actual bundled-runtime OAuth URL and account-handshake tests. CI tests and APK assembly must also pass before publication.

**Still requires device verification:** successful consent-page loading, browser callback, token exchange, and authenticated chat have not been confirmed on the user's phone. This is a candidate fix, not a claim that end-to-end login has passed. Linux sandbox/helper and optional voice-resource compatibility is not established.

**Installation:** this is a debug-signed alpha for Android 9+. If Android rejects updating the earlier alpha because signatures differ, uninstall the older APK first, then install this one. Uninstalling removes its local app state. Stable production signing is not configured yet.

Download `codex-android-debug.apk` below. `SHA256SUMS.txt` contains its checksum. This is an unofficial client.
