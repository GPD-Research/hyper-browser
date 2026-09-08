# UI Overhaul: Recursive File Tree & Preferences

The goal is to replace the "oval button" navigation with a traditional, recursive file tree view where folders expand and collapse in place. We will also address recent crashes and add a settings mechanism to persist default startup paths.

## User Review Required

> [!IMPORTANT]
> The navigation model is changing from "drilling into folders" to "expanding nodes in a tree". This will allow seeing multiple directory levels simultaneously.

## Proposed Changes

### 1. Stability & Fixes
- **FileProvider Integration**: (Already started) Finish wiring `FileProvider` to fix `FileUriExposedException` when opening files from the `/sdcard` root via direct `file://` URIs.
- **Robustness**: Ensure `getDocumentFile` and other file operations gracefully handle permissions and missing files to prevent the reported crashes.

### 2. File Tree UI
- **Recursive Tree View**: Implement a `FileTreeItem` component that renders folders and files with proper indentation.
- **State Management**: Update `BrowserPaneState` to track `expandedFolders` (a set of Uris) so that expansion state is preserved during scrolling.
- **Visual Style**: Remove the large oval buttons and replace them with a compact, tree-like structure using `KeyboardArrowRight` and `KeyboardArrowDown` icons.

### 3. Preferences & Settings
- **Persistence**: Use `SharedPreferences` to store the "Default Root" and "Default Current Folder" for both the Left and Right panes.
- **Settings Dialog**: Add an option in the Settings menu to "Set Current View as Default", so the app starts exactly where the user left off.
- **Auto-Load**: Update the app initialization to load these saved paths on startup.

### 4. Testing & Gallery
- **Dummy Data**: Use the generated screenshots in `/Pictures/HyperBrowserTest` to verify the Image Gallery mode and ensure it functions correctly with direct file access.

## Verification Plan

### Automated Tests
- Run `gradle build` to ensure no regression in compilation.

### Manual Verification
- Launch the app and grant "All Files Access".
- Navigate to the device root.
- Expand several folders (e.g., `Android`, `DCIM`, `Pictures`) and verify they show their contents inline with indentation.
- Collapse a folder and verify it hides its children.
- Open the `Pictures/HyperBrowserTest` folder, double-click an image, and verify it launches the gallery.
- Save the current view as default in Settings, restart the app, and verify it restores the tree state.
