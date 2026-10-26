Android UI and file-access prototype.

- Redesigned native chat UI and Files & access tab.
- Project-folder access, text-file browsing/editing, and attachments.
- Read only, Project access, and Full access controls.
- Optional full shared-storage access on Android 11 and newer.
- Bundled arm64 and x86_64 Codex runtime executables.

**Known blocker:** the app-owned Codex connection closes during startup in device testing. Real login and live chat are not working end to end. UI previews use sample data. This prerelease is for UI and file-access evaluation.

Android 9 or newer. This is an unofficial, debug-signed development APK. Tests and APK assembly must pass in GitHub Actions before this release is published; full device validation remains pending.

Download `codex-android-debug.apk` below. Source and build instructions are in the repository.
