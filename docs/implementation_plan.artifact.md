# Storage Expansion: Cloud & SD Card Integration

The goal is to simplify storage selection, provide clear paths for SD Card and Google Drive integration, and ensure cross-storage file operations work reliably.

## User Review Required

> [!NOTE]
> We will leverage Android's built-in "Storage Access Framework" (SAF) for Cloud and SD Card support to avoid bulky third-party SDKs, but we will hide the technical terminology from the user.

## Proposed Changes

### 1. Folder Picker Redesign
- **Simplified Terminology**: Replace "SAF mode" with user-friendly labels like "Cloud, SD Card, or USB".
- **Structured Selection**: Organize the picker into two primary sections:
    - **Quick Access**: "Internal Storage (Device Root)".
    - **Advanced/External**: "Cloud & SD Card (Drive, SD, etc.)".
- **Enhanced UI**: Use icons and better grouping in the `FolderPickerDialog`.

### 2. Google Drive & Cloud Support
- **Seamless Integration**: Since the app already uses `DocumentFile`, selecting a Google Drive folder through the new "Cloud" option will allow the app to browse, copy, and move files to/from the cloud just like local storage.
- **Root Management**: Ensure that when a Cloud root is selected, it is persisted correctly in preferences for the next launch.

### 3. Image Editor Fixes
- **Action Intent Refinement**: Update `ACTION_EDIT` logic to be more permissive and handle cases where some editors might require `ACTION_SEND` with specific extra flags.
- **MIME Type Broadening**: (Already improved) Continue to verify that RAW and other specialized image formats are correctly identified to ensure the "editing not supported" error is resolved.

### 4. Cross-Storage Performance
- **Buffer Optimization**: Ensure `copyStream` uses an appropriately sized buffer for large file transfers (e.g., high-res RAW photos) between the device and cloud storage.

## Verification Plan

### Manual Verification
- **SD Card Access**: Click "Cloud & SD Card" in the picker, select the SD Card root, and verify the tree displays SD card contents.
- **Google Drive Access**: Click "Cloud & SD Card", select a Google Drive folder, and verify browsing/copying works.
- **Cloud-to-Local Transfer**: Select a file in a Drive-rooted pane and copy it to an Internal-rooted pane.
- **Image Editing**: Open a JPG or PNG from the gallery and select "Open in Editor". Verify that installed photo editors (e.g., Google Photos, Snapseed) open the file successfully.
