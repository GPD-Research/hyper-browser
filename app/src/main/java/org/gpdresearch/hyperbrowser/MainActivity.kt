package org.gpdresearch.hyperbrowser

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.Settings
import android.webkit.MimeTypeMap
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

private enum class Pane { LEFT, RIGHT }
private enum class TransferDirection { LEFT_TO_RIGHT, RIGHT_TO_LEFT }
private enum class TransferMode { COPY, MOVE }
private enum class GalleryMode { SINGLE, THUMBNAILS }
private enum class LayoutMode {
    PHONE,
    TABLET_BALANCED,
    TABLET_WIDE,
}

private data class FileDisplayOptions(
    val showHiddenFiles: Boolean = false,
    val showTrashFiles: Boolean = false,
)

private data class BrowserPaneState(
    val root: Uri? = null,
    val current: Uri? = null,
    val selected: Set<Uri> = emptySet(),
    val refreshKey: Int = 0,
)

private data class DirectoryListing(
    val name: String?,
    val files: List<DocumentFile>,
)

private data class TransferRequest(
    val sourceDir: Uri,
    val targetDir: Uri,
    val selected: Set<Uri>,
    val wholeDirectory: Boolean,
    val mode: TransferMode,
)

private data class SelectionInfo(
    val title: String,
    val kind: String,
    val sizeLabel: String,
    val path: String,
    val details: String,
)

private data class ClipboardEntry(
    val sourceDir: Uri,
    val items: Set<Uri>,
    val mode: TransferMode,
)

private data class StorageRoot(
    val label: String,
    val directory: File,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { HyperBrowserApp() }
    }
}

@Composable
private fun HyperBrowserApp() {
    val activity = LocalContext.current as? ComponentActivity ?: return

    var leftPane by remember { mutableStateOf(BrowserPaneState()) }
    var rightPane by remember { mutableStateOf(BrowserPaneState()) }
    var activePane by remember { mutableStateOf(Pane.LEFT) }
    var transferDirection by remember { mutableStateOf(TransferDirection.LEFT_TO_RIGHT) }
    var layoutMode by remember { mutableStateOf(LayoutMode.PHONE) }
    var pendingRequest by remember { mutableStateOf<TransferRequest?>(null) }
    var pendingDelete by remember { mutableStateOf<Set<Uri>?>(null) }
    var galleryUri by remember { mutableStateOf<Uri?>(null) }
    var galleryDirectory by remember { mutableStateOf<Uri?>(null) }
    var showLayoutSettings by remember { mutableStateOf(false) }
    var fileDisplayOptions by remember { mutableStateOf(FileDisplayOptions()) }
    var selectionPreviewVisible by remember { mutableStateOf(true) }
    var selectionPreviewOffset by remember { mutableStateOf(Offset.Zero) }
    var clipboard by remember { mutableStateOf<ClipboardEntry?>(null) }

    var showLeftPicker by remember { mutableStateOf(false) }
    var showRightPicker by remember { mutableStateOf(false) }

    val sourcePane = if (transferDirection == TransferDirection.LEFT_TO_RIGHT) Pane.LEFT else Pane.RIGHT
    val destinationPane = if (sourcePane == Pane.LEFT) Pane.RIGHT else Pane.LEFT
    val sourceState = if (sourcePane == Pane.LEFT) leftPane else rightPane
    val destinationState = if (destinationPane == Pane.LEFT) leftPane else rightPane
    val scope = rememberCoroutineScope()
    val selected = sourceState.selected
    val selectedFile = selected.singleOrNull()
    val selectedMimeType by produceState(initialValue = "", selectedFile) {
        value = selectedFile?.let { uri -> withContext(Dispatchers.IO) { resolveMimeType(activity.contentResolver, uri) } } ?: ""
    }
    val isImageSelected = selectedMimeType.startsWith("image/")
    val idleSelectionInfo = remember { buildSelectionInfo(activity, emptySet()) }
    val selectionInfo by produceState(initialValue = idleSelectionInfo, selected, activity) {
        value = if (selected.isEmpty()) idleSelectionInfo else withContext(Dispatchers.IO) { buildSelectionInfo(activity, selected) }
    }
    val sourceLabel by produceState(initialValue = "source", sourceState.current) {
        value = sourceState.current?.let { uri -> withContext(Dispatchers.IO) { resolveDisplayPath(activity, uri) } } ?: "source"
    }
    val destinationLabel by produceState(initialValue = "destination", destinationState.current) {
        value = destinationState.current?.let { uri -> withContext(Dispatchers.IO) { resolveDisplayPath(activity, uri) } } ?: "destination"
    }

    fun refreshPanesAfterWrite() {
        leftPane = leftPane.copy(refreshKey = leftPane.refreshKey + 1, selected = if (sourcePane == Pane.LEFT) emptySet() else leftPane.selected)
        rightPane = rightPane.copy(refreshKey = rightPane.refreshKey + 1, selected = if (sourcePane == Pane.RIGHT) emptySet() else rightPane.selected)
    }

    fun runTransfer(request: TransferRequest) {
        scope.launch {
            withContext(Dispatchers.IO) { executeTransfer(activity, request) }
            refreshPanesAfterWrite()
        }
    }

    fun copyToClipboard(mode: TransferMode) {
        val sourceDir = sourceState.current ?: sourceState.root ?: return
        clipboard = ClipboardEntry(sourceDir = sourceDir, items = sourceState.selected, mode = mode)
    }

    fun pasteClipboard() {
        val entry = clipboard ?: return
        val activeState = if (activePane == Pane.LEFT) leftPane else rightPane
        val targetDir = activeState.current ?: activeState.root ?: return
        clipboard = null
        val request = TransferRequest(
            sourceDir = entry.sourceDir,
            targetDir = targetDir,
            selected = entry.items,
            wholeDirectory = entry.items.isEmpty(),
            mode = entry.mode,
        )
        // Pasting with nothing selected transfers the whole folder, so make it explicit.
        if (request.wholeDirectory) pendingRequest = request else runTransfer(request)
    }

    fun openGallery(uri: Uri, directory: Uri?) {
        galleryDirectory = directory
        galleryUri = uri
    }

    fun handleFileDoubleTap(uri: Uri, directory: Uri?) {
        scope.launch {
            val mime = withContext(Dispatchers.IO) { resolveMimeType(activity.contentResolver, uri) }
            if (mime.startsWith("image/")) {
                openGallery(uri, directory)
            } else {
                openFileWithDefaultApp(activity, uri)
            }
        }
    }

    val runtimePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    LaunchedEffect(Unit) {
        val missing = missingRuntimePermissions(activity)
        if (missing.isNotEmpty()) {
            runtimePermissionLauncher.launch(missing)
        }

        // Folder grants survive restarts, so reopen the last roots instead of asking again.
        val persisted = withContext(Dispatchers.IO) {
            activity.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri }
        }
        persisted.getOrNull(0)?.let { uri ->
            if (leftPane.root == null) leftPane = BrowserPaneState(root = uri, current = uri)
        }
        persisted.getOrNull(1)?.let { uri ->
            if (rightPane.root == null) rightPane = BrowserPaneState(root = uri, current = uri)
        }
    }

    MaterialTheme {
        Scaffold { contentPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            ) {
            Column(
                modifier = Modifier
                    .fillMaxSize(),
            ) {
                MinimalTransferMenu(
                    direction = transferDirection,
                    onReverse = {
                        transferDirection = if (transferDirection == TransferDirection.LEFT_TO_RIGHT) TransferDirection.RIGHT_TO_LEFT else TransferDirection.LEFT_TO_RIGHT
                    },
                    onChooseLeftRoot = { showLeftPicker = true },
                    onChooseRightRoot = { showRightPicker = true },
                    sourceLabel = sourceLabel,
                    destinationLabel = destinationLabel,
                )

                if (galleryUri != null) {
                    ImageViewerScreen(
                        activity = activity,
                        startingUri = galleryUri!!,
                        directoryUri = galleryDirectory,
                        onClose = { galleryUri = null },
                    )
                } else {
                    Box(modifier = Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CommandStrip(
                                layoutMode = layoutMode,
                                pasteEnabled = clipboard != null,
                                onCopy = { copyToClipboard(TransferMode.COPY) },
                                onPaste = { pasteClipboard() },
                                onMove = { copyToClipboard(TransferMode.MOVE) },
                                onDelete = {
                                    val items = sourceState.selected.ifEmpty { setOfNotNull(sourceState.current) }
                                    if (items.isNotEmpty()) {
                                        pendingDelete = items
                                    }
                                },
                                onGallery = {
                                    val image = selectedFile?.takeIf { isImageSelected }
                                    if (image != null) {
                                        openGallery(image, sourceState.current)
                                    }
                                },
                                onSelectMulti = { activePane = sourcePane },
                                onOpenSettings = { showLayoutSettings = true },
                            )

                            DirectoryPane(
                                title = "",
                                state = leftPane,
                                isActive = activePane == Pane.LEFT,
                                modifier = Modifier.weight(if (layoutMode == LayoutMode.TABLET_WIDE) 1.6f else if (layoutMode == LayoutMode.TABLET_BALANCED) 1.2f else 1f),
                                onActivate = { activePane = Pane.LEFT },
                                onChooseRoot = { showLeftPicker = true },
                                onNavigate = { directory -> leftPane = leftPane.copy(current = directory, selected = emptySet()) },
                                onOpenFile = { uri -> handleFileDoubleTap(uri, leftPane.current ?: leftPane.root) },
                                onMoveUp = {
                                    val parent = leftPane.current?.let { parentDirectoryUri(activity, it) }
                                    if (parent != null) {
                                        leftPane = leftPane.copy(current = parent, selected = emptySet())
                                    }
                                },
                                onSelectionChange = { selectedSet -> leftPane = leftPane.copy(selected = selectedSet) },
                                onImageLongPress = { uri -> openGallery(uri, leftPane.current ?: leftPane.root) },
                                showHiddenFiles = fileDisplayOptions.showHiddenFiles,
                                showTrashFiles = fileDisplayOptions.showTrashFiles,
                            )

                            DirectoryPane(
                                title = "",
                                state = rightPane,
                                isActive = activePane == Pane.RIGHT,
                                modifier = Modifier.weight(if (layoutMode == LayoutMode.TABLET_WIDE) 1.6f else if (layoutMode == LayoutMode.TABLET_BALANCED) 1.2f else 1f),
                                onActivate = { activePane = Pane.RIGHT },
                                onChooseRoot = { showRightPicker = true },
                                onNavigate = { directory -> rightPane = rightPane.copy(current = directory, selected = emptySet()) },
                                onOpenFile = { uri -> handleFileDoubleTap(uri, rightPane.current ?: rightPane.root) },
                                onMoveUp = {
                                    val parent = rightPane.current?.let { parentDirectoryUri(activity, it) }
                                    if (parent != null) {
                                        rightPane = rightPane.copy(current = parent, selected = emptySet())
                                    }
                                },
                                onSelectionChange = { selectedSet -> rightPane = rightPane.copy(selected = selectedSet) },
                                onImageLongPress = { uri -> openGallery(uri, rightPane.current ?: rightPane.root) },
                                showHiddenFiles = fileDisplayOptions.showHiddenFiles,
                                showTrashFiles = fileDisplayOptions.showTrashFiles,
                            )
                        }

                        if (selectedFile != null && isImageSelected && galleryUri == null && selectionPreviewVisible) {
                            SelectionThumbnail(
                                activity = activity,
                                uri = selectedFile,
                                offset = selectionPreviewOffset,
                                onOffsetChange = { selectionPreviewOffset = it },
                                onClose = { selectionPreviewVisible = false },
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(12.dp),
                            )
                        }
                    }

                    PreviewDetailPane(
                        info = selectionInfo,
                        selectedFile = selectedFile,
                        isImage = isImageSelected,
                        onOpen = { selectedFile?.let { openFileWithDefaultApp(activity, it) } },
                        onView = { selectedFile?.let { if (isImageSelected) openGallery(it, sourceState.current) } },
                    )
                }
            }
            }
        }
    }

    if (pendingRequest != null) {
        ConfirmTransferDialog(
            request = pendingRequest!!,
            onDismiss = { pendingRequest = null },
            onConfirm = { request ->
                pendingRequest = null
                runTransfer(request)
            },
        )
    }

    if (pendingDelete != null) {
        ConfirmDeleteDialog(
            count = pendingDelete!!.size,
            onDismiss = { pendingDelete = null },
            onConfirm = {
                val items = pendingDelete!!
                pendingDelete = null
                scope.launch {
                    withContext(Dispatchers.IO) { deleteItems(activity, items) }
                    refreshPanesAfterWrite()
                }
            },
        )
    }

    if (showLayoutSettings) {
        LayoutSettingsDialog(
            selectedMode = layoutMode,
            showHiddenFiles = fileDisplayOptions.showHiddenFiles,
            showTrashFiles = fileDisplayOptions.showTrashFiles,
            onSelect = { mode ->
                layoutMode = mode
                showLayoutSettings = false
            },
            onToggleHiddenFiles = { fileDisplayOptions = fileDisplayOptions.copy(showHiddenFiles = it) },
            onToggleTrashFiles = { fileDisplayOptions = fileDisplayOptions.copy(showTrashFiles = it) },
            onDismiss = { showLayoutSettings = false },
        )
    }

    if (showLeftPicker) {
        FolderPickerDialog(
            onFolderSelected = { uri ->
                leftPane = BrowserPaneState(root = uri, current = uri, selected = emptySet())
                showLeftPicker = false
            },
            onDismiss = { showLeftPicker = false }
        )
    }

    if (showRightPicker) {
        FolderPickerDialog(
            onFolderSelected = { uri ->
                rightPane = BrowserPaneState(root = uri, current = uri, selected = emptySet())
                showRightPicker = false
            },
            onDismiss = { showRightPicker = false }
        )
    }
}

private fun missingRuntimePermissions(context: Context): Array<String> {
    val wanted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        listOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO,
        )
    } else {
        listOf(Manifest.permission.READ_EXTERNAL_STORAGE) +
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE) else emptyList()
    }
    return wanted
        .filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        .toTypedArray()
}

@Suppress("DEPRECATION")
private fun deviceStorageRoots(context: Context): List<StorageRoot> {
    val volumes = context.getSystemService(StorageManager::class.java)?.storageVolumes.orEmpty()
    val roots = volumes.mapNotNull { volume ->
        val directory = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            volume.directory
        } else if (volume.isPrimary) {
            Environment.getExternalStorageDirectory()
        } else {
            null
        }
        directory?.takeIf { it.canRead() }?.let { StorageRoot(volume.getDescription(context) ?: it.name, it) }
    }
    val fallback = Environment.getExternalStorageDirectory()?.takeIf { it.canRead() }
        ?.let { listOf(StorageRoot("Internal storage", it)) }
        .orEmpty()
    val systemRoot = File("/").takeIf { it.canRead() }?.let { listOf(StorageRoot("System root (/)", it)) }.orEmpty()
    return (roots.ifEmpty { fallback } + systemRoot).distinctBy { it.directory.absolutePath }
}

private fun openAllFilesAccessSettings(context: Context) {
    val appSpecific = Intent(
        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        Uri.fromParts("package", context.packageName, null),
    )
    runCatching { context.startActivity(appSpecific) }
        .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } }
}

private fun openFileWithDefaultApp(activity: ComponentActivity, uri: Uri) {
    activity.startActivity(
        Intent.createChooser(
            Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            null,
        ),
    )
}

private fun executeTransfer(activity: ComponentActivity, request: TransferRequest) {
    if (request.wholeDirectory) {
        val sourceDoc = getDocumentFile(activity, request.sourceDir) ?: return
        val targetDoc = getDocumentFile(activity, request.targetDir) ?: return
        when (request.mode) {
            TransferMode.COPY -> copyTree(activity.contentResolver, sourceDoc, targetDoc)
            TransferMode.MOVE -> moveTree(activity.contentResolver, sourceDoc, targetDoc)
        }
        return
    }

    val targetDoc = getDocumentFile(activity, request.targetDir) ?: return
    request.selected.forEach { uri ->
        val sourceDoc = getDocumentFile(activity, uri) ?: return@forEach
        when (request.mode) {
            TransferMode.COPY -> transferDocument(activity.contentResolver, sourceDoc, targetDoc, true)
            TransferMode.MOVE -> transferDocument(activity.contentResolver, sourceDoc, targetDoc, false)
        }
    }
}

private fun deleteItems(activity: ComponentActivity, uris: Set<Uri>) {
    uris.forEach { uri ->
        getDocumentFile(activity, uri)?.delete()
    }
}

private fun copyTree(resolver: ContentResolver, source: DocumentFile, target: DocumentFile): Boolean {
    val targetName = nextAvailableName(target, source.name ?: "folder")
    val destination = target.createDirectory(targetName) ?: return false
    var ok = true
    source.listFiles().forEach { child ->
        if (child.uri == destination.uri) return@forEach
        val copied = if (child.isDirectory) {
            copyTree(resolver, child, destination)
        } else {
            copyFileContents(resolver, child, destination, nextAvailableName(destination, child.name ?: "file"))
        }
        if (!copied) {
            ok = false
        }
    }
    return ok
}

private fun moveTree(resolver: ContentResolver, source: DocumentFile, target: DocumentFile): Boolean {
    if (!copyTree(resolver, source, target)) return false
    return source.delete()
}

private fun copyFileContents(
    resolver: ContentResolver,
    source: DocumentFile,
    targetDir: DocumentFile,
    name: String,
): Boolean {
    // Cloud documents (Google Docs, Sheets, …) hold no bytes; they must be exported to a real type.
    val exportType = if (isVirtualDocument(resolver, source.uri)) {
        exportMimeType(resolver, source.uri) ?: return false
    } else {
        null
    }
    val fileName = if (exportType != null) nextAvailableName(targetDir, withExportExtension(name, exportType)) else name
    val destination = targetDir.createFile(exportType ?: source.type ?: "application/octet-stream", fileName)
        ?: return false
    val copied = runCatching {
        openDocumentStream(resolver, source.uri, exportType)?.use { input ->
            resolver.openOutputStream(destination.uri)?.use { output ->
                input.copyTo(output)
                output.flush()
                true
            }
        }
    }.getOrNull() == true
    if (!copied) {
        destination.delete()
    }
    return copied
}

private fun isVirtualDocument(resolver: ContentResolver, uri: Uri): Boolean {
    if (uri.scheme != ContentResolver.SCHEME_CONTENT) return false
    return runCatching {
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use { cursor ->
            cursor.moveToFirst() &&
                (cursor.getInt(0) and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT) != 0
        }
    }.getOrNull() == true
}

private fun exportMimeType(resolver: ContentResolver, uri: Uri): String? {
    val available = runCatching { resolver.getStreamTypes(uri, "*/*") }.getOrNull()?.filterNotNull().orEmpty()
    if (available.isEmpty()) return null
    val preferred = listOf("application/pdf", "image/png", "image/jpeg", "text/plain")
    return preferred.firstOrNull { it in available } ?: available.first()
}

private fun withExportExtension(name: String, mimeType: String): String {
    val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: return name
    return if (name.endsWith(".$extension", ignoreCase = true)) name else "$name.$extension"
}

private fun openDocumentStream(resolver: ContentResolver, uri: Uri, exportType: String? = null): InputStream? {
    val mimeType = exportType ?: if (isVirtualDocument(resolver, uri)) exportMimeType(resolver, uri) else null
    return if (mimeType == null) {
        resolver.openInputStream(uri)
    } else {
        resolver.openTypedAssetFileDescriptor(uri, mimeType, null)?.createInputStream()
    }
}

private fun transferDocument(
    resolver: ContentResolver,
    source: DocumentFile,
    targetDir: DocumentFile,
    copyMode: Boolean,
): Boolean {
    if (source.isDirectory) {
        val targetName = nextAvailableName(targetDir, source.name ?: "folder")
        val destination = targetDir.createDirectory(targetName) ?: return false
        var ok = true
        source.listFiles().forEach { child ->
            if (child.uri == destination.uri) return@forEach
            if (!transferDocument(resolver, child, destination, copyMode)) {
                ok = false
            }
        }
        if (!copyMode && ok) {
            source.delete()
        }
        return ok
    }

    val targetName = nextAvailableName(targetDir, source.name ?: "file")
    if (!copyFileContents(resolver, source, targetDir, targetName)) {
        return false
    }
    if (!copyMode) {
        source.delete()
    }
    return true
}

private fun nextAvailableName(targetDir: DocumentFile, preferredName: String): String {
    if (targetDir.findFile(preferredName) == null) {
        return preferredName
    }
    val dotIndex = preferredName.lastIndexOf('.')
    val base = if (dotIndex > 0) preferredName.substring(0, dotIndex) else preferredName
    val extension = if (dotIndex > 0) preferredName.substring(dotIndex) else ""
    var counter = 1
    while (true) {
        val candidate = "$base ($counter)$extension"
        if (targetDir.findFile(candidate) == null) {
            return candidate
        }
        counter += 1
    }
}

// fromTreeUri()/fromSingleUri() always report a null parent, so derive it from the document id.
private fun parentDirectoryUri(context: Context, uri: Uri): Uri? {
    if (uri.scheme == ContentResolver.SCHEME_FILE) {
        return uri.path?.let { File(it).parentFile }?.takeIf { it.canRead() }?.let(Uri::fromFile)
    }
    return runCatching {
        val treeRootId = DocumentsContract.getTreeDocumentId(uri)
        val documentId = if (DocumentsContract.isDocumentUri(context, uri)) {
            DocumentsContract.getDocumentId(uri)
        } else {
            treeRootId
        }
        val separator = documentId.lastIndexOf('/')
        if (documentId == treeRootId || separator <= 0) return@runCatching null
        val parentId = documentId.substring(0, separator)
        if (!parentId.startsWith(treeRootId)) return@runCatching null
        DocumentsContract.buildDocumentUriUsingTree(uri, parentId)
    }.getOrNull()
}

private fun resolveMimeType(resolver: ContentResolver, uri: Uri): String {
    return resolver.getType(uri) ?: when (uri.toString().substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg", "png", "gif", "bmp", "webp" -> "image/bitmap"
        "pdf" -> "application/pdf"
        "txt" -> "text/plain"
        else -> "application/octet-stream"
    }
}

private fun resolveDisplayPath(context: ComponentActivity, uri: Uri): String {
    val doc = getDocumentFile(context, uri)
    return doc?.name ?: uri.lastPathSegment ?: "unknown"
}

private fun getDocumentFile(context: Context, uri: Uri): DocumentFile? {
    return if (uri.scheme == "content") {
        if (uri.toString().contains("tree")) {
            DocumentFile.fromTreeUri(context, uri)
        } else {
            DocumentFile.fromSingleUri(context, uri)
        }
    } else if (uri.scheme == "file") {
        uri.path?.let { DocumentFile.fromFile(File(it)) }
    } else {
        null
    }
}

private fun isImageMimeType(value: String): Boolean = value.startsWith("image/")

private fun isHiddenPathSegment(name: String?): Boolean {
    return name?.startsWith(".") == true || name?.startsWith("$") == true
}

private fun isTrashRelatedPath(name: String?): Boolean {
    val lower = name?.lowercase() ?: return false
    return lower.contains("trash") || lower.contains("recycle") || lower.contains("deleted") || lower.contains("recentlydeleted")
}

private fun shouldDisplayDocument(doc: DocumentFile, showHiddenFiles: Boolean, showTrashFiles: Boolean): Boolean {
    val name = doc.name ?: return true
    val hidden = isHiddenPathSegment(name)
    val trash = isTrashRelatedPath(name)
    if (hidden && !showHiddenFiles) return false
    if (trash && !showTrashFiles) return false
    return true
}

private fun buildSelectionInfo(activity: ComponentActivity, uris: Set<Uri>): SelectionInfo {
    if (uris.isEmpty()) {
        return SelectionInfo(
            title = "No item selected",
            kind = "Idle",
            sizeLabel = "—",
            path = "Select an item to inspect it",
            details = "Ready",
        )
    }
    if (uris.size == 1) {
        val uri = uris.first()
        val doc = getDocumentFile(activity, uri)
        if (doc == null) {
            return SelectionInfo(
                title = uri.lastPathSegment ?: "Unknown",
                kind = "File",
                sizeLabel = "Unknown",
                path = uri.toString(),
                details = "Document reference",
            )
        }
        val isDir = doc.isDirectory
        val childCount = if (isDir) runCatching { doc.listFiles().size }.getOrNull() else null
        return SelectionInfo(
            title = doc.name ?: uri.lastPathSegment ?: "Unknown",
            kind = if (isDir) "Folder" else "File",
            sizeLabel = if (isDir) childCount?.let { "$it items" } ?: "Unknown" else formatBytes(doc.length()),
            path = doc.uri.toString(),
            details = if (isDir) "Directory" else (doc.type ?: "Document"),
        )
    }
    val names = uris.take(3).mapNotNull { uri -> getDocumentFile(activity, uri)?.name ?: uri.lastPathSegment }
    return SelectionInfo(
        title = "${uris.size} items selected",
        kind = "Multi-select",
        sizeLabel = "${uris.size} objects",
        path = names.joinToString(", "),
        details = if (names.isEmpty()) "Selection ready" else "Preview: ${names.joinToString(", ")}",
    )
}

private fun formatBytes(size: Long): String {
    if (size <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = size.toDouble()
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024.0
        index += 1
    }
    return String.format("%.1f %s", value, units[index])
}

@Composable
private fun MinimalTransferMenu(
    direction: TransferDirection,
    onReverse: () -> Unit,
    onChooseLeftRoot: () -> Unit,
    onChooseRightRoot: () -> Unit,
    sourceLabel: String,
    destinationLabel: String,
) {
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AssistChip(
                onClick = onChooseLeftRoot,
                label = { Text("Left root", fontSize = 10.sp) },
                leadingIcon = { Icon(Icons.Filled.FolderOpen, contentDescription = null, Modifier.size(14.dp)) },
                modifier = Modifier.weight(1f),
            )

            IconButton(
                onClick = onReverse,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = if (direction == TransferDirection.LEFT_TO_RIGHT) Icons.AutoMirrored.Filled.ArrowForward else Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Reverse transfer direction",
                    modifier = Modifier.size(20.dp),
                )
            }

            AssistChip(
                onClick = onChooseRightRoot,
                label = { Text("Right root", fontSize = 10.sp) },
                leadingIcon = { Icon(Icons.Filled.FolderOpen, contentDescription = null, Modifier.size(14.dp)) },
                modifier = Modifier.weight(1f),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("From:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontSize = 9.sp)
            Text(sourceLabel, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, fontSize = 10.sp, modifier = Modifier.weight(1f))
            Text("To:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontSize = 9.sp)
            Text(destinationLabel, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, fontSize = 10.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        }
    }
}

@Composable
private fun PreviewDetailPane(
    info: SelectionInfo,
    selectedFile: Uri?,
    isImage: Boolean,
    onOpen: () -> Unit,
    onView: () -> Unit,
) {
    Surface(
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(info.title, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${info.kind} · ${info.sizeLabel} · ${info.details}", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (selectedFile != null) {
                TextButton(onClick = onOpen, contentPadding = PaddingValues(4.dp)) { 
                    Text("Open", fontSize = 11.sp) 
                }
                if (isImage) {
                    TextButton(onClick = onView, contentPadding = PaddingValues(4.dp)) { 
                        Text("View", fontSize = 11.sp) 
                    }
                }
            }
        }
    }
}

@Composable
private fun CommandStrip(
    layoutMode: LayoutMode,
    pasteEnabled: Boolean,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onGallery: () -> Unit,
    onSelectMulti: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val stripWidth = when (layoutMode) {
        LayoutMode.PHONE -> 64.dp
        LayoutMode.TABLET_BALANCED -> 72.dp
        LayoutMode.TABLET_WIDE -> 80.dp
    }

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(stripWidth)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CommandButton(label = "Copy", icon = Icons.Filled.ContentCopy, onClick = onCopy)
        CommandButton(label = "Paste", icon = Icons.Filled.ContentPaste, onClick = onPaste, enabled = pasteEnabled)
        CommandButton(label = "Cut", icon = Icons.AutoMirrored.Filled.DriveFileMove, onClick = onMove)
        CommandButton(label = "Delete", icon = Icons.Filled.Delete, onClick = onDelete)
        CommandButton(label = "Gallery", icon = Icons.Filled.Image, onClick = onGallery)
        CommandButton(label = "Multi", icon = Icons.Filled.SelectAll, onClick = onSelectMulti)
        CommandButton(label = "Settings", icon = Icons.Filled.Settings, onClick = onOpenSettings, showLabel = false)
    }
}

@Composable
private fun CommandButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    showLabel: Boolean = true,
    enabled: Boolean = true,
) {
    Column(
        modifier = Modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(6.dp)
            .size(width = 88.dp, height = 76.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val contentColor = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        Icon(icon, contentDescription = label, modifier = Modifier.size(24.dp), tint = contentColor)
        if (showLabel) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun SelectionThumbnail(
    activity: ComponentActivity,
    uri: Uri,
    offset: Offset,
    onOffsetChange: (Offset) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) { loadBitmap(activity, uri, 160, 160) }
    }
    var currentOffset by remember(offset) { mutableStateOf(offset) }

    Box(
        modifier = modifier
            .offset { IntOffset(currentOffset.x.roundToInt(), currentOffset.y.roundToInt()) }
            .size(96.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(Unit) {
                detectDragGestures { _, dragAmount ->
                    currentOffset += dragAmount
                    onOffsetChange(currentOffset)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!,
                contentDescription = "Selected image thumbnail",
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(Icons.Filled.Image, contentDescription = null)
        }

        IconButton(
            onClick = onClose,
            modifier = Modifier.align(Alignment.TopEnd),
        ) {
            Icon(Icons.Filled.Close, contentDescription = "Close preview")
        }
    }
}

@Composable
private fun LayoutSettingsDialog(
    selectedMode: LayoutMode,
    showHiddenFiles: Boolean,
    showTrashFiles: Boolean,
    onSelect: (LayoutMode) -> Unit,
    onToggleHiddenFiles: (Boolean) -> Unit,
    onToggleTrashFiles: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Layout settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LayoutMode.entries.forEach { mode ->
                    val label = when (mode) {
                        LayoutMode.PHONE -> "Phone layout"
                        LayoutMode.TABLET_BALANCED -> "Tablet balanced"
                        LayoutMode.TABLET_WIDE -> "Tablet wide"
                    }
                    Button(
                        onClick = { onSelect(mode) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (mode == selectedMode) "$label (selected)" else label)
                    }
                }

                Text("File display", style = MaterialTheme.typography.labelLarge)
                Button(
                    onClick = { onToggleHiddenFiles(!showHiddenFiles) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (showHiddenFiles) "Hide hidden files" else "Show hidden files")
                }
                Button(
                    onClick = { onToggleTrashFiles(!showTrashFiles) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (showTrashFiles) "Hide trash-related files" else "Show trash-related files")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun ConfirmDeleteDialog(
    count: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete") },
        text = { Text(if (count == 1) "Delete this item permanently?" else "Delete $count items permanently?") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun FolderPickerDialog(
    onFolderSelected: (Uri) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current as ComponentActivity
    var rootUri by remember { mutableStateOf<Uri?>(null) }
    var currentUri by remember { mutableStateOf<Uri?>(null) }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }

    fun takeUriPermissionSafely(uri: Uri) {
        if (uri.scheme != "content") return
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            context.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
        }
    }

    val hasAllFilesAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        true
    }

    val pickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            takeUriPermissionSafely(it)
            onFolderSelected(it)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Default Folder") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (rootUri == null) {
                    Text("Choose a starting location.")
                    Spacer(modifier = Modifier.size(12.dp))
                    Button(onClick = { pickerLauncher.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Select Folder (via SAF)")
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !hasAllFilesAccess) {
                        Spacer(modifier = Modifier.size(8.dp))
                        Button(
                            onClick = { openAllFilesAccessSettings(context) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Grant All Files Access")
                        }
                    }
                    if (hasAllFilesAccess) {
                        val volumes = remember { deviceStorageRoots(context) }
                        volumes.forEach { root ->
                            Spacer(modifier = Modifier.size(8.dp))
                            Button(
                                onClick = {
                                    val uri = Uri.fromFile(root.directory)
                                    rootUri = uri
                                    currentUri = uri
                                    selectedUri = uri
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(root.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                } else {
                    val currentDoc = getDocumentFile(context, currentUri!!)
                    val folders = currentDoc?.listFiles()?.filter { it.isDirectory } ?: emptyList()

                    Text(
                        text = "Browsing: ${resolveDisplayPath(context, currentUri!!)}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.size(8.dp))

                    LazyColumn(modifier = Modifier.height(300.dp)) {
                        if (currentUri != rootUri) {
                            item {
                                TextButton(
                                    onClick = { currentUri = currentUri?.let { parentDirectoryUri(context, it) } ?: rootUri },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(".. [Up to parent]")
                                }
                            }
                        }
                        items(folders) { folder ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { currentUri = folder.uri }
                                    .padding(vertical = 4.dp)
                            ) {
                                Checkbox(
                                    checked = selectedUri == folder.uri,
                                    onCheckedChange = { if (it) selectedUri = folder.uri }
                                )
                                Text(
                                    text = folder.name ?: "Folder",
                                    modifier = Modifier.padding(start = 8.dp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        if (folders.isEmpty()) {
                            item {
                                Text(
                                    "No subfolders here",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = selectedUri != null,
                onClick = { selectedUri?.let { onFolderSelected(it) } }
            ) {
                Text("Select")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun ConfirmTransferDialog(
    request: TransferRequest,
    onDismiss: () -> Unit,
    onConfirm: (TransferRequest) -> Unit,
) {
    val context = LocalContext.current as? ComponentActivity ?: return
    var moveMode by remember(request) { mutableStateOf(request.mode == TransferMode.MOVE) }
    val sourceLabel = resolveDisplayPath(context, request.sourceDir)
    val targetLabel = resolveDisplayPath(context, request.targetDir)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Confirm transfer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (request.wholeDirectory) {
                        "Copy contents of ${sourceLabel} to ${targetLabel}?"
                    } else {
                        "Transfer selected item(s) from ${sourceLabel} to ${targetLabel}?"
                    },
                )
                Text("Copy is the default. Move mode deletes original files after transfer.")
                Text("Cloud documents are exported to a local format first, so a move replaces the original with the exported copy.")
                Button(onClick = { moveMode = !moveMode }) {
                    Text(if (moveMode) "Move mode enabled" else "Enable move mode")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(request.copy(mode = if (moveMode) TransferMode.MOVE else TransferMode.COPY))
                },
            ) {
                Text("Confirm")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageViewerScreen(
    activity: ComponentActivity,
    startingUri: Uri,
    directoryUri: Uri?,
    onClose: () -> Unit,
) {
    var currentUri by remember(startingUri) { mutableStateOf(startingUri) }
    var galleryMode by remember { mutableStateOf(GalleryMode.SINGLE) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var showMenu by remember { mutableStateOf(false) }
    var imageActionUri by remember { mutableStateOf<Uri?>(null) }
    var listingRefresh by remember { mutableIntStateOf(0) }

    val images by produceState(initialValue = emptyList<DocumentFile>(), directoryUri, startingUri, listingRefresh) {
        value = withContext(Dispatchers.IO) {
            val dir = directoryUri?.let { getDocumentFile(activity, it) }
            dir?.listFiles()
                ?.filter { file -> file.isFile && isImageMimeType(resolveMimeType(activity.contentResolver, file.uri)) }
                ?.sortedBy { it.name ?: "" }
                ?: listOfNotNull(getDocumentFile(activity, startingUri))
        }
    }

    val currentDoc = images.firstOrNull { it.uri == currentUri }
    val currentBitmap by produceState<ImageBitmap?>(initialValue = null, currentUri) {
        value = withContext(Dispatchers.IO) { loadBitmap(activity, currentUri) }
    }

    LaunchedEffect(galleryMode, scale) {
        if (galleryMode == GalleryMode.SINGLE && scale <= 0.75f) {
            galleryMode = GalleryMode.THUMBNAILS
            scale = 1f
            offset = Offset.Zero
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(currentDoc?.name ?: currentUri.lastPathSegment ?: "Image viewer", maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (images.size > 1) {
                    IconButton(onClick = {
                        val index = images.indexOfFirst { it.uri == currentUri }
                        val nextIndex = if (index <= 0) images.lastIndex else index - 1
                        currentUri = images[nextIndex].uri
                        scale = 1f
                        offset = Offset.Zero
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Previous image")
                    }
                    IconButton(onClick = {
                        val index = images.indexOfFirst { it.uri == currentUri }
                        val nextIndex = if (index < 0 || index == images.lastIndex) 0 else index + 1
                        currentUri = images[nextIndex].uri
                        scale = 1f
                        offset = Offset.Zero
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next image")
                    }
                }
                IconButton(onClick = {
                    galleryMode = if (galleryMode == GalleryMode.SINGLE) GalleryMode.THUMBNAILS else GalleryMode.SINGLE
                    scale = 1f
                    offset = Offset.Zero
                }) {
                    Icon(Icons.Filled.Image, contentDescription = "Toggle gallery view")
                }
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close gallery") }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            val singleBitmap = currentBitmap
            if (galleryMode == GalleryMode.SINGLE && singleBitmap != null) {
                Image(
                    bitmap = singleBitmap,
                    contentDescription = "Zoomable image",
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(0.25f, 6f)
                                offset += pan
                            }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { _, dragAmount ->
                                if (scale > 1f) {
                                    offset += dragAmount
                                }
                            }
                        }
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y,
                        )
                        .combinedClickable(
                            onClick = { showMenu = !showMenu },
                            onLongClick = { imageActionUri = currentUri },
                        ),
                )
            }

            if (galleryMode == GalleryMode.THUMBNAILS) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(images) { file ->
                        val thumb by produceState<ImageBitmap?>(initialValue = null, file.uri) {
                            value = withContext(Dispatchers.IO) { loadBitmap(activity, file.uri, 120, 120) }
                        }
                        val thumbnail = thumb
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clickable {
                                    currentUri = file.uri
                                    galleryMode = GalleryMode.SINGLE
                                    scale = 1f
                                    offset = Offset.Zero
                                },
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(110.dp)
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (thumbnail != null) {
                                    Image(bitmap = thumbnail, contentDescription = file.name ?: "Thumbnail", modifier = Modifier.fillMaxSize())
                                } else {
                                    Icon(Icons.Filled.Image, contentDescription = null)
                                }
                            }
                            Text(
                                text = file.name ?: "Image",
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }

            if (showMenu) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = onClose) { Text("Back to file browser") }
                    Button(onClick = { galleryMode = GalleryMode.THUMBNAILS; showMenu = false }) { Text("Show thumbnails") }
                }
            }

            if (imageActionUri != null) {
                AlertDialog(
                    onDismissRequest = { imageActionUri = null },
                    title = { Text("Image actions") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                openFileWithDefaultApp(activity, imageActionUri!!)
                                imageActionUri = null
                            }) { Text("Open in external app") }
                            Button(onClick = {
                                activity.startActivity(
                                    Intent.createChooser(
                                        Intent(Intent.ACTION_EDIT, imageActionUri!!).apply {
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        },
                                        "Edit image",
                                    ),
                                )
                                imageActionUri = null
                            }) { Text("Edit in external editor") }
                            Button(onClick = {
                                val target = imageActionUri ?: return@Button
                                getDocumentFile(activity, target)?.delete()
                                imageActionUri = null
                                val remaining = images.filterNot { it.uri == target }
                                if (remaining.isEmpty()) {
                                    onClose()
                                } else {
                                    if (currentUri == target) {
                                        val index = images.indexOfFirst { it.uri == target }
                                        currentUri = remaining[index.coerceIn(0, remaining.lastIndex)].uri
                                    }
                                    listingRefresh += 1
                                }
                            }) { Text("Delete") }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { imageActionUri = null }) { Text("Close") }
                    },
                )
            }
        }
    }
}

private fun loadBitmap(activity: ComponentActivity, uri: Uri, width: Int = 0, height: Int = 0): ImageBitmap? {
    val resolver = activity.contentResolver
    val exportType = if (isVirtualDocument(resolver, uri)) exportMimeType(resolver, uri) else null
    return openDocumentStream(resolver, uri, exportType)?.use { input ->
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(input, null, bounds)
        val targetWidth = if (width > 0) width else bounds.outWidth
        val targetHeight = if (height > 0) height else bounds.outHeight
        val sample = computeInSampleSize(bounds.outWidth, bounds.outHeight, targetWidth, targetHeight)

        openDocumentStream(resolver, uri, exportType)?.use { stream ->
            val opts = BitmapFactory.Options().apply {
                inJustDecodeBounds = false
                inSampleSize = sample
            }
            BitmapFactory.decodeStream(stream, null, opts)?.asImageBitmap()
        }
    }
}

private fun computeInSampleSize(srcWidth: Int, srcHeight: Int, targetWidth: Int, targetHeight: Int): Int {
    var inSampleSize = 1
    if (srcHeight > targetHeight || srcWidth > targetWidth) {
        val halfHeight = srcHeight / 2
        val halfWidth = srcWidth / 2
        while ((halfHeight / inSampleSize) >= targetHeight && (halfWidth / inSampleSize) >= targetWidth) {
            inSampleSize *= 2
        }
    }
    return inSampleSize
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun DirectoryPane(
    title: String,
    state: BrowserPaneState,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    onActivate: () -> Unit,
    onChooseRoot: () -> Unit,
    onNavigate: (Uri) -> Unit,
    onOpenFile: (Uri) -> Unit,
    onMoveUp: () -> Unit,
    onSelectionChange: (Set<Uri>) -> Unit,
    onImageLongPress: (Uri) -> Unit,
    showHiddenFiles: Boolean,
    showTrashFiles: Boolean,
) {
    val context = LocalContext.current
    val currentUri = state.current ?: state.root
    val listing by produceState<DirectoryListing?>(initialValue = null, currentUri, state.refreshKey, showHiddenFiles, showTrashFiles) {
        value = currentUri?.let { uri ->
            withContext(Dispatchers.IO) {
                val dir = getDocumentFile(context, uri)
                DirectoryListing(
                    name = dir?.name,
                    files = (dir?.listFiles()?.filter { file -> shouldDisplayDocument(file, showHiddenFiles, showTrashFiles) }
                        ?.sortedWith(
                            compareByDescending<DocumentFile> { it.isDirectory }.thenBy { it.name ?: "" },
                        ) ?: emptyList()),
                )
            }
        }
    }
    val files = listing?.files ?: emptyList()
    val isLoading = currentUri != null && listing == null

    Column(modifier = modifier.clickable(onClick = onActivate).fillMaxHeight()) {
        FlowRow(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            AssistChip(
                onClick = onChooseRoot,
                label = { Text(if (state.root == null) "Choose root" else "Change root") },
                leadingIcon = { Icon(Icons.Filled.FolderOpen, contentDescription = null, Modifier.size(18.dp)) },
            )
            if (state.root != null && currentUri != null && currentUri != state.root) {
                AssistChip(onClick = onMoveUp, label = { Text("Up") })
            }
        }

        Text(
            text = listing?.name ?: "No folder selected",
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium,
        )

        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Loading…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (files.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "This folder is empty",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(files, key = { it.uri }) { file ->
                    val selected = file.uri in state.selected
                    val icon = if (file.isDirectory) Icons.Filled.FolderOpen else Icons.AutoMirrored.Filled.InsertDriveFile
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                            .combinedClickable(
                                onClick = {
                                    onActivate()
                                    onSelectionChange(if (selected) state.selected - file.uri else state.selected + file.uri)
                                },
                                onDoubleClick = {
                                    onActivate()
                                    if (file.isDirectory) {
                                        onNavigate(file.uri)
                                    } else {
                                        onOpenFile(file.uri)
                                    }
                                },
                                onLongClick = {
                                    onActivate()
                                    if (!file.isDirectory) {
                                        onSelectionChange(setOf(file.uri))
                                        if (resolveMimeType((context as? ComponentActivity)?.contentResolver ?: return@combinedClickable, file.uri).startsWith("image/")) {
                                            onImageLongPress(file.uri)
                                        }
                                    }
                                },
                            )
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = icon,
                                contentDescription = if (file.isDirectory) "Folder" else "File",
                                modifier = Modifier.size(22.dp),
                                tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = file.name ?: "Unnamed",
                                modifier = Modifier.padding(start = 10.dp),
                                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
