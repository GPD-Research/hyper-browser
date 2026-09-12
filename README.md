# Hyper Browser

Lean Android dual-pane file browser built with Kotlin and Jetpack Compose.

## Version 3.1.0 (September 2026)

### Commands follow the selection, not the arrow

- Delete, rename, gallery and the other single-item commands act on whichever pane holds the
  selection. Only copy and move read the transfer arrow, so a folder picked in the pane the arrow
  points at is no longer deleted from the other side.

### Rotate

- Single-image view has a rotate-90°-clockwise button that saves to the same file. The turn is
  written as the orientation tag — in IFD0 for TIFF and the RAW formats built on it, through EXIF
  for JPEG, PNG and WebP — so the pixels, and a RAW file's sensor data, are never re-encoded.
- Because the write is a handful of bytes, the cost is redrawing the file afterwards. For RAW and
  TIFF whose decode would need more than about 60% of the heap this device grants the app, a
  prompt says so before anything is written.
- Orientation is now honoured when showing ordinary JPEG and PNG images too; previously only RAW
  and TIFF were turned to match their tag.
- Rotating a Google Drive image is not supported; copy it locally first.

### The inspector and moving between images

- The inspector closes whenever another image is shown — swiped to, tapped in the grid, or stepped
  onto after a delete. It belongs to the file it was opened on: the next one may be a JPEG with no
  inspector at all, and another RAW would otherwise be read at full resolution unasked.

### Coming back from the gallery

- Closing the gallery selects the image that was on screen in the pane it was opened from, so a
  swipe through a folder comes back to where it ended rather than where it began.
- The pane scrolls that row back into view, centred, whether the file is near the top of a folder
  or the bottom of a long one. A row already on screen is left where it is.

### Large TIFFs

- Opening a TIFF draws the file's own pixels rather than the thumbnail stored inside it. Those
  thumbnails are routinely a few hundred pixels wide, and stretching one across the screen is what
  made large TIFFs look soft; the embedded preview is now used only when it is at least as wide as
  the screen, or when the image itself cannot be decoded.
- Fitted images are decoded at twice the viewport, so the first frame is sharp on a dense display
  and survives a pinch before a sharper tile arrives. Zooming still refines a tile at a time out of
  the pyramid, which is what keeps a gigapixel file inside a fixed memory budget.
- Grid thumbnails follow the same rule, so a TIFF whose preview is smaller than the cell is
  rendered rather than upscaled.

### RAW inspector: compressed or uncompressed

- Inspecting a RAW file, a second toolbar button switches between the sensor data (uncompressed,
  the default) and the camera's own JPEG. The compressed view takes the largest JPEG in the file —
  the best rendition the camera wrote — decodes it at its native resolution in full colour rather
  than at viewport size, and keeps the JPEG so zooming pulls sharper crops out of it instead of
  magnifying the overview.
- The label names which one is on screen, e.g. `Embedded JPEG 6720×4480`.

### Warnings before wholesale changes

- Deleting more than one folder at once lists each folder with the number of files and subfolders
  inside it, and needs the "yes, delete all" box ticked. Deleting files stays a single tap.
- Copying or moving more than one folder into a destination lists the folders and where they are
  headed, and needs the same acknowledgement.

### Undo

- The last delete, copy or move can be undone from the command strip. Deleted items are parked in
  a hidden `.HyperBrowserTrash` folder beside where they came from and put back on undo; undoing a
  copy removes the copies. Backends that cannot move an item still delete it outright, and nothing
  is offered to undo in that case.
- Undo survives a rotation or a window resize.

### Layout

- The command strip runs the full height of the window, so the root chips and the direction arrow
  sit centred over their own panes and there is room for more commands. It still scrolls when the
  window is too short for all of them.
- The preview that follows a selected image is twice its old size in phone mode and three times it
  in tablet mode, and is decoded at that size rather than upscaled. The "tablet wide" layout mode
  is gone; the two remaining modes are phone and tablet.
- The root button above each pane shows the full path, right-aligned and truncated from the left,
  so the folder you are actually in stays visible as the path deepens. Shared storage is named
  "Internal Storage" rather than the "0" it is called on disk.
- Tablet mode enlarges the controls only. The file lists keep the same text size and row height as
  phone mode, so a larger screen shows more of each tree instead of the same rows written larger.

## Version 3.0.2 (September 2026)

### Immersive image viewing

- An image opened on its own fills the screen: the toolbar and filename are hidden, and the tap
  that already summoned the bottom menu brings them back.
- Zoom in and out stay one tap away through a pair of buttons over the bottom of the image.
- A zoomed image is clipped to its own area instead of painting over the toolbar and status bar.
- The inspector's provenance label steps aside while the menu covers that part of the screen.

## Version 3.0.1 (September 2026)

### Camera RAW sensor decoding

The inspector reads the photosites of compressed RAW files rather than falling back to the
embedded JPEG preview. Every decoder below was checked photosite-for-photosite against LibRaw on
real camera files and matches exactly:

- Sony ARW: uncompressed (14-bit samples in 16-bit words, which were previously read as packed
  bits and produced wrong values) and Sony's compressed 11+7-bit format.
- Canon CR2: lossless JPEG (SOF3) sensor data, including multi-slice frames.
- Nikon NEF: compressed and lossless-compressed Huffman data with the camera's linearization
  curve.
- Sony's encrypted metadata block is now decrypted correctly, so Sony files use the camera's own
  black point, saturation point and white balance instead of neutral defaults.

Canon's CR3 is not a TIFF and its sensor data is still not decoded; such files are labelled as
embedded preview rather than presented as sensor data.

### Inspector provenance

- The inspector states what it is showing: `Sensor data 7952×5304 (Sony compressed RAW)`,
  `Full resolution 29566×14321`, or `Embedded preview 6720×4480 — sensor data in this file cannot
  be read`.

### Gallery button

- Opens a selected image in view mode within its folder, a selected folder as a thumbnail grid,
  and the pane's current folder as a grid when nothing is selected. Previously it did nothing
  unless a single image file was selected.
- Viewing a single image, the top bar has a "Set as wallpaper" action. It hands the image to the
  device's own wallpaper handling — the system "Set as" targets plus the platform cropper — rather
  than setting the wallpaper itself, so whatever wallpaper apps are installed are what you get. It
  is also on the long-press image menu. Drive items must be copied to local storage first.

### Verification

`app/src/androidTest/.../RawSensorDecodeTest.kt` decodes Sony (uncompressed and compressed), Canon
CR2 and Nikon NEF fixtures on device and asserts the visible frame, the source label and that the
frame carries real tonal range; a test skips itself unless its fixture is pushed to the app's
external-files directory.

## Version 3.0.0 (September 2026)

### Full-resolution inspector for TIFF and RAW

- The TIFF region/arrow-pad mode is gone. TIFF and RAW now share one inspector, toggled from the
  single-image viewer, that pans and pinch-zooms over the source pixels and refines the visible
  area into sharp crops while the coarse overview stays on screen.
- Zooming out is supported; the zoom ceiling is derived from the source width and the viewport
  instead of a fixed factor, so 30k-pixel-wide images reach 1:1 without over-zooming small ones.
- A locator overlay marks the visible source rectangle on the overview and can be hidden.
- Crops are rendered at the requested viewport scale, so a selected region is no longer stretched
  as though it were the whole image.

### TIFF decoding

- Tiled TIFFs are supported alongside strips, and reduced-resolution SubIFDs/pyramid levels are
  used for overviews when a file provides them.
- Downsampling is box-filtered rather than nearest-neighbour, which is what made gallery thumbnails
  look blocky.
- Strips/tiles with no rows contributing to the current sampling are skipped, and only the crop's
  column prefix of each row is decompressed and predictor-corrected.
- Local files are memory-mapped instead of read onto the heap, so a ~500 MB image is decoded within
  the bounds of the requested output rather than as one full-resolution bitmap.

### RAW sensor data

- Full-resolution mode decodes actual sensor data instead of falling through to the embedded JPEG:
  packed 12/14-bit samples, tiled and strip layouts, black/white level normalization,
  `AsShotNeutral` white balance, sRGB gamma and demosaicing.
- Casual gallery browsing is unchanged and still preview-first.
- Files whose sensor data cannot be read (notably lossless-JPEG-compressed CR2/NEF) now say so
  instead of silently presenting the preview as full resolution.

### File browser

- **New Folder** in the command strip: creates a directory in the active pane's current folder,
  validating the name and refreshing the pane.

### Verification

Both ESA/Hubble `heic0707a.tif` fixtures (525 MB / 29566x14321 and 61 MB / 10000x4844) were decoded
on an emulator through `app/src/androidTest/.../TiffDecodeTest.kt`, which skips itself unless the
fixtures are pushed to the app's external-files directory. RAW sensor decoding has not yet been
verified against a real camera file.

## Version 2.0.2 (September 2026)

### RAW/TIFF rendering refinements

- `RawImage.decode()` now accepts a `ByteSource`, so mapped files (e.g. large TIFFs/RAWs) are decoded without copying the whole file onto the heap.
- Orientation is now read directly from the source for TIFF-based containers and from a bounded EXIF prefix for other formats.
- Full RAW demosaicing searches all IFDs for CFA/single-channel 16-bit sensor data and supports more TIFF compression schemes.
- TIFF full-resolution region mode is selected before the decode path, ensuring the selected region is rendered at full quality.

## Version 2.0.1 (September 2026)

### Major Image Quality Improvements

**TIFF Rendering Quality Fix**
- Fixed aggressive downsampling that was destroying TIFF image quality
- TIFF files now render at 2x viewport size for better zooming capability
- Small TIFF files render at full resolution instead of being unnecessarily downscaled
- Memory limits (16MP max, 8192px edge max) still prevent crashes on huge files

**TIFF Full Resolution Mode**
- New "Use full resolution (region)" mode for viewing massive TIFF files (e.g., 400MB Hubble images)
- Renders only a selected region at full quality instead of downsampling the entire image
- Automatic grid overlay shows current region position within the full image
- Grid matches image aspect ratio with subdivisions based on file size (16MB per square)
- Navigation controls (arrow buttons) to pan around the TIFF in 25% increments
- Allows photographers to view truly massive TIFF files at full quality without memory issues

**RAW File Full Sensor Data Mode**
- New "Use full RAW (slow)" toggle for RAW files (ARW, CR2, NEF, DNG, RAF, etc.)
- Implements bilinear demosaicing to convert RAW sensor data to full RGB
- Allows photographers to view the full sensor data instead of embedded JPEG preview
- Memory check: requires 512MB+ RAM to enable (prevents crashes on low-memory devices)
- Computationally expensive - intended for flagship Android devices with powerful processors
- Supports 16-bit single-channel RAW data with standard RGGB Bayer pattern

### Technical Details

**TIFF Downsampling Logic**
- Overview renders at 2x viewport size (allows zooming without pixelation)
- Thumbnails render at 2x target size for grid display
- Safety ceiling: 16MP pixel limit and 8192px edge limit enforced for all renders
- Region-based rendering for full resolution mode bypasses viewport downsampling

**RAW Demosaicing**
- Bilinear interpolation for Bayer pattern conversion
- Handles edge cases and boundary conditions
- Scales 16-bit sensor data to 8-bit for display
- Loads entire RAW data into memory (required for demosaicing algorithm)

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
- Inline rename in the file panes, committed with the Rename button or by selecting another file
- Camera RAW (ARW, CR2, NEF, DNG, RAF, RW2, ORF …), HEIC/HEIF and TIFF viewing: RAW files are shown
  from their largest embedded JPEG preview, TIFF is decoded in-app (no full demosaic)
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
