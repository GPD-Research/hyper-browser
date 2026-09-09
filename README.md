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

Notes:

- The full `drive` scope is required. `DriveScopes.APPFOLDER`/`APPDATA` only expose files this app
  created, which is useless for a file manager.
- Google-native documents (Docs, Sheets, Slides) hold no bytes, so copying one out exports it —
  PDF for documents, PNG for drawings — exactly like the SAF virtual-document path.
- Drive items use synthetic `gdrive://` URIs and cannot be handed to other apps directly. Copy them
  to local storage first to open them elsewhere.

## Architecture notes

The image browser is intentionally kept simple and separate from the generic file browser. Image files use a lightweight in-app viewing flow with simple thumbnails and a zoomable single-image mode. Non-image files remain in the default-app open flow, while the app stays focused on file organization, selection, and transfer rather than full document editing.
