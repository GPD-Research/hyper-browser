package org.gpdresearch.hyperbrowser

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.InputStream
import java.io.OutputStream

private enum class Pane { LEFT, RIGHT }
private enum class TransferMode { COPY, MOVE }
private enum class GalleryMode { SINGLE, THUMBNAILS }
private enum class AppTheme { LIGHT, INVERTED, MATRIX }
private enum class LayoutMode {
    PHONE,
    TABLET_BALANCED,
    TABLET_WIDE,
}

private data class BrowserPaneState(
    val root: Uri? = null,
    val selected: Set<Uri> = emptySet(),
    val expanded: Set<Uri> = emptySet(),
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
    val prefs = remember { activity.getSharedPreferences("hyper_browser_prefs", Context.MODE_PRIVATE) }

    fun loadInitialState(paneKey: String): BrowserPaneState {
        val rootStr = prefs.getString("${paneKey}_root", null)
        val rootUri = rootStr?.let { Uri.parse(it) }
        return BrowserPaneState(root = rootUri)
    }

    var leftPane by remember { mutableStateOf(loadInitialState("left")) }
    var rightPane by remember { mutableStateOf(loadInitialState("right")) }
    var activePane by remember { mutableStateOf(Pane.LEFT) }
    var layoutMode by remember { mutableStateOf(LayoutMode.PHONE) }
    var pendingRequest by remember { mutableStateOf<TransferRequest?>(null) }
    var galleryUri by remember { mutableStateOf<Uri?>(null) }
    var showLayoutSettings by remember { mutableStateOf(false) }
    var showLeftPicker by remember { mutableStateOf(false) }
    var showRightPicker by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var customExtensions by remember {
        val extStr = prefs.getString("custom_extensions", "")
        mutableStateOf(extStr?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet())
    }
    var appTheme by remember { 
        val themeName = prefs.getString("app_theme", AppTheme.LIGHT.name)
        mutableStateOf(AppTheme.valueOf(themeName ?: AppTheme.LIGHT.name))
    }

    val sourcePane = activePane
    val destinationPane = if (activePane == Pane.LEFT) Pane.RIGHT else Pane.LEFT
    val sourceState = if (sourcePane == Pane.LEFT) leftPane else rightPane
    val destinationState = if (destinationPane == Pane.LEFT) leftPane else rightPane
    val selected = sourceState.selected
    val selectedFile = selected.singleOrNull()
    val selectedMimeType = selectedFile?.let { resolveMimeType(activity.contentResolver, it) } ?: ""
    val isImageSelected = selectedMimeType.startsWith("image/")
    val selectionInfo = remember(selected, activity) { buildSelectionInfo(activity, selected) }

    fun enqueueTransfer(mode: TransferMode, selectedItems: Set<Uri> = sourceState.selected) {
        val sourceDir = sourceState.root ?: return
        val targetDir = destinationState.root ?: return
        pendingRequest = TransferRequest(
            sourceDir = sourceDir,
            targetDir = targetDir,
            selected = selectedItems,
            wholeDirectory = selectedItems.isEmpty(),
            mode = mode,
        )
    }

    fun executeTransferNow(mode: TransferMode, selectedItems: Set<Uri> = sourceState.selected) {
        val sourceDir = sourceState.root ?: return
        val targetDir = destinationState.root ?: return
        val request = TransferRequest(
            sourceDir = sourceDir,
            targetDir = targetDir,
            selected = selectedItems,
            wholeDirectory = selectedItems.isEmpty(),
            mode = mode,
        )
        executeTransfer(activity, request)
        if (sourcePane == Pane.LEFT) {
            leftPane = leftPane.copy(selected = emptySet())
        } else {
            rightPane = rightPane.copy(selected = emptySet())
        }
    }

    fun isImageFile(uri: Uri): Boolean {
        val mime = resolveMimeType(activity.contentResolver, uri)
        if (mime.startsWith("image/")) return true
        val ext = uri.toString().substringAfterLast('.', "").lowercase()
        return ext in customExtensions
    }

    fun handleFileClick(uri: Uri, isDirectory: Boolean) {
        if (isDirectory) {
            val paneState = if (activePane == Pane.LEFT) leftPane else rightPane
            val newExpanded = if (uri in paneState.expanded) paneState.expanded - uri else paneState.expanded + uri
            if (activePane == Pane.LEFT) {
                leftPane = leftPane.copy(expanded = newExpanded)
            } else {
                rightPane = rightPane.copy(expanded = newExpanded)
            }
        } else {
            if (!isImageFile(uri)) {
                openFileWithDefaultApp(activity, uri)
            }
        }
    }

    fun handleSelection(uri: Uri, targetPane: Pane) {
        activePane = targetPane
        val paneState = if (targetPane == Pane.LEFT) leftPane else rightPane
        val isSelected = uri in paneState.selected
        var newSelection = paneState.selected

        if (isSelected) {
            newSelection = newSelection - uri
        } else {
            newSelection = newSelection + uri
        }

        if (targetPane == Pane.LEFT) {
            leftPane = leftPane.copy(selected = newSelection)
        } else {
            rightPane = rightPane.copy(selected = newSelection)
        }
    }

    fun setPaneRoot(uri: Uri, pane: Pane) {
        if (pane == Pane.LEFT) {
            leftPane = leftPane.copy(root = uri, expanded = emptySet(), selected = emptySet())
        } else {
            rightPane = rightPane.copy(root = uri, expanded = emptySet(), selected = emptySet())
        }
    }

    fun savePaneDefaults() {
        prefs.edit().apply {
            putString("left_root", leftPane.root?.toString())
            putString("right_root", rightPane.root?.toString())
            apply()
        }
    }

    fun updateTheme(newTheme: AppTheme) {
        appTheme = newTheme
        prefs.edit().putString("app_theme", newTheme.name).apply()
    }

    fun updateCustomExtensions(extensions: Set<String>) {
        customExtensions = extensions
        prefs.edit().putString("custom_extensions", extensions.joinToString(",")).apply()
    }

    fun createFolder(name: String) {
        val targetUri = sourceState.selected.firstOrNull { 
            getDocumentFile(activity, it)?.isDirectory == true 
        } ?: sourceState.root ?: return
        
        val parentDoc = getDocumentFile(activity, targetUri)
        if (parentDoc != null && parentDoc.isDirectory) {
            val newDir = parentDoc.createDirectory(name)
            if (newDir != null) {
                if (activePane == Pane.LEFT) {
                    leftPane = leftPane.copy(expanded = leftPane.expanded + targetUri)
                } else {
                    rightPane = rightPane.copy(expanded = rightPane.expanded + targetUri)
                }
            }
        }
        showCreateFolderDialog = false
    }

    val matrixGreen = Color(0xFF00FF41)
    val colorScheme = when (appTheme) {
        AppTheme.LIGHT -> lightColorScheme()
        AppTheme.INVERTED -> darkColorScheme(
            background = Color.Black,
            surface = Color.Black,
            onBackground = Color.White,
            onSurface = Color.White,
            primary = Color.White,
            onPrimary = Color.Black
        )
        AppTheme.MATRIX -> darkColorScheme(
            background = Color.Black,
            surface = Color.Black,
            onBackground = matrixGreen,
            onSurface = matrixGreen,
            primary = matrixGreen,
            onPrimary = Color.Black,
            secondary = matrixGreen,
            onSecondary = Color.Black
        )
    }

    MaterialTheme(colorScheme = colorScheme) {
        Scaffold { contentPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            ) {
                if (galleryUri != null) {
                    ImageViewerScreen(
                        activity = activity,
                        startingUri = galleryUri!!,
                        onClose = { galleryUri = null },
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CommandStrip(
                            layoutMode = layoutMode,
                            onCopy = {
                                val items = sourceState.selected.ifEmpty { setOfNotNull(sourceState.root) }
                                if (items.isNotEmpty()) {
                                    executeTransferNow(TransferMode.COPY, items)
                                }
                            },
                            onPaste = {},
                            onMove = {
                                val items = sourceState.selected.ifEmpty { setOfNotNull(sourceState.root) }
                                if (items.isNotEmpty()) {
                                    enqueueTransfer(TransferMode.MOVE, items)
                                }
                            },
                            onDelete = {
                                val items = sourceState.selected.ifEmpty { setOfNotNull(sourceState.root) }
                                if (items.isNotEmpty()) {
                                    enqueueTransfer(TransferMode.MOVE, items) // Using MOVE for delete flow currently
                                }
                            },
                            onCreateFolder = { showCreateFolderDialog = true },
                            onOpenGallery = {
                                val selectedDir = sourceState.selected.firstOrNull { 
                                    getDocumentFile(activity, it)?.isDirectory == true 
                                } ?: sourceState.root
                                galleryUri = selectedDir
                            },
                            onSelectMulti = {
                                if (activePane == Pane.LEFT) {
                                    leftPane = leftPane.copy(selected = emptySet())
                                } else {
                                    rightPane = rightPane.copy(selected = emptySet())
                                }
                            },
                            onOpenSettings = { showLayoutSettings = true },
                        )

                        DirectoryPane(
                            title = "Left",
                            state = leftPane,
                            isActive = activePane == Pane.LEFT,
                            modifier = Modifier.weight(if (layoutMode == LayoutMode.TABLET_WIDE) 1.6f else if (layoutMode == LayoutMode.TABLET_BALANCED) 1.2f else 1f),
                            onActivate = { activePane = Pane.LEFT },
                            onChooseRoot = { showLeftPicker = true },
                            onToggleExpanded = { uri ->
                                leftPane = leftPane.copy(expanded = if (uri in leftPane.expanded) leftPane.expanded - uri else leftPane.expanded + uri)
                            },
                            onOpenFile = { uri, isDir -> handleFileClick(uri, isDir) },
                            onSelectionChange = { uri, _ -> handleSelection(uri, Pane.LEFT) },
                            isImageFile = { isImageFile(it) },
                            onOpenInGallery = { galleryUri = it },
                            onOpenWithChooser = { openFileWithChooser(activity, it) },
                            onSetAsRoot = { setPaneRoot(it, Pane.LEFT) }
                        )

                        DirectoryPane(
                            title = "Right",
                            state = rightPane,
                            isActive = activePane == Pane.RIGHT,
                            modifier = Modifier.weight(if (layoutMode == LayoutMode.TABLET_WIDE) 1.6f else if (layoutMode == LayoutMode.TABLET_BALANCED) 1.2f else 1f),
                            onActivate = { activePane = Pane.RIGHT },
                            onChooseRoot = { showRightPicker = true },
                            onToggleExpanded = { uri ->
                                rightPane = rightPane.copy(expanded = if (uri in rightPane.expanded) rightPane.expanded - uri else rightPane.expanded + uri)
                            },
                            onOpenFile = { uri, isDir -> handleFileClick(uri, isDir) },
                            onSelectionChange = { uri, _ -> handleSelection(uri, Pane.RIGHT) },
                            isImageFile = { isImageFile(it) },
                            onOpenInGallery = { galleryUri = it },
                            onOpenWithChooser = { openFileWithChooser(activity, it) },
                            onSetAsRoot = { setPaneRoot(it, Pane.RIGHT) }
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
                executeTransfer(activity, request)
                pendingRequest = null
                if (sourcePane == Pane.LEFT) {
                    leftPane = leftPane.copy(selected = emptySet())
                } else {
                    rightPane = rightPane.copy(selected = emptySet())
                }
            },
        )
    }

    if (showCreateFolderDialog) {
        CreateFolderDialog(
            onConfirm = { name -> createFolder(name) },
            onDismiss = { showCreateFolderDialog = false }
        )
    }

    if (showLayoutSettings) {
        LayoutSettingsDialog(
            selectedMode = layoutMode,
            onSelect = { mode ->
                layoutMode = mode
                showLayoutSettings = false
            },
            currentTheme = appTheme,
            onThemeSelect = { updateTheme(it) },
            customExtensions = customExtensions,
            onUpdateExtensions = { updateCustomExtensions(it) },
            onSaveDefaults = {
                savePaneDefaults()
                showLayoutSettings = false
            },
            onDismiss = { showLayoutSettings = false },
        )
    }

    if (showLeftPicker) {
        FolderPickerDialog(
            onFolderSelected = { uri ->
                leftPane = BrowserPaneState(root = uri, selected = emptySet(), expanded = emptySet())
                showLeftPicker = false
            },
            onDismiss = { showLeftPicker = false }
        )
    }

    if (showRightPicker) {
        FolderPickerDialog(
            onFolderSelected = { uri ->
                rightPane = BrowserPaneState(root = uri, selected = emptySet(), expanded = emptySet())
                showRightPicker = false
            },
            onDismiss = { showRightPicker = false }
        )
    }
}

private fun openFileWithDefaultApp(activity: ComponentActivity, uri: Uri) {
    val shareUri = if (uri.scheme == "file") {
        uri.path?.let { FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", File(it)) } ?: uri
    } else {
        uri
    }
    activity.startActivity(
        Intent.createChooser(
            Intent(Intent.ACTION_VIEW, shareUri).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (uri.scheme == "file") {
                    // For direct file access, some apps might need the MIME type explicitly
                    setDataAndType(shareUri, resolveMimeType(activity.contentResolver, uri))
                }
            },
            null,
        ),
    )
}

private fun openFileWithChooser(activity: ComponentActivity, uri: Uri) {
    val shareUri = if (uri.scheme == "file") {
        FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", File(uri.path!!))
    } else {
        uri
    }
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(shareUri, resolveMimeType(activity.contentResolver, uri))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    activity.startActivity(Intent.createChooser(intent, "Open with..."))
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

private fun copyTree(resolver: ContentResolver, source: DocumentFile, target: DocumentFile) {
    val targetName = nextAvailableName(target, source.name ?: "folder")
    val destination = target.findFile(targetName) ?: target.createDirectory(targetName) ?: return
    source.listFiles().forEach { child ->
        if (child.isDirectory) {
            copyTree(resolver, child, destination)
        } else {
            val fileTarget = destination.findFile(child.name ?: "file") ?: destination.createFile("application/octet-stream", child.name ?: "file") ?: return@forEach
            resolver.openInputStream(child.uri)?.use { input ->
                resolver.openOutputStream(fileTarget.uri)?.use { output -> copyStream(input, output) }
            }
        }
    }
}

private fun moveTree(resolver: ContentResolver, source: DocumentFile, target: DocumentFile) {
    copyTree(resolver, source, target)
    source.delete()
}

private fun transferDocument(
    resolver: ContentResolver,
    source: DocumentFile,
    targetDir: DocumentFile,
    copyMode: Boolean,
): Boolean {
    if (source.isDirectory) {
        val targetName = nextAvailableName(targetDir, source.name ?: "folder")
        val destination = targetDir.findFile(targetName) ?: targetDir.createDirectory(targetName) ?: return false
        var ok = true
        source.listFiles().forEach { child ->
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
    val destination = targetDir.findFile(targetName) ?: targetDir.createFile("application/octet-stream", targetName) ?: return false
    resolver.openInputStream(source.uri)?.use { input ->
        resolver.openOutputStream(destination.uri)?.use { output -> copyStream(input, output) }
    } ?: return false
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

private fun copyStream(input: InputStream, output: OutputStream) {
    val buffer = ByteArray(64 * 1024) // 64KB buffer for efficient large file transfers
    var bytesRead: Int
    while (input.read(buffer).also { bytesRead = it } != -1) {
        output.write(buffer, 0, bytesRead)
    }
    output.flush()
}

private fun resolveMimeType(resolver: ContentResolver, uri: Uri): String {
    val type = resolver.getType(uri)
    if (type != null) return type
    
    val ext = uri.toString().substringAfterLast('.', "").lowercase()
    return when (ext) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "bmp" -> "image/x-ms-bmp"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "pdf" -> "application/pdf"
        "txt" -> "text/plain"
        "cr2", "nef", "arw", "dng", "orf", "raf" -> "image/x-adobe-dng" // Generic RAW
        else -> "application/octet-stream"
    }
}

private fun resolveDisplayPath(context: Context, uri: Uri): String {
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

private fun getRecursiveUris(parent: DocumentFile): Set<Uri> {
    val uris = mutableSetOf<Uri>()
    parent.listFiles().forEach { file ->
        uris.add(file.uri)
        if (file.isDirectory) {
            uris.addAll(getRecursiveUris(file))
        }
    }
    return uris
}

private fun isImageMimeType(value: String): Boolean = value.startsWith("image/")

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
        return SelectionInfo(
            title = doc.name ?: uri.lastPathSegment ?: "Unknown",
            kind = if (isDir) "Folder" else "File",
            sizeLabel = if (isDir) "${doc.listFiles().size} items" else formatBytes(doc.length()),
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
private fun PreviewDetailPane(
    info: SelectionInfo,
    selectedFile: Uri?,
    isImage: Boolean,
    onOpen: () -> Unit,
    onView: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text("Preview", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(info.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${info.kind} · ${info.sizeLabel}", style = MaterialTheme.typography.bodyMedium)
        Text(info.details, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(info.path, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (selectedFile != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Button(onClick = onOpen) { Text("Open file") }
                Button(onClick = onView, enabled = isImage) { Text("View image") }
            }
        }
    }
}

@Composable
private fun CommandStrip(
    layoutMode: LayoutMode,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onCreateFolder: () -> Unit,
    onOpenGallery: () -> Unit,
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
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CommandButton(label = "Copy", icon = Icons.Filled.ContentCopy, onClick = onCopy)
        CommandButton(label = "Paste", icon = Icons.Filled.ContentPaste, onClick = onPaste)
        CommandButton(label = "Move", icon = Icons.AutoMirrored.Filled.DriveFileMove, onClick = onMove)
        CommandButton(label = "Delete", icon = Icons.Filled.Delete, onClick = onDelete)
        CommandButton(label = "New Folder", icon = Icons.Filled.CreateNewFolder, onClick = onCreateFolder)
        CommandButton(label = "Gallery", icon = Icons.Filled.PhotoLibrary, onClick = onOpenGallery)
        CommandButton(label = "Clear", icon = Icons.Filled.Deselect, onClick = {
            onSelectMulti() 
        })
        CommandButton(label = "Settings", icon = Icons.Filled.Settings, onClick = onOpenSettings)
    }
}

@Composable
private fun CommandButton(label: String, icon: ImageVector, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(4.dp)
            .size(width = 56.dp, height = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), maxLines = 1)
    }
}

@Composable
private fun LayoutSettingsDialog(
    selectedMode: LayoutMode,
    onSelect: (LayoutMode) -> Unit,
    currentTheme: AppTheme,
    onThemeSelect: (AppTheme) -> Unit,
    customExtensions: Set<String>,
    onUpdateExtensions: (Set<String>) -> Unit,
    onSaveDefaults: () -> Unit,
    onDismiss: () -> Unit,
) {
    var newExt by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Layout Mode", style = MaterialTheme.typography.labelLarge)
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
                Spacer(modifier = Modifier.height(16.dp))
                Text("App Theme", style = MaterialTheme.typography.labelLarge)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppTheme.entries.forEach { theme ->
                        Button(
                            onClick = { onThemeSelect(theme) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(4.dp)
                        ) {
                            val label = theme.name.lowercase().replaceFirstChar { it.uppercase() }
                            Text(if (theme == currentTheme) "$label*" else label, fontSize = 10.sp, maxLines = 1)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text("Custom Image Extensions", style = MaterialTheme.typography.labelLarge)
                Text("Current: ${customExtensions.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newExt,
                        onValueChange = { newExt = it.lowercase().trim() },
                        label = { Text("Add extension (e.g. cr2)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    IconButton(onClick = {
                        if (newExt.isNotBlank()) {
                            onUpdateExtensions(customExtensions + newExt)
                            newExt = ""
                        }
                    }) {
                        Icon(Icons.Filled.Add, "Add")
                    }
                }
                if (customExtensions.isNotEmpty()) {
                    TextButton(onClick = { onUpdateExtensions(emptySet()) }) {
                        Text("Clear All Custom Extensions")
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onSaveDefaults,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(12.dp)
                ) {
                    Text("Save Current Folders as Default")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
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

@Composable
private fun CreateFolderDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create New Folder") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Folder Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun FolderPickerDialog(
    onFolderSelected: (Uri) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current as ComponentActivity

    val hasAllFilesAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        true
    }

    val pickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(it, flags)
            onFolderSelected(it)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Storage Location") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("Choose where to start browsing.", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    "Note: Android limits access to absolute cloud/system roots. If a folder says 'Can't use', please select a subfolder (e.g. 'My Drive').",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                Spacer(modifier = Modifier.size(16.dp))
                
                Text("Standard Locations", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.size(8.dp))
                
                if (hasAllFilesAccess) {
                    Button(
                        onClick = {
                            val root = Environment.getExternalStorageDirectory()
                            onFolderSelected(Uri.fromFile(root))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.FolderOpen, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Internal Storage (Device Root)")
                    }
                } else {
                    Text("All Files Access is required for direct browsing.", style = MaterialTheme.typography.bodySmall)
                    Button(
                        onClick = {
                            @Suppress("InlinedApi")
                            val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                            context.startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Grant All Files Access")
                    }
                }

                Spacer(modifier = Modifier.size(16.dp))
                Text("External & Cloud", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.size(8.dp))
                
                OutlinedButton(
                    onClick = { pickerLauncher.launch(null) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.PhotoLibrary, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Cloud, SD Card, or USB")
                }
                Text(
                    "Use this to pick Google Drive folders or SD Card roots.",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun ImageViewerScreen(
    activity: ComponentActivity,
    startingUri: Uri,
    onClose: () -> Unit,
) {
    val currentFile = getDocumentFile(activity, startingUri)
    val isDir = currentFile?.isDirectory == true
    val parentDir = if (isDir) startingUri else (currentFile?.parentFile?.uri ?: startingUri)
    val directoryDoc = getDocumentFile(activity, parentDir)
    val images = directoryDoc?.listFiles()?.filter { file ->
        file.isFile && isImageMimeType(resolveMimeType(activity.contentResolver, file.uri))
    } ?: emptyList()

    var currentUri by remember(startingUri) { 
        mutableStateOf(if (isDir) (images.firstOrNull()?.uri ?: startingUri) else startingUri) 
    }
    var galleryMode by remember(startingUri) { 
        mutableStateOf(if (isDir) GalleryMode.THUMBNAILS else GalleryMode.SINGLE) 
    }
    var scale by remember(startingUri) { mutableFloatStateOf(1f) }
    var offset by remember(startingUri) { mutableStateOf(Offset.Zero) }
    var showMenu by remember(startingUri) { mutableStateOf(false) }
    var showActionMenu by remember(startingUri) { mutableStateOf(false) }

    // Gallery Zoom State
    var gridZoom by remember(startingUri) { mutableFloatStateOf(3f) } 
    val transformState = rememberTransformableState { zoomChange, _, _ ->
        gridZoom = (gridZoom / zoomChange).coerceIn(1f, 10f)
    }

    val currentDoc = images.firstOrNull { it.uri == currentUri }
    val displayMetrics = activity.resources.displayMetrics
    val screenWidth = displayMetrics.widthPixels
    val screenHeight = displayMetrics.heightPixels
    
    val currentBitmap = currentDoc?.let { 
        remember(it.uri, activity) { 
            // Load full image capped at screen resolution to prevent "Canvas: trying to draw too large bitmap" crash
            loadBitmap(activity, it.uri, screenWidth, screenHeight) 
        } 
    }

    LaunchedEffect(galleryMode, scale) {
        if (galleryMode == GalleryMode.SINGLE && scale <= 0.75f) {
            galleryMode = GalleryMode.THUMBNAILS
            scale = 1f
            offset = Offset.Zero
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(currentDoc?.name ?: "Image viewer", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    if (galleryMode == GalleryMode.SINGLE) {
                        scale = (scale / 1.2f).coerceAtLeast(1f)
                    } else {
                        gridZoom = (gridZoom + 1f).coerceAtMost(10f)
                    }
                }) {
                    Icon(Icons.Filled.Remove, contentDescription = "Zoom out")
                }
                IconButton(onClick = {
                    if (galleryMode == GalleryMode.SINGLE) {
                        scale = (scale * 1.2f).coerceAtMost(6f)
                    } else {
                        gridZoom = (gridZoom - 1f).coerceAtLeast(1f)
                    }
                }) {
                    Icon(Icons.Filled.Add, contentDescription = "Zoom in")
                }
                if (images.size > 1 && galleryMode == GalleryMode.SINGLE) {
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
                    Icon(if (galleryMode == GalleryMode.SINGLE) Icons.Filled.PhotoLibrary else Icons.Filled.Image, contentDescription = "Toggle gallery view")
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
            if (galleryMode == GalleryMode.SINGLE && currentBitmap != null) {
                Box {
                    Image(
                        bitmap = currentBitmap,
                        contentDescription = "Full image",
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    scale = (scale * zoom).coerceIn(1f, 6f)
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
                            .clickable(onClick = { showActionMenu = !showActionMenu }),
                        contentScale = ContentScale.Fit
                    )
                    
                    DropdownMenu(
                        expanded = showActionMenu,
                        onDismissRequest = { showActionMenu = false },
                        modifier = Modifier.align(Alignment.TopEnd)
                    ) {
                        DropdownMenuItem(
                            text = { Text("Open in Editor") },
                            onClick = { 
                                val mimeType = resolveMimeType(activity.contentResolver, currentUri)
                                val shareUri = if (currentUri.scheme == "file") {
                                    FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", File(currentUri.path!!))
                                } else {
                                    currentUri
                                }
                                // Use ACTION_SEND or ACTION_EDIT. Some apps prefer SEND for "sharing" to editor.
                                // But for editing in place, ACTION_EDIT is standard.
                                val editIntent = Intent(Intent.ACTION_EDIT).apply {
                                    setDataAndType(shareUri, mimeType)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                                }
                                try {
                                    activity.startActivity(Intent.createChooser(editIntent, "Edit Image"))
                                } catch (_: Exception) {
                                    // Fallback: try ACTION_VIEW with write permission
                                    val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(shareUri, mimeType)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                                    }
                                    try {
                                        activity.startActivity(Intent.createChooser(viewIntent, "Open with..."))
                                    } catch (_: Exception) {}
                                }
                                showActionMenu = false 
                            },
                            leadingIcon = { Icon(Icons.Filled.Edit, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Print / Share") },
                            onClick = { 
                                val mimeType = resolveMimeType(activity.contentResolver, currentUri)
                                val shareUri = if (currentUri.scheme == "file") {
                                    FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", File(currentUri.path!!))
                                } else {
                                    currentUri
                                }
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = mimeType
                                    putExtra(Intent.EXTRA_STREAM, shareUri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                activity.startActivity(Intent.createChooser(shareIntent, "Print or Share Image"))
                                showActionMenu = false 
                            },
                            leadingIcon = { Icon(Icons.Filled.PhotoLibrary, null) } // Use PhotoLibrary icon for share/import
                        )
                        DropdownMenuItem(
                            text = { Text("Close") },
                            onClick = { onClose() },
                            leadingIcon = { Icon(Icons.Filled.Close, null) }
                        )
                    }
                }
            }

            if (galleryMode == GalleryMode.THUMBNAILS) {
                val columns = gridZoom.toInt().coerceIn(1, 8)
                val highQuality = columns <= 2
                
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    modifier = Modifier
                        .fillMaxSize()
                        .transformable(state = transformState),
                    contentPadding = PaddingValues(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(images, key = { it.uri }) { file ->
                        val targetSize = if (highQuality) 512 else 160
                        val thumb = remember(file.uri, activity, targetSize) { loadBitmap(activity, file.uri, targetSize, targetSize) }
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
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (thumb != null) {
                                    Image(
                                        bitmap = thumb, 
                                        contentDescription = file.name ?: "Thumbnail", 
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Fit
                                    )
                                } else {
                                    Icon(Icons.Filled.Image, contentDescription = null, modifier = Modifier.size(32.dp))
                                }
                            }
                            if (columns <= 4) {
                                Text(
                                    text = file.name ?: "Image",
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
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
        }
    }
}

private fun loadBitmap(activity: ComponentActivity, uri: Uri, width: Int = 0, height: Int = 0): ImageBitmap? {
    val bounds = activity.contentResolver.openInputStream(uri)?.use { input ->
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(input, null, opts)
        opts
    } ?: return null

    // If width/height are 0, we still want to cap at screen size to prevent crashes with massive files
    val displayMetrics = activity.resources.displayMetrics
    val maxW = if (width > 0) width else displayMetrics.widthPixels
    val maxH = if (height > 0) height else displayMetrics.heightPixels

    val sample = computeInSampleSize(bounds.outWidth, bounds.outHeight, maxW, maxH)

    return activity.contentResolver.openInputStream(uri)?.use { stream ->
        val opts = BitmapFactory.Options().apply {
            inJustDecodeBounds = false
            inSampleSize = sample
            // Use RGB_565 (2 bytes/pixel) for smaller thumbnails to save 50% memory
            inPreferredConfig = if (maxW > 400) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
        }
        BitmapFactory.decodeStream(stream, null, opts)?.asImageBitmap()
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
    onToggleExpanded: (Uri) -> Unit,
    onOpenFile: (Uri, Boolean) -> Unit,
    onSelectionChange: (Uri, Boolean) -> Unit,
    isImageFile: (Uri) -> Boolean,
    onOpenInGallery: (Uri) -> Unit,
    onOpenWithChooser: (Uri) -> Unit,
    onSetAsRoot: (Uri) -> Unit
) {
    val context = LocalContext.current
    val rootUri = state.root

    Column(modifier = modifier
        .clickable(onClick = onActivate)
        .fillMaxHeight()) {
        if (rootUri == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Choose a root folder to begin",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.size(12.dp))
                    Text(
                        text = "Select Root Folder",
                        style = MaterialTheme.typography.labelLarge.copy(
                            color = MaterialTheme.colorScheme.primary,
                            textDecoration = TextDecoration.Underline
                        ),
                        modifier = Modifier.clickable { onChooseRoot() }
                    )
                }
            }
            return
        }
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
                label = { Text("Change root") },
                leadingIcon = { Icon(Icons.Filled.FolderOpen, contentDescription = null, Modifier.size(18.dp)) },
            )
        }

        Text(
            text = resolveDisplayPath(context, rootUri),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium,
        )

        LazyColumn(Modifier.fillMaxSize()) {
            item {
                val rootDoc = getDocumentFile(context, rootUri)
                if (rootDoc != null) {
                    TreeRoot(
                        context = context,
                        root = rootDoc,
                        state = state,
                        onToggleExpanded = onToggleExpanded,
                        onOpenFile = onOpenFile,
                        onSelectionChange = onSelectionChange,
                        isImageFile = isImageFile,
                        onOpenInGallery = onOpenInGallery,
                        onOpenWithChooser = onOpenWithChooser,
                        onSetAsRoot = onSetAsRoot
                    )
                }
            }
        }
    }
}

@Composable
private fun TreeRoot(
    context: Context,
    root: DocumentFile,
    state: BrowserPaneState,
    onToggleExpanded: (Uri) -> Unit,
    onOpenFile: (Uri, Boolean) -> Unit,
    onSelectionChange: (Uri, Boolean) -> Unit,
    isImageFile: (Uri) -> Boolean,
    onOpenInGallery: (Uri) -> Unit,
    onOpenWithChooser: (Uri) -> Unit,
    onSetAsRoot: (Uri) -> Unit
) {
    Column {
        RenderTreeNodes(
            context = context,
            parent = root,
            expanded = state.expanded,
            selected = state.selected,
            parentSelected = root.uri in state.selected,
            depth = 0,
            onToggleExpanded = onToggleExpanded,
            onOpenFile = onOpenFile,
            onSelectionChange = onSelectionChange,
            isImageFile = isImageFile,
            onOpenInGallery = onOpenInGallery,
            onOpenWithChooser = onOpenWithChooser,
            onSetAsRoot = onSetAsRoot
        )
    }
}

@Composable
private fun RenderTreeNodes(
    context: Context,
    parent: DocumentFile,
    expanded: Set<Uri>,
    selected: Set<Uri>,
    parentSelected: Boolean,
    depth: Int,
    onToggleExpanded: (Uri) -> Unit,
    onOpenFile: (Uri, Boolean) -> Unit,
    onSelectionChange: (Uri, Boolean) -> Unit,
    isImageFile: (Uri) -> Boolean,
    onOpenInGallery: (Uri) -> Unit,
    onOpenWithChooser: (Uri) -> Unit,
    onSetAsRoot: (Uri) -> Unit
) {
    // Load files asynchronously to prevent UI hang on cloud storage
    val filesState = produceState<List<DocumentFile>?>(initialValue = null, parent.uri) {
        value = withContext(Dispatchers.IO) {
            try {
                parent.listFiles().sortedWith(
                    compareByDescending<DocumentFile> { it.isDirectory }.thenBy { it.name ?: "" }
                )
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    val files = filesState.value

    if (files == null) {
        Row(modifier = Modifier.padding(start = (depth * 12 + 16).dp).padding(vertical = 4.dp)) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Loading...", style = MaterialTheme.typography.labelSmall)
        }
    } else {
        files.forEach { file ->
            val isExplicitlySelected = file.uri in selected
            val isEffectivelySelected = parentSelected || isExplicitlySelected
            val isExpanded = file.uri in expanded

            FileTreeRow(
                file = file,
                depth = depth,
                isExpanded = isExpanded,
                isSelected = isEffectivelySelected,
                onToggleExpanded = { onToggleExpanded(file.uri) },
                onOpenFile = { onOpenFile(file.uri, file.isDirectory) },
                onSelect = {
                    onSelectionChange(file.uri, file.isDirectory)
                },
                isImageFile = isImageFile,
                onOpenInGallery = onOpenInGallery,
                onOpenWithChooser = onOpenWithChooser,
                onSetAsRoot = onSetAsRoot
            )

            if (file.isDirectory && isExpanded) {
                RenderTreeNodes(
                    context = context,
                    parent = file,
                    expanded = expanded,
                    selected = selected,
                    parentSelected = isEffectivelySelected,
                    depth = depth + 1,
                    onToggleExpanded = onToggleExpanded,
                    onOpenFile = onOpenFile,
                    onSelectionChange = onSelectionChange,
                    isImageFile = isImageFile,
                    onOpenInGallery = onOpenInGallery,
                    onOpenWithChooser = onOpenWithChooser,
                    onSetAsRoot = onSetAsRoot
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileTreeRow(
    file: DocumentFile,
    depth: Int,
    isExpanded: Boolean,
    isSelected: Boolean,
    onToggleExpanded: () -> Unit,
    onOpenFile: () -> Unit,
    onSelect: () -> Unit,
    isImageFile: (Uri) -> Boolean,
    onOpenInGallery: (Uri) -> Unit,
    onOpenWithChooser: (Uri) -> Unit,
    onSetAsRoot: (Uri) -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val isImage = !file.isDirectory && isImageFile(file.uri)

    val icon = if (file.isDirectory) {
        if (isExpanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight
    } else {
        Icons.AutoMirrored.Filled.InsertDriveFile
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth * 12).dp)
            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            .padding(vertical = 2.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = isSelected,
            onClick = onSelect,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        if (file.isDirectory) {
            Icon(
                imageVector = icon,
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                modifier = Modifier
                    .size(20.dp)
                    .clickable { onToggleExpanded() },
                tint = MaterialTheme.colorScheme.primary
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = "File",
                modifier = Modifier.size(18.dp).padding(start = 2.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            Text(
                text = file.name ?: "Unnamed",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp)
                    .combinedClickable(
                        onClick = {
                            if (file.isDirectory) onToggleExpanded()
                            else if (isImage) showMenu = true
                            else onOpenFile()
                        },
                        onLongClick = {
                            showMenu = true
                        }
                    ),
                style = MaterialTheme.typography.bodyMedium,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                if (file.isDirectory) {
                    DropdownMenuItem(
                        text = { Text("Set as Pane Root") },
                        onClick = {
                            onSetAsRoot(file.uri)
                            showMenu = false
                        },
                        leadingIcon = { Icon(Icons.Filled.FolderOpen, null) }
                    )
                }
                if (isImage) {
                    DropdownMenuItem(
                        text = { Text("Open in Gallery") },
                        onClick = {
                            onOpenInGallery(file.uri)
                            showMenu = false
                        },
                        leadingIcon = { Icon(Icons.Filled.PhotoLibrary, null) }
                    )
                }
                DropdownMenuItem(
                    text = { Text("Open with App") },
                    onClick = {
                        onOpenWithChooser(file.uri)
                        showMenu = false
                    },
                    leadingIcon = { Icon(Icons.Filled.Edit, null) }
                )
            }
        }
    }
}
