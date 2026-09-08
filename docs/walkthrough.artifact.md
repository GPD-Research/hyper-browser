# Root Access Implementation Walkthrough

I have implemented support for accessing the device root storage, allowing the app to function as a standard file browser.

## Changes Made

### 1. Permission Handling
- Added `MANAGE_EXTERNAL_STORAGE` to [AndroidManifest.xml](file:///home/gregory-dearth/projects/hyper-browser/app/src/main/AndroidManifest.xml).
- Added logic in [MainActivity.kt](file:///home/gregory-dearth/projects/hyper-browser/app/src/main/java/org/gpdresearch/hyperbrowser/MainActivity.kt) to check for "All Files Access" on Android 11+ and provide a shortcut to the system settings if permission is missing.

### 2. Universal File Access
- Introduced `getDocumentFile` helper function in [MainActivity.kt](file:///home/gregory-dearth/projects/hyper-browser/app/src/main/java/org/gpdresearch/hyperbrowser/MainActivity.kt) which transparently handles both Storage Access Framework (SAF) `content://` URIs and direct `file://` URIs.
- Refactored the app to use this helper instead of hardcoded `DocumentFile.fromTreeUri` calls, ensuring consistent behavior across all storage sources.

### 3. Folder Picker Enhancements
- Updated [FolderPickerDialog](method://org.gpdresearch.hyperbrowser.MainActivityKt#FolderPickerDialog) to:
    - Offer a "Grant All Files Access" button when appropriate.
    - Provide a "Use Device Root (/sdcard)" option once permission is granted, allowing users to start browsing at the user root (Documents, Pictures, Movies, etc.).

## Verification Results

### Build Status
- [x] `app:assembleDebug` completed successfully.

### Manual Verification Steps Recommended
1. Run the app and click "Choose root" in any pane.
2. If on Android 11+, click "Grant All Files Access" to open system settings and enable the toggle for Hyper Browser.
3. Return to the app and select "Use Device Root".
4. Verify that you can see and navigate through standard system folders like `Download`, `Documents`, and `Pictures`.
