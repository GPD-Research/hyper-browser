# Hyper Browser

Lean Android dual-pane file browser built with Kotlin and Jetpack Compose.

## Current status

This repo now includes a working Android project scaffold and a functional dual-pane SAF browser foundation:

- Two side-by-side directory panes, each with its own rooted SAF tree
- Active pane tracking and opposite-pane transfer target selection
- Direction arrow to flip the transfer vector without changing the selection
- Copy/move actions that operate against the selected items and target folder
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

This slice focuses on the browser shell and SAF transfer model. The native Rust copy/move engine remains the next integration step for high-performance batch operations and sub-sampled image parsing.
