# KBZip

An open-source, lossless archive format, made for Karen Benny.

- `SPEC.md`: the format specification (v1).
- `app/src/main/java/org/kbzip/format/`: reference implementation (pure Kotlin/JVM, no Android dependencies).
- `app/src/main/java/org/kbzip/app/`: Android app to create archives, extract them, and find `.kbz` files on your phone.
- `app/src/test/`: round-trip and corruption tests (`./gradlew test`).

## Build the app

1. Open this folder in Android Studio (Koala or newer). Let it sync Gradle.
2. Run `app` on a device or emulator (Android 7.0+), or *Build > Build APK(s)*.
3. Run the format tests with `./gradlew :app:testDebugUnitTest`.

## Build with GitHub Actions

Push this project to a GitHub repository and the workflows do the rest:

- `.github/workflows/build.yml` runs on every push and pull request. It runs the format tests, builds the APK, and uploads it as the **KBZip-debug-apk** artifact (Actions tab, open the run, download from *Artifacts*).
- `.github/workflows/release.yml` runs when you push a tag such as `v1.0.0` and attaches the APK to a GitHub Release.

The APKs are debug-signed, which is fine for installing on your own phone and Karen's (allow installs from unknown sources). A Play Store release would need your own signing key stored as repository secrets.

## Using the app

- **Open a KBZip file**: pick a `.kbz`, browse and search its contents, tap a file to save it, or extract everything to a folder.
- **Find KBZip files**: pick a folder (or a storage root); the app scans it for `.kbz` files. Android only lets apps see folders you grant, so scan e.g. *Downloads* or the internal-storage root.
- **Create an archive**: add files and/or folders, optionally edit the dedication, then choose where to save.
- Tapping a `.kbz` in a file manager opens it in KBZip.

License: MIT.
