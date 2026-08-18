package org.gpdresearch.hyperbrowser

import android.content.ContentResolver
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import java.io.InputStream
import java.io.OutputStream

private enum class Pane { LEFT, RIGHT }
private enum class TransferDirection { LEFT_TO_RIGHT, RIGHT_TO_LEFT }
private enum class TransferMode { COPY, MOVE }
private enum class GalleryMode { SINGLE, THUMBNAILS }
private enum class LayoutMode {
    PHONE,
    TABLET_BALANCED,
    TABLET_WIDE,
}

private data class BrowserPaneState(
    val root: Uri? = null,
    val current: Uri? = null,
    val selected: Set<Uri> = emptySet(),
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

    var leftPane by remember { mutableStateOf(BrowserPaneState()) }
    var rightPane by remember { mutableStateOf(BrowserPaneState()) }
    var activePane by remember { mutableStateOf(Pane.LEFT) }
    var transferDirection by remember { mutableStateOf(TransferDirection.LEFT_TO_RIGHT) }
    var layoutMode by remember { mutableStateOf(LayoutMode.PHONE) }
    var pendingRequest by remember { mutableStateOf<TransferRequest?>(null) }
    var galleryUri by remember { mutableStateOf<Uri?>(null) }
    var showLayoutSettings by remember { mutableStateOf(false) }

    val sourcePane = if (transferDirection == TransferDirection.LEFT_TO_RIGHT) Pane.LEFT else Pane.RIGHT
    val destinationPane = if (sourcePane == Pane.LEFT) Pane.RIGHT else Pane.LEFT
    val sourceState = if (sourcePane == Pane.LEFT) leftPane else rightPane
    val destinationState = if (destinationPane == Pane.LEFT) leftPane else rightPane
    val selected = sourceState.selected
    val selectedFile = selected.singleOrNull()
    val selectedMimeType = selectedFile?.let { resolveMimeType(activity.contentResolver, it) } ?: ""
    val isImageSelected = selectedMimeType.startsWith("image/")
    val selectionInfo = remember(selected, activity) { buildSelectionInfo(activity, selected) }

    val leftPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        activity.contentResolver.takePersistableUriPermission(uri, flags)
        leftPane = BrowserPaneState(root = uri, current = uri, selected = emptySet())
    }
    val rightPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        activity.contentResolver.takePersistableUriPermission(uri, flags)
        rightPane = BrowserPaneState(root = uri, current = uri, selected = emptySet())
    }

    fun enqueueTransfer(mode: TransferMode, selectedItems: Set<Uri> = sourceState.selected) {
        val sourceDir = sourceState.current ?: sourceState.root ?: return
        val targetDir = destinationState.current ?: destinationState.root ?: return
        pendingRequest = TransferRequest(
            sourceDir = sourceDir,
            targetDir = targetDir,
            selected = selectedItems,
            wholeDirectory = selectedItems.isEmpty(),
            mode = mode,
        )
    }

    fun executeTransferNow(mode: TransferMode, selectedItems: Set<Uri> = sourceState.selected) {
        val sourceDir = sourceState.current ?: sourceState.root ?: return
        val targetDir = destinationState.current ?: destinationState.root ?: return
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

    fun handleFileDoubleTap(uri: Uri) {
        val mime = resolveMimeType(activity.contentResolver, uri)
        if (mime.startsWith("image/")) {
            galleryUri = uri
        } else {
            openFileWithDefaultApp(activity, uri)
        }
    }

    MaterialTheme {
        Scaffold { contentPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            ) {
                MinimalTransferMenu(
                    direction = transferDirection,
                    onReverse = {
                        transferDirection = if (transferDirection == TransferDirection.LEFT_TO_RIGHT) TransferDirection.RIGHT_TO_LEFT else TransferDirection.LEFT_TO_RIGHT
                    },
                    onCopy = { executeTransferNow(TransferMode.COPY) },
                    onMove = { enqueueTransfer(TransferMode.MOVE) },
                    sourceLabel = sourceState.current?.let { resolveDisplayPath(activity, it) } ?: "source",
                    destinationLabel = destinationState.current?.let { resolveDisplayPath(activity, it) } ?: "destination",
                )

                PreviewDetailPane(
                    info = selectionInfo,
                    selectedFile = selectedFile,
                    isImage = isImageSelected,
                    onOpen = { selectedFile?.let { openFileWithDefaultApp(activity, it) } },
                    onView = { selectedFile?.let { if (isImageSelected) galleryUri = it } },
                )

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
                            onCopy = { executeTransferNow(TransferMode.COPY) },
                            onPaste = {},
                            onMove = {
                                val items = sourceState.selected.ifEmpty { setOfNotNull(sourceState.current) }
                                if (items.isNotEmpty()) {
                                    enqueueTransfer(TransferMode.MOVE, items)
                                }
                            },
                            onDelete = {
                                val items = sourceState.selected.ifEmpty { setOfNotNull(sourceState.current) }
                                if (items.isNotEmpty()) {
                                    enqueueTransfer(TransferMode.MOVE, items)
                                }
                            },
                            onSelectMulti = { activePane = sourcePane },
                            onOpenSettings = { showLayoutSettings = true },
                        )

                        DirectoryPane(
                            title = "Left",
                            state = leftPane,
                            isActive = activePane == Pane.LEFT,
                            modifier = Modifier.weight(if (layoutMode == LayoutMode.TABLET_WIDE) 1.6f else if (layoutMode == LayoutMode.TABLET_BALANCED) 1.2f else 1f),
                            onActivate = { activePane = Pane.LEFT },
                            onChooseRoot = { leftPicker.launch(null) },
                            onNavigate = { directory -> leftPane = leftPane.copy(current = directory, selected = emptySet()) },
                            onOpenFile = { uri -> handleFileDoubleTap(uri) },
                            onMoveUp = {
                                val currentDoc = leftPane.current?.let { DocumentFile.fromTreeUri(activity, it) }
                                val parent = currentDoc?.parentFile
                                if (parent != null) {
                                    leftPane = leftPane.copy(current = parent.uri, selected = emptySet())
                                }
                            },
                            onSelectionChange = { selectedSet -> leftPane = leftPane.copy(selected = selectedSet) },
                        )

                        DirectoryPane(
                            title = "Right",
                            state = rightPane,
                            isActive = activePane == Pane.RIGHT,
                            modifier = Modifier.weight(if (layoutMode == LayoutMode.TABLET_WIDE) 1.6f else if (layoutMode == LayoutMode.TABLET_BALANCED) 1.2f else 1f),
                            onActivate = { activePane = Pane.RIGHT },
                            onChooseRoot = { rightPicker.launch(null) },
                            onNavigate = { directory -> rightPane = rightPane.copy(current = directory, selected = emptySet()) },
                            onOpenFile = { uri -> handleFileDoubleTap(uri) },
                            onMoveUp = {
                                val currentDoc = rightPane.current?.let { DocumentFile.fromTreeUri(activity, it) }
                                val parent = currentDoc?.parentFile
                                if (parent != null) {
                                    rightPane = rightPane.copy(current = parent.uri, selected = emptySet())
                                }
                            },
                            onSelectionChange = { selectedSet -> rightPane = rightPane.copy(selected = selectedSet) },
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

    if (showLayoutSettings) {
        LayoutSettingsDialog(
            selectedMode = layoutMode,
            onSelect = { mode ->
                layoutMode = mode
                showLayoutSettings = false
            },
            onDismiss = { showLayoutSettings = false },
        )
    }
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
        val sourceDoc = DocumentFile.fromTreeUri(activity, request.sourceDir) ?: return
        val targetDoc = DocumentFile.fromTreeUri(activity, request.targetDir) ?: return
        when (request.mode) {
            TransferMode.COPY -> copyTree(activity.contentResolver, sourceDoc, targetDoc)
            TransferMode.MOVE -> moveTree(activity.contentResolver, sourceDoc, targetDoc)
        }
        return
    }

    val targetDoc = DocumentFile.fromTreeUri(activity, request.targetDir) ?: return
    request.selected.forEach { uri ->
        val sourceDoc = DocumentFile.fromSingleUri(activity, uri) ?: return@forEach
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
    input.copyTo(output)
    output.flush()
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
    val doc = DocumentFile.fromTreeUri(context, uri)
    return doc?.name ?: uri.lastPathSegment ?: "unknown"
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
        val doc = DocumentFile.fromSingleUri(activity, uri)
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
    val names = uris.take(3).mapNotNull { uri -> DocumentFile.fromSingleUri(activity, uri)?.name ?: uri.lastPathSegment }
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
    onCopy: () -> Unit,
    onMove: () -> Unit,
    sourceLabel: String,
    destinationLabel: String,
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(horizontalAlignment = Alignment.Start, modifier = Modifier.weight(1f)) {
                Text("From", style = MaterialTheme.typography.labelSmall)
                Text(sourceLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            IconButton(
                onClick = onReverse,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = if (direction == TransferDirection.LEFT_TO_RIGHT) Icons.AutoMirrored.Filled.ArrowForward else Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Reverse transfer direction",
                    modifier = Modifier.size(24.dp),
                )
            }

            Column(horizontalAlignment = Alignment.End, modifier = Modifier.weight(1f)) {
                Text("To", style = MaterialTheme.typography.labelSmall)
                Text(destinationLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onCopy, modifier = Modifier.weight(1f)) { Text("Copy files") }
            OutlinedButton(onClick = onMove, modifier = Modifier.weight(1f)) { Text("Move files") }
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
    onSelectMulti: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val stripWidth = when (layoutMode) {
        LayoutMode.PHONE -> 88.dp
        LayoutMode.TABLET_BALANCED -> 96.dp
        LayoutMode.TABLET_WIDE -> 104.dp
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
        CommandButton(label = "Paste", icon = Icons.Filled.MoreVert, onClick = onPaste)
        CommandButton(label = "Move", icon = Icons.AutoMirrored.Filled.DriveFileMove, onClick = onMove)
        CommandButton(label = "Delete", icon = Icons.Filled.Delete, onClick = onDelete)
        CommandButton(label = "Multi", icon = Icons.Filled.SelectAll, onClick = onSelectMulti)
        CommandButton(label = "Settings", icon = Icons.Filled.Settings, onClick = onOpenSettings)
    }
}

@Composable
private fun CommandButton(label: String, icon: ImageVector, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(6.dp)
            .size(width = 72.dp, height = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(26.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
private fun LayoutSettingsDialog(
    selectedMode: LayoutMode,
    onSelect: (LayoutMode) -> Unit,
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
private fun ImageViewerScreen(
    activity: ComponentActivity,
    startingUri: Uri,
    onClose: () -> Unit,
) {
    val parentDir = DocumentFile.fromSingleUri(activity, startingUri)?.parentFile?.uri ?: startingUri
    val directoryDoc = DocumentFile.fromTreeUri(activity, parentDir) ?: DocumentFile.fromSingleUri(activity, parentDir)
    val images = directoryDoc?.listFiles()?.filter { file ->
        file.isFile && isImageMimeType(resolveMimeType(activity.contentResolver, file.uri))
    } ?: emptyList()

    var currentUri by remember(startingUri) { mutableStateOf(startingUri) }
    var galleryMode by remember { mutableStateOf(GalleryMode.SINGLE) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var showMenu by remember { mutableStateOf(false) }

    val currentDoc = images.firstOrNull { it.uri == currentUri }
    val currentBitmap = currentDoc?.let { remember(it.uri, activity) { loadBitmap(activity, it.uri) } }

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
            Text(currentDoc?.name ?: "Image viewer", maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            if (galleryMode == GalleryMode.SINGLE && currentBitmap != null) {
                Image(
                    bitmap = currentBitmap,
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
                        .clickable(onClick = { showMenu = !showMenu }),
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
                        val thumb = remember(file.uri, activity) { loadBitmap(activity, file.uri, 120, 120) }
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
                                if (thumb != null) {
                                    Image(bitmap = thumb, contentDescription = file.name ?: "Thumbnail", modifier = Modifier.fillMaxSize())
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
        }
    }
}

private fun loadBitmap(activity: ComponentActivity, uri: Uri, width: Int = 0, height: Int = 0): ImageBitmap? {
    return activity.contentResolver.openInputStream(uri)?.use { input ->
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(input, null, bounds)
        val targetWidth = if (width > 0) width else bounds.outWidth
        val targetHeight = if (height > 0) height else bounds.outHeight
        val sample = computeInSampleSize(bounds.outWidth, bounds.outHeight, targetWidth, targetHeight)

        activity.contentResolver.openInputStream(uri)?.use { stream ->
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
) {
    val context = LocalContext.current
    val currentUri = state.current ?: state.root
    val currentDirectory = currentUri?.let { DocumentFile.fromTreeUri(context, it) }
    val files = currentDirectory?.listFiles()?.sortedWith(
        compareByDescending<DocumentFile> { it.isDirectory }.thenBy { it.name ?: "" },
    ) ?: emptyList()

    Column(modifier = modifier.clickable(onClick = onActivate).fillMaxHeight()) {
        if (currentDirectory == null && state.root == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Choose a root folder to begin",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                label = { Text(if (state.root == null) "Choose root" else "Change root") },
                leadingIcon = { Icon(Icons.Filled.FolderOpen, contentDescription = null, Modifier.size(18.dp)) },
            )
            if (state.root != null && currentUri != null && currentUri != state.root) {
                AssistChip(onClick = onMoveUp, label = { Text("Up") })
            }
        }

        Text(
            text = currentDirectory?.name ?: "No folder selected",
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium,
        )

        if (files.isEmpty()) {
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
