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

## Architecture notes

The image browser is intentionally kept simple and separate from the generic file browser. Image files use a lightweight in-app viewing flow with simple thumbnails and a zoomable single-image mode. Non-image files remain in the default-app open flow, while the app stays focused on file organization, selection, and transfer rather than full document editing.
