# Hyper Browser

Lean Android dual-pane file browser built with Kotlin and Jetpack Compose.

## Current status

This repo now contains a buildable Android project scaffold for a lean dual-pane SAF browser. The app has been verified with:

```sh
gradle --no-daemon :app:compileDebugKotlin
```

Key behavior implemented:

- Two side-by-side file panes rooted in independent SAF directories
- A minimal transfer control showing source and destination labels with a single arrow to reverse the direction
- A vertical command strip for Copy, Paste, Move, Delete, and selection handling
- Image-aware logic with a separate View flow from the generic Open flow
- Simple in-app image browsing using a single-image viewer and a thumbnail strip for easy navigation
- Default-app open flow for non-image files, keeping the app focused on file movement and organization
- Devcontainer support that restores the Android SDK automatically on rebuild

## Build

The Codespaces configuration is stored in [.devcontainer/devcontainer.json](.devcontainer/devcontainer.json). The repo is configured to build with Java 17 and Android API 35.

To build from the project root:

```sh
gradle :app:assembleDebug
```

## Google Drive setup

Drive's `DocumentsProvider` refuses tree grants, so SAF can never hand back "My Drive" as a
browsable folder. The app therefore talks to the **Drive REST API v3** directly, which is what other
file managers do. Browsing, copying, moving and deleting all work against Drive through the same
panes as local storage.

Drive access needs an OAuth client that you own — the app ships no credentials:

1. In the [Google Cloud console](https://console.cloud.google.com/), create a project and enable the
   **Google Drive API**.
2. Configure the OAuth consent screen. `https://www.googleapis.com/auth/drive` is a *restricted*
   scope, so while the app is unverified add your own account under **Test users**. Public
   distribution requires Google's restricted-scope verification (security assessment included).
3. Create credentials → **OAuth client ID** → **Android**, using package name
   `org.gpdresearch.hyperbrowser` and the SHA-1 of the keystore the APK is signed with. For debug
   builds that is `~/.android/debug.keystore`:

   ```sh
   keytool -list -v -alias androiddebugkey -keystore ~/.android/debug.keystore \
     -storepass android -keypass android | grep SHA1
   ```

   Register a second client for your release keystore before shipping.
4. Launch the app, open a root chip, and pick **Google Drive**. The account is remembered, so later
   launches reconnect without prompting.

Sign-in failing with `ApiException: 10` (DEVELOPER_ERROR) means the package name or SHA-1 on the
OAuth client does not match the APK that is running.

### What users see on a sideloaded install

Two unrelated warning screens show up, and they come from different systems:

1. **"Install unknown apps" / Play Protect** — from Android, for any APK not installed via the Play
   Store. The user allows their browser or file manager to install apps, then taps *Install anyway*.
   Removed only by shipping through Play.
2. **"Google hasn't verified this app"** — from OAuth, because `drive` is a restricted scope. The
   user taps *Advanced* → *Go to Hyper Browser (unsafe)*. Removed only by passing restricted-scope
   verification, which requires a CASA Tier 2 assessment (~$600/yr).

The consent screen then requires ticking the granular-permission box for Drive. If the user leaves
it unticked, sign-in succeeds but the Drive scope is absent; `DriveAuth.hasDriveScope` catches that
and the UI reports the permission as declined rather than showing an empty pane.

Both screens are walked through for end users on the [project site](https://gpd-research.com).
To have them removed today, PayPal $1000 to the support address and the maintainer will get right on
Play Store distribution and a CASA assessment. Offer void where prohibited; prohibited everywhere.

Notes:

- The full `drive` scope is required. `DriveScopes.APPFOLDER`/`APPDATA` only expose files this app
  created, which is useless for a file manager.
- Google-native documents (Docs, Sheets, Slides) hold no bytes, so copying one out exports it —
  PDF for documents, PNG for drawings — exactly like the SAF virtual-document path.
- Drive items use synthetic `gdrive://` URIs and cannot be handed to other apps directly. Copy them
  to local storage first to open them elsewhere.

## Release builds

Release signing is wired up in [app/build.gradle.kts](app/build.gradle.kts). Credentials are read
from `keystore.properties` in the repo root (git-ignored) or, on CI, from the environment
(`HB_KEYSTORE_FILE`, `HB_KEYSTORE_PASSWORD`, `HB_KEY_ALIAS`, `HB_KEY_PASSWORD`). When neither is
present the build still succeeds but prints
`No release keystore configured - release artifacts will be unsigned.`

Create the upload key once, then never lose it — Play ties the listing to it:

```sh
keytool -genkeypair -v -keystore hyper-browser-upload.jks -alias upload \
  -keyalg RSA -keysize 4096 -validity 10000
```

Then write `keystore.properties` (never commit it):

```properties
storeFile=hyper-browser-upload.jks
storePassword=…
keyAlias=upload
keyPassword=…
```

Build the bundle for Play Console:

```sh
./gradlew :app:bundleRelease
# app/build/outputs/bundle/release/app-release.aab
```

R8/resource shrinking is deliberately off: the Drive REST models are bound reflectively by GSON and
would need a keep-rule audit first.

Register the release key's SHA-1 as a second Android OAuth client (see *Google Drive setup*), or
Drive sign-in fails with `ApiException: 10` in release builds. If you opt into **Play App Signing**,
the SHA-1 that matters at runtime is Google's app-signing certificate from the Play Console, not the
upload key.

## Permissions

| Permission | Why it is needed |
| --- | --- |
| `INTERNET` | Google Drive REST API v3 requests. |
| `ACCESS_NETWORK_STATE` | Fail Drive operations fast with an offline message instead of hanging. |
| `READ_EXTERNAL_STORAGE` (`maxSdkVersion=32`) | Shared-storage reads before Android 13; replaced by the `READ_MEDIA_*` grants after that. |
| `WRITE_EXTERNAL_STORAGE` (`maxSdkVersion=28`) | Copy/move/delete targets on Android 9 and older, before scoped storage. |
| `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` / `READ_MEDIA_AUDIO` | Android 13+ granular media reads for listings, grid thumbnails and the gallery viewer. |
| `MANAGE_EXTERNAL_STORAGE` | Browsing arbitrary folders (internal storage root, SD card, USB) and transferring files between them. |

All of them are requested at runtime from `missingRuntimePermissions()`; All-files access is an
opt-in trip to system settings and the app degrades to per-folder SAF grants without it.

`MANAGE_EXTERNAL_STORAGE` is the only one that gates the Play listing. It needs the Play Console
**permissions declaration form**:

- Use case: **file manager**.
- Justification: the app is a dual-pane file manager whose whole purpose is moving files between
  arbitrary locations. SAF cannot grant a tree on the internal-storage root (the system picker
  blocks that directory), and MediaStore only exposes media files, so neither API can back
  browsing or transferring non-media files across volumes.
- Upload a demo video showing browsing, copy, move and delete across two panes.

Direct-APK and F-Droid distribution need no such declaration.

## Architecture notes

The image browser is intentionally kept simple and separate from the generic file browser. Image files use a lightweight in-app viewing flow with simple thumbnails and a zoomable single-image mode. Non-image files remain in the default-app open flow, while the app stays focused on file organization, selection, and transfer rather than full document editing.
