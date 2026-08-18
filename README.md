# Hyper Browser

Lean Android dual-pane file browser built with Kotlin and Jetpack Compose.

## Current status

This repo now includes a working Android project scaffold and a functional dual-pane SAF browser foundation:

- Two side-by-side directory panes, each with its own rooted SAF tree
- Active pane tracking and opposite-pane transfer target selection
- Direction arrow to flip the transfer vector without changing the selection
- Copy/move actions that operate against the selected items and target folder
- Safe transfer handling with duplicate-name resolution and selection clearing after each action
- Transfer status strip showing the active source, selected count, and target folder
- Preview/detail pane summarizing the current file or folder metadata before open/copy/move actions
- Image-first view flow: selecting an image offers Open and View, with View launching an in-app zoomable image browser
- In-app image gallery behavior: zooming out fully reveals a thumbnail strip of the directory, then another image can be selected without leaving the viewer
- Non-image files use the default application flow: Open sends the file to the user’s default app, and the OS chooser handles the first-launch app selection flow when no default exists
- Text-only browsing with no background indexing or thumbnail generation
- Intent delegation for app-open actions through the Android chooser
- Devcontainer support to restore the Android SDK automatically on rebuild

## Build

The Codespaces setup is now captured in [.devcontainer/devcontainer.json](.devcontainer/devcontainer.json). The Android SDK is installed in the workspace environment and the project builds with Java 17 and Android API 35.

To build locally from the repo root:

```sh
gradle :app:assembleDebug
```

## Notes

This slice focuses on the browser shell, SAF transfer model, transfer UX, and image-specific viewing flow. The native Rust copy/move engine remains the next integration step for high-performance batch operations and sub-sampled image parsing.
