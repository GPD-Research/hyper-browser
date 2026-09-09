package org.gpdresearch.hyperbrowser

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.Settings
import android.util.LruCache
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.common.api.ApiException
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class Pane { LEFT, RIGHT }
private enum class TransferDirection { LEFT_TO_RIGHT, RIGHT_TO_LEFT }
private enum class TransferMode { COPY, MOVE }

private enum class AppTheme(val label: String) {
    LIGHT("Light"),
    INVERTED("Inverted"),
    HACKER("Hacker"),
}

private val MATRIX_GREEN = Color(0xFF00FF41)

private fun colorSchemeFor(theme: AppTheme) = when (theme) {
    AppTheme.LIGHT -> lightColorScheme()
    AppTheme.INVERTED -> darkColorScheme(
        background = Color.Black,
        surface = Color.Black,
        onBackground = Color.White,
        onSurface = Color.White,
        primary = Color.White,
        onPrimary = Color.Black,
    )
    AppTheme.HACKER -> darkColorScheme(
        background = Color.Black,
        surface = Color.Black,
        onBackground = MATRIX_GREEN,
        onSurface = MATRIX_GREEN,
        primary = MATRIX_GREEN,
        onPrimary = Color.Black,
        secondary = MATRIX_GREEN,
        onSecondary = Color.Black,
    )
}

/** Selection is marked with an outline rather than a fill so it never relies on hue alone. */
private fun selectionOutlineColor(theme: AppTheme) = when (theme) {
    AppTheme.LIGHT -> Color(0xFFD32F2F)
    AppTheme.INVERTED -> Color.White
    AppTheme.HACKER -> MATRIX_GREEN
}

/**
 * Gallery detail levels. Only [SINGLE] decodes the file at its native resolution; the grids trade
 * detail for scroll smoothness.
 */
private enum class GalleryStage(val columns: Int, val thumbnailPx: Int, val lowQuality: Boolean) {
    GRID_SMALL(columns = 4, thumbnailPx = 192, lowQuality = true),
    GRID_MEDIUM(columns = 2, thumbnailPx = 640, lowQuality = false),
    SINGLE(columns = 1, thumbnailPx = 0, lowQuality = false),
}

private const val MAX_SINGLE_ZOOM = 24f

private enum class LayoutMode {
    PHONE,
    TABLET_BALANCED,
    TABLET_WIDE,
}

private data class FileDisplayOptions(
    val showHiddenFiles: Boolean = false,
    val showTrashFiles: Boolean = false,
)

private enum class SortOrder(val label: String) {
    NAME_ASC("Alphanumeric A-Z"),
    NAME_DESC("Alphanumeric Z-A"),
    MODIFIED_NEWEST("Modified newest"),
    MODIFIED_OLDEST("Modified oldest"),
    CREATED_NEWEST("Created newest"),
    CREATED_OLDEST("Created oldest"),
}

/** When [split] is off, folders follow [fileOrder] too; folders are always listed first. */
private data class SortOptions(
    val split: Boolean = true,
    val folderOrder: SortOrder = SortOrder.NAME_ASC,
    val fileOrder: SortOrder = SortOrder.MODIFIED_NEWEST,
)

private fun comparatorFor(order: SortOrder): Comparator<FileEntry> {
    val byName = compareBy(String.CASE_INSENSITIVE_ORDER) { entry: FileEntry -> entry.name }
    return when (order) {
        SortOrder.NAME_ASC -> byName
        SortOrder.NAME_DESC -> byName.reversed()
        SortOrder.MODIFIED_NEWEST -> compareByDescending<FileEntry> { it.lastModified }.then(byName)
        SortOrder.MODIFIED_OLDEST -> compareBy<FileEntry> { it.lastModified }.then(byName)
        SortOrder.CREATED_NEWEST -> compareByDescending<FileEntry> { it.createdAt }.then(byName)
        SortOrder.CREATED_OLDEST -> compareBy<FileEntry> { it.createdAt }.then(byName)
    }
}

private fun sortEntries(entries: List<FileEntry>, options: SortOptions): List<FileEntry> {
    val folderOrder = if (options.split) options.folderOrder else options.fileOrder
    return entries.filter { it.isDirectory }.sortedWith(comparatorFor(folderOrder)) +
        entries.filterNot { it.isDirectory }.sortedWith(comparatorFor(options.fileOrder))
}

private data class BrowserPaneState(
    val root: Uri? = null,
    val current: Uri? = null,
    val selected: Set<Uri> = emptySet(),
    val refreshKey: Int = 0,
)

private const val PANE_PREFS = "hyper_browser_panes"
private const val PANE_LEFT = "left"
private const val PANE_RIGHT = "right"
private const val THEME_PREF = "app_theme"

private fun loadPaneState(prefs: SharedPreferences, key: String): BrowserPaneState {
    val root = prefs.getString("${key}_root", null)?.let(Uri::parse) ?: return BrowserPaneState()
    val current = prefs.getString("${key}_current", null)?.let(Uri::parse) ?: root
    // A file:// root only works while All files access is granted, so drop it when unreadable.
    if (root.scheme == ContentResolver.SCHEME_FILE && root.path?.let { File(it).canRead() } != true) {
        return BrowserPaneState()
    }
    return BrowserPaneState(root = root, current = current)
}

private fun savePaneState(prefs: SharedPreferences, key: String, state: BrowserPaneState) {
    prefs.edit()
        .putString("${key}_root", state.root?.toString())
        .putString("${key}_current", state.current?.toString())
        .apply()
}

private data class DirectoryListing(
    val name: String?,
    val files: List<FileEntry>,
)

private data class TransferRequest(
    val sourceDir: Uri,
    val targetDir: Uri,
    val selected: Set<Uri>,
    val wholeDirectory: Boolean,
    val mode: TransferMode,
)

private data class FolderTransferPrompt(
    val request: TransferRequest,
    val sourceName: String,
    val targetName: String,
    val offerDeleteSource: Boolean,
)

private data class FilesIntoFolderPrompt(
    val request: TransferRequest,
    val itemCount: Int,
    val targetName: String,
)

private sealed interface TransferPlan {
    /** The selection is valid, so the operation only stages the clipboard. */
    object Staged : TransferPlan

    /** The arrow points a folder at a file selection, which cannot be a destination. */
    object ReverseSuggested : TransferPlan

    data class FolderIntoFolder(val prompt: FolderTransferPrompt) : TransferPlan

    /** Files are selected on both sides, so the destination folder has to be inferred. */
    data class FilesIntoFolder(val prompt: FilesIntoFolderPrompt) : TransferPlan
}

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

    val prefs = remember { activity.getSharedPreferences(PANE_PREFS, Context.MODE_PRIVATE) }
    var leftPane by remember { mutableStateOf(loadPaneState(prefs, PANE_LEFT)) }
    var rightPane by remember { mutableStateOf(loadPaneState(prefs, PANE_RIGHT)) }
    var activePane by remember { mutableStateOf(Pane.LEFT) }
    var transferDirection by remember { mutableStateOf(TransferDirection.LEFT_TO_RIGHT) }
    var layoutMode by remember { mutableStateOf(LayoutMode.PHONE) }
    var pendingRequest by remember { mutableStateOf<TransferRequest?>(null) }
    var pendingFolderTransfer by remember { mutableStateOf<FolderTransferPrompt?>(null) }
    var pendingFilesTransfer by remember { mutableStateOf<FilesIntoFolderPrompt?>(null) }
    var reversePrompt by remember { mutableStateOf<TransferMode?>(null) }
    var pendingDelete by remember { mutableStateOf<Set<Uri>?>(null) }
    var propertiesUri by remember { mutableStateOf<Uri?>(null) }
    var galleryUri by remember { mutableStateOf<Uri?>(null) }
    var galleryDirectory by remember { mutableStateOf<Uri?>(null) }
    var showLayoutSettings by remember { mutableStateOf(false) }
    var fileDisplayOptions by remember { mutableStateOf(FileDisplayOptions()) }
    var sortOptions by remember { mutableStateOf(SortOptions()) }
    var gallerySort by remember { mutableStateOf(SortOrder.MODIFIED_NEWEST) }
    var showSortSettings by remember { mutableStateOf(false) }
    var selectionPreviewVisible by remember { mutableStateOf(true) }
    var selectionPreviewOffset by remember { mutableStateOf(Offset.Zero) }
    var clipboard by remember { mutableStateOf<ClipboardEntry?>(null) }

    var showLeftPicker by remember { mutableStateOf(false) }
    var showRightPicker by remember { mutableStateOf(false) }
    var multiSelect by remember { mutableStateOf(false) }
    var appTheme by remember {
        val stored = prefs.getString(THEME_PREF, null)
        mutableStateOf(AppTheme.entries.firstOrNull { it.name == stored } ?: AppTheme.LIGHT)
    }

    val sourcePane = if (transferDirection == TransferDirection.LEFT_TO_RIGHT) Pane.LEFT else Pane.RIGHT
    val destinationPane = if (sourcePane == Pane.LEFT) Pane.RIGHT else Pane.LEFT
    val sourceState = if (sourcePane == Pane.LEFT) leftPane else rightPane
    val destinationState = if (destinationPane == Pane.LEFT) leftPane else rightPane
    val scope = rememberCoroutineScope()
    val selected = sourceState.selected
    val selectedFile = selected.singleOrNull()
    val selectedMimeType by produceState(initialValue = "", selectedFile) {
        value = selectedFile?.let { uri -> withContext(Dispatchers.IO) { Storage.mimeType(activity, uri) } } ?: ""
    }
    val isImageSelected = selectedMimeType.startsWith("image/")
    val idleSelectionInfo = remember { buildSelectionInfo(activity, emptySet()) }
    val selectionInfo by produceState(initialValue = idleSelectionInfo, selected, activity) {
        value = if (selected.isEmpty()) idleSelectionInfo else withContext(Dispatchers.IO) { buildSelectionInfo(activity, selected) }
    }
    val leftLabel by produceState(initialValue = "Choose left root", leftPane.current, leftPane.root) {
        val uri = leftPane.current ?: leftPane.root
        value = uri?.let { withContext(Dispatchers.IO) { resolveDisplayPath(activity, it) } } ?: "Choose left root"
    }
    val rightLabel by produceState(initialValue = "Choose right root", rightPane.current, rightPane.root) {
        val uri = rightPane.current ?: rightPane.root
        value = uri?.let { withContext(Dispatchers.IO) { resolveDisplayPath(activity, it) } } ?: "Choose right root"
    }

    fun refreshPanesAfterWrite() {
        leftPane = leftPane.copy(refreshKey = leftPane.refreshKey + 1, selected = if (sourcePane == Pane.LEFT) emptySet() else leftPane.selected)
        rightPane = rightPane.copy(refreshKey = rightPane.refreshKey + 1, selected = if (sourcePane == Pane.RIGHT) emptySet() else rightPane.selected)
    }

    fun runTransfer(request: TransferRequest) {
        scope.launch {
            val failures = withContext(Dispatchers.IO) { executeTransfer(activity, request) }
            refreshPanesAfterWrite()
            if (failures > 0) {
                Toast.makeText(activity, failureMessage(failures, "transferred"), Toast.LENGTH_LONG).show()
            }
        }
    }

    fun stageClipboard(mode: TransferMode, source: BrowserPaneState) {
        val sourceDir = source.current ?: source.root ?: return
        clipboard = ClipboardEntry(sourceDir = sourceDir, items = source.selected, mode = mode)
    }

    fun startOperation(mode: TransferMode, source: BrowserPaneState, target: BrowserPaneState) {
        scope.launch {
            when (val plan = withContext(Dispatchers.IO) { planTransfer(activity, source, target, mode) }) {
                TransferPlan.Staged -> stageClipboard(mode, source)
                TransferPlan.ReverseSuggested -> reversePrompt = mode
                is TransferPlan.FolderIntoFolder -> pendingFolderTransfer = plan.prompt
                is TransferPlan.FilesIntoFolder -> pendingFilesTransfer = plan.prompt
            }
        }
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

    fun openWith(uri: Uri) {
        scope.launch {
            val mime = withContext(Dispatchers.IO) { Storage.mimeType(activity, uri) }
            launchExternalApp(activity, uri, mime)
        }
    }

    fun openFileTarget(uri: Uri, directory: Uri?) {
        scope.launch {
            val mime = withContext(Dispatchers.IO) { Storage.mimeType(activity, uri) }
            if (mime.startsWith("image/")) {
                openGallery(uri, directory)
            } else {
                launchExternalApp(activity, uri, mime)
            }
        }
    }

    val runtimePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.any { granted -> !granted }) {
            Toast.makeText(activity, "Storage permission denied; some folders will look empty", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(leftPane.root, leftPane.current, rightPane.root, rightPane.current) {
        savePaneState(prefs, PANE_LEFT, leftPane)
        savePaneState(prefs, PANE_RIGHT, rightPane)
    }

    LaunchedEffect(Unit) {
        val missing = missingRuntimePermissions(activity)
        if (missing.isNotEmpty()) {
            runtimePermissionLauncher.launch(missing)
        }

        // A previously authorised Google account is reused so Drive roots survive a restart.
        DriveAuth.authorizedAccount(activity)?.account?.let { account ->
            withContext(Dispatchers.IO) { DriveClient.connect(activity, account, account.name) }
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

    MaterialTheme(colorScheme = colorSchemeFor(appTheme)) {
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
                if (galleryUri == null) {
                    MinimalTransferMenu(
                        direction = transferDirection,
                        onReverse = {
                            transferDirection = if (transferDirection == TransferDirection.LEFT_TO_RIGHT) TransferDirection.RIGHT_TO_LEFT else TransferDirection.LEFT_TO_RIGHT
                        },
                        onChooseLeftRoot = { showLeftPicker = true },
                        onChooseRightRoot = { showRightPicker = true },
                        leftLabel = leftLabel,
                        rightLabel = rightLabel,
                    )
                }

                if (galleryUri != null) {
                    ImageViewerScreen(
                        activity = activity,
                        startingUri = galleryUri!!,
                        directoryUri = galleryDirectory,
                        sortOrder = gallerySort,
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
                                onCopy = { startOperation(TransferMode.COPY, sourceState, destinationState) },
                                onPaste = { pasteClipboard() },
                                onMove = { startOperation(TransferMode.MOVE, sourceState, destinationState) },
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
                                multiSelect = multiSelect,
                                // Leaving multi mode doubles as "I changed my mind" and drops the payload.
                                onToggleMulti = {
                                    if (multiSelect) {
                                        multiSelect = false
                                        leftPane = leftPane.copy(selected = emptySet())
                                        rightPane = rightPane.copy(selected = emptySet())
                                    } else {
                                        multiSelect = true
                                    }
                                },
                                selectionActive = leftPane.selected.isNotEmpty() || rightPane.selected.isNotEmpty(),
                                onDeselect = {
                                    leftPane = leftPane.copy(selected = emptySet())
                                    rightPane = rightPane.copy(selected = emptySet())
                                },
                                onOpenSettings = { showLayoutSettings = true },
                            )

                            DirectoryPane(
                                state = leftPane,
                                isActive = activePane == Pane.LEFT,
                                modifier = Modifier.weight(if (layoutMode == LayoutMode.TABLET_WIDE) 1.6f else if (layoutMode == LayoutMode.TABLET_BALANCED) 1.2f else 1f),
                                onActivate = { activePane = Pane.LEFT },
                                onNavigate = { directory -> leftPane = leftPane.copy(current = directory, selected = emptySet()) },
                                onOpenFile = { uri -> openFileTarget(uri, leftPane.current ?: leftPane.root) },
                                onOpenWith = { uri -> openWith(uri) },
                                onShowProperties = { uri -> propertiesUri = uri },
                                onMoveUp = {
                                    val parent = leftPane.current?.let { Storage.parent(activity, it) }
                                    if (parent != null) {
                                        leftPane = leftPane.copy(current = parent, selected = emptySet())
                                    }
                                },
                                onSelectionChange = { selectedSet -> leftPane = leftPane.copy(selected = selectedSet) },
                                multiSelect = multiSelect,
                                selectionOutline = selectionOutlineColor(appTheme),
                                showHiddenFiles = fileDisplayOptions.showHiddenFiles,
                                showTrashFiles = fileDisplayOptions.showTrashFiles,
                                sortOptions = sortOptions,
                            )

                            DirectoryPane(
                                state = rightPane,
                                isActive = activePane == Pane.RIGHT,
                                modifier = Modifier.weight(if (layoutMode == LayoutMode.TABLET_WIDE) 1.6f else if (layoutMode == LayoutMode.TABLET_BALANCED) 1.2f else 1f),
                                onActivate = { activePane = Pane.RIGHT },
                                onNavigate = { directory -> rightPane = rightPane.copy(current = directory, selected = emptySet()) },
                                onOpenFile = { uri -> openFileTarget(uri, rightPane.current ?: rightPane.root) },
                                onOpenWith = { uri -> openWith(uri) },
                                onShowProperties = { uri -> propertiesUri = uri },
                                onMoveUp = {
                                    val parent = rightPane.current?.let { Storage.parent(activity, it) }
                                    if (parent != null) {
                                        rightPane = rightPane.copy(current = parent, selected = emptySet())
                                    }
                                },
                                onSelectionChange = { selectedSet -> rightPane = rightPane.copy(selected = selectedSet) },
                                multiSelect = multiSelect,
                                selectionOutline = selectionOutlineColor(appTheme),
                                showHiddenFiles = fileDisplayOptions.showHiddenFiles,
                                showTrashFiles = fileDisplayOptions.showTrashFiles,
                                sortOptions = sortOptions,
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
                        onOpen = { selectedFile?.let { uri -> openWith(uri) } },
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

    if (reversePrompt != null) {
        ReverseFlowDialog(
            onDismiss = { reversePrompt = null },
            onReverse = {
                val mode = reversePrompt!!
                reversePrompt = null
                transferDirection = if (transferDirection == TransferDirection.LEFT_TO_RIGHT) TransferDirection.RIGHT_TO_LEFT else TransferDirection.LEFT_TO_RIGHT
                startOperation(mode, destinationState, sourceState)
            },
        )
    }

    if (pendingFolderTransfer != null) {
        ConfirmFolderTransferDialog(
            prompt = pendingFolderTransfer!!,
            onDismiss = { pendingFolderTransfer = null },
            onConfirm = { request ->
                pendingFolderTransfer = null
                runTransfer(request)
            },
        )
    }

    if (pendingFilesTransfer != null) {
        ConfirmFilesIntoFolderDialog(
            prompt = pendingFilesTransfer!!,
            onDismiss = { pendingFilesTransfer = null },
            onConfirm = { request ->
                pendingFilesTransfer = null
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
                    val failures = withContext(Dispatchers.IO) { deleteItems(activity, items) }
                    refreshPanesAfterWrite()
                    if (failures > 0) {
                        Toast.makeText(activity, failureMessage(failures, "deleted"), Toast.LENGTH_LONG).show()
                    }
                }
            },
        )
    }

    if (propertiesUri != null) {
        FolderPropertiesDialog(
            activity = activity,
            uri = propertiesUri!!,
            onDismiss = { propertiesUri = null },
        )
    }

    if (showLayoutSettings) {
        LayoutSettingsDialog(
            selectedMode = layoutMode,
            showHiddenFiles = fileDisplayOptions.showHiddenFiles,
            showTrashFiles = fileDisplayOptions.showTrashFiles,
            currentTheme = appTheme,
            onSelect = { mode ->
                layoutMode = mode
                showLayoutSettings = false
            },
            onThemeSelect = { theme ->
                appTheme = theme
                prefs.edit().putString(THEME_PREF, theme.name).apply()
            },
            onToggleHiddenFiles = { fileDisplayOptions = fileDisplayOptions.copy(showHiddenFiles = it) },
            onToggleTrashFiles = { fileDisplayOptions = fileDisplayOptions.copy(showTrashFiles = it) },
            onOpenSort = {
                showLayoutSettings = false
                showSortSettings = true
            },
            onDismiss = { showLayoutSettings = false },
        )
    }

    if (showSortSettings) {
        SortSettingsDialog(
            options = sortOptions,
            gallerySort = gallerySort,
            onOptionsChange = { sortOptions = it },
            onGallerySortChange = { gallerySort = it },
            onDismiss = { showSortSettings = false },
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

/** Raw file:// URIs cannot legally leave the process, so other apps get a FileProvider URI instead. */
private fun shareableUri(context: Context, uri: Uri): Uri? {
    if (uri.scheme != ContentResolver.SCHEME_FILE) return uri
    val path = uri.path ?: return null
    return runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(path))
    }.getOrNull()
}

private fun launchExternalApp(
    activity: ComponentActivity,
    uri: Uri,
    mimeType: String?,
    action: String = Intent.ACTION_VIEW,
    title: String = "Open with",
) {
    // Drive items are synthetic URIs no other app can resolve; they must be copied out first.
    val shared = if (DriveUris.isDrive(uri)) null else shareableUri(activity, uri)
    if (shared == null) {
        Toast.makeText(activity, "Copy this file to local storage to open it elsewhere", Toast.LENGTH_SHORT).show()
        return
    }
    val intent = Intent(action).apply {
        setDataAndType(shared, mimeType?.takeIf { it.isNotBlank() } ?: "*/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }
    runCatching { activity.startActivity(Intent.createChooser(intent, title)) }
        .onFailure { Toast.makeText(activity, "No app can open this file", Toast.LENGTH_SHORT).show() }
}

private fun planTransfer(
    activity: ComponentActivity,
    source: BrowserPaneState,
    target: BrowserPaneState,
    mode: TransferMode,
): TransferPlan {
    val sourceDocs = source.selected.mapNotNull { Storage.entry(activity, it) }
    val targetDocs = target.selected.mapNotNull { Storage.entry(activity, it) }
    val sourceFolders = sourceDocs.filter { it.isDirectory }
    val targetFolders = targetDocs.filter { it.isDirectory }

    // A folder can only land inside another folder, never inside the selected files.
    if (sourceFolders.isNotEmpty() && targetDocs.any { !it.isDirectory }) {
        return TransferPlan.ReverseSuggested
    }

    if (sourceDocs.isNotEmpty() && sourceFolders.isEmpty() && targetDocs.isNotEmpty() && targetFolders.isEmpty()) {
        val targetDir = target.current ?: target.root
        val sourceDir = source.current ?: source.root
        if (targetDir != null && sourceDir != null) {
            val targetFolder = Storage.entry(activity, targetDir)
            return TransferPlan.FilesIntoFolder(
                FilesIntoFolderPrompt(
                    request = TransferRequest(
                        sourceDir = sourceDir,
                        targetDir = targetDir,
                        selected = sourceDocs.map { it.uri }.toSet(),
                        wholeDirectory = false,
                        mode = mode,
                    ),
                    itemCount = sourceDocs.size,
                    targetName = targetFolder?.name ?: resolveDisplayPath(activity, targetDir),
                ),
            )
        }
    }

    if (sourceDocs.size == 1 && sourceFolders.size == 1 && targetDocs.size == 1 && targetFolders.size == 1) {
        val sourceFolder = sourceFolders.first()
        val targetFolder = targetFolders.first()
        return TransferPlan.FolderIntoFolder(
            FolderTransferPrompt(
                request = TransferRequest(
                    sourceDir = sourceFolder.uri,
                    targetDir = targetFolder.uri,
                    selected = emptySet(),
                    wholeDirectory = true,
                    mode = mode,
                ),
                sourceName = sourceFolder.name,
                targetName = targetFolder.name,
                offerDeleteSource = mode == TransferMode.MOVE && isRemoteOrRemovableUri(sourceFolder.uri),
            ),
        )
    }

    return TransferPlan.Staged
}

// Cloud providers and removable volumes make a "move" destructive in ways the user should opt into.
private fun isRemoteOrRemovableUri(uri: Uri): Boolean {
    if (DriveUris.isDrive(uri)) return true
    if (uri.scheme != ContentResolver.SCHEME_CONTENT) return false
    if (uri.authority != "com.android.externalstorage.documents") return true
    val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
        ?: runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        ?: return false
    return !documentId.startsWith("primary:")
}

private fun executeTransfer(activity: ComponentActivity, request: TransferRequest): Int {
    val copyMode = request.mode == TransferMode.COPY
    val takenNames = Storage.childNames(activity, request.targetDir)
    var failures = 0
    if (request.wholeDirectory) {
        val source = Storage.entry(activity, request.sourceDir) ?: return 1
        if (!transferEntry(activity, source, request.targetDir, takenNames, copyMode)) failures += 1
        return failures
    }
    request.selected.forEach { uri ->
        val source = Storage.entry(activity, uri)
        if (source == null || !transferEntry(activity, source, request.targetDir, takenNames, copyMode)) {
            failures += 1
        }
    }
    return failures
}

private fun deleteItems(activity: ComponentActivity, uris: Set<Uri>): Int =
    uris.count { uri -> !Storage.delete(activity, uri) }

private fun failureMessage(count: Int, verb: String): String =
    if (count == 1) "1 item could not be $verb" else "$count items could not be $verb"

/** [takenNames] is threaded through so a batch transfer does not re-list the destination per item. */
private fun transferEntry(
    context: Context,
    source: FileEntry,
    targetDir: Uri,
    takenNames: MutableSet<String>,
    copyMode: Boolean,
): Boolean {
    if (source.isDirectory) {
        val folderName = nextAvailableName(takenNames, source.name)
        val destination = Storage.createFolder(context, targetDir, folderName) ?: return false
        val childNames = mutableSetOf<String>()
        var ok = true
        Storage.children(context, source.uri).forEach { child ->
            if (child.uri == destination.uri) return@forEach
            if (!transferEntry(context, child, destination.uri, childNames, copyMode)) {
                ok = false
            }
        }
        if (!copyMode && ok) {
            Storage.delete(context, source.uri)
        }
        return ok
    }

    if (!copyFileContents(context, source, targetDir, takenNames)) return false
    if (!copyMode) {
        Storage.delete(context, source.uri)
    }
    return true
}

private fun copyFileContents(
    context: Context,
    source: FileEntry,
    targetDir: Uri,
    takenNames: MutableSet<String>,
): Boolean {
    val content = Storage.read(context, source) ?: return false
    val name = nextAvailableName(takenNames, content.fileName)
    return runCatching {
        content.stream.use { input -> Storage.writeChild(context, targetDir, name, content.mimeType, input) }
    }.getOrDefault(false)
}

private fun nextAvailableName(takenNames: MutableSet<String>, preferredName: String): String {
    fun claim(name: String): String {
        takenNames += name
        return name
    }
    if (preferredName !in takenNames) return claim(preferredName)
    val dotIndex = preferredName.lastIndexOf('.')
    val base = if (dotIndex > 0) preferredName.substring(0, dotIndex) else preferredName
    val extension = if (dotIndex > 0) preferredName.substring(dotIndex) else ""
    var counter = 1
    while (true) {
        val candidate = "$base ($counter)$extension"
        if (candidate !in takenNames) return claim(candidate)
        counter += 1
    }
}

private fun resolveDisplayPath(context: Context, uri: Uri): String =
    Storage.entry(context, uri)?.name ?: uri.lastPathSegment ?: "unknown"

private fun isImageMimeType(value: String): Boolean = value.startsWith("image/")

private fun isHiddenPathSegment(name: String?): Boolean {
    return name?.startsWith(".") == true || name?.startsWith("$") == true
}

private fun isTrashRelatedPath(name: String?): Boolean {
    val lower = name?.lowercase() ?: return false
    return lower.contains("trash") || lower.contains("recycle") || lower.contains("deleted") || lower.contains("recentlydeleted")
}

private fun shouldDisplayDocument(doc: FileEntry, showHiddenFiles: Boolean, showTrashFiles: Boolean): Boolean {
    val hidden = isHiddenPathSegment(doc.name)
    val trash = isTrashRelatedPath(doc.name)
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
        val doc = Storage.entry(activity, uri)
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
        val childCount = if (isDir) runCatching { Storage.children(activity, uri).size }.getOrNull() else null
        return SelectionInfo(
            title = doc.name,
            kind = if (isDir) "Folder" else "File",
            sizeLabel = if (isDir) childCount?.let { "$it items" } ?: "Unknown" else formatBytes(doc.size),
            path = doc.uri.toString(),
            details = if (isDir) "Directory" else (doc.mimeType ?: "Document"),
        )
    }
    val names = uris.take(3).mapNotNull { uri -> Storage.entry(activity, uri)?.name ?: uri.lastPathSegment }
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
    leftLabel: String,
    rightLabel: String,
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
                label = { Text(leftLabel, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Filled.FolderOpen, contentDescription = null, Modifier.size(14.dp)) },
                modifier = Modifier.weight(1f),
            )

            IconButton(
                onClick = onReverse,
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                    .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape),
            ) {
                Icon(
                    imageVector = if (direction == TransferDirection.LEFT_TO_RIGHT) Icons.AutoMirrored.Filled.ArrowForward else Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Reverse transfer direction",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
            }

            AssistChip(
                onClick = onChooseRightRoot,
                label = { Text(rightLabel, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Filled.FolderOpen, contentDescription = null, Modifier.size(14.dp)) },
                modifier = Modifier.weight(1f),
            )
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
    multiSelect: Boolean,
    onToggleMulti: () -> Unit,
    selectionActive: Boolean,
    onDeselect: () -> Unit,
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
        CommandButton(label = "Move", icon = Icons.AutoMirrored.Filled.DriveFileMove, onClick = onMove)
        CommandButton(label = "Delete", icon = Icons.Filled.Delete, onClick = onDelete)
        CommandButton(label = "Gallery", icon = Icons.Filled.Image, onClick = onGallery)
        CommandButton(label = "Multi", icon = Icons.Filled.SelectAll, onClick = onToggleMulti, active = multiSelect)
        if (selectionActive) {
            CommandButton(label = "Deselect", icon = Icons.Filled.Deselect, onClick = onDeselect)
        }
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
    active: Boolean = false,
) {
    Column(
        modifier = Modifier
            .clickable(enabled = enabled, onClick = onClick)
            .fillMaxWidth()
            .height(76.dp)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val contentColor = when {
            !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            active -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurface
        }
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
    currentTheme: AppTheme,
    onSelect: (LayoutMode) -> Unit,
    onThemeSelect: (AppTheme) -> Unit,
    onToggleHiddenFiles: (Boolean) -> Unit,
    onToggleTrashFiles: (Boolean) -> Unit,
    onOpenSort: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Layout settings") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
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

                Text("Sorting", style = MaterialTheme.typography.labelLarge)
                Button(onClick = onOpenSort, modifier = Modifier.fillMaxWidth()) {
                    Text("Sort options")
                }

                Text("App theme", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppTheme.entries.forEach { theme ->
                        Button(
                            onClick = { onThemeSelect(theme) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(4.dp),
                        ) {
                            Text(
                                if (theme == currentTheme) "${theme.label} *" else theme.label,
                                fontSize = 11.sp,
                                maxLines = 1,
                            )
                        }
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
private fun SortSettingsDialog(
    options: SortOptions,
    gallerySort: SortOrder,
    onOptionsChange: (SortOptions) -> Unit,
    onGallerySortChange: (SortOrder) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sort options") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = options.split,
                        onCheckedChange = { onOptionsChange(options.copy(split = it)) },
                    )
                    Text("Sort folders separately from files")
                }

                if (options.split) {
                    Text("Folders", style = MaterialTheme.typography.labelLarge)
                    SortOrder.entries.forEach { order ->
                        SortChoice(order.label, order == options.folderOrder) {
                            onOptionsChange(options.copy(folderOrder = order))
                        }
                    }
                }

                Text(if (options.split) "Files" else "Folders and files", style = MaterialTheme.typography.labelLarge)
                SortOrder.entries.forEach { order ->
                    SortChoice(order.label, order == options.fileOrder) {
                        onOptionsChange(options.copy(fileOrder = order))
                    }
                }

                Text("Gallery", style = MaterialTheme.typography.labelLarge)
                SortOrder.entries.forEach { order ->
                    SortChoice(order.label, order == gallerySort) { onGallerySortChange(order) }
                }

                Text(
                    "Creation dates are only tracked on local storage and Google Drive; elsewhere they fall back to the modified date.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun SortChoice(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
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
    val scope = rememberCoroutineScope()
    var rootUri by remember { mutableStateOf<Uri?>(null) }
    var currentUri by remember { mutableStateOf<Uri?>(null) }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var driveMessage by remember { mutableStateOf<String?>(null) }

    fun takeUriPermissionSafely(uri: Uri) {
        if (uri.scheme != "content") return
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            context.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
        }
    }

    fun browseDriveRoot() {
        rootUri = DriveUris.ROOT
        currentUri = DriveUris.ROOT
        selectedUri = DriveUris.ROOT
    }

    fun connectDrive(account: GoogleSignInAccount) {
        val androidAccount = account.account
        if (androidAccount == null) {
            driveMessage = "That Google account could not be used for Drive."
            return
        }
        scope.launch {
            withContext(Dispatchers.IO) { DriveClient.connect(context, androidAccount, account.email) }
            driveMessage = null
            browseDriveRoot()
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

    val driveSignInLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val account = runCatching {
            GoogleSignIn.getSignedInAccountFromIntent(result.data).getResult(ApiException::class.java)
        }.getOrNull()
        if (account != null && DriveAuth.hasDriveScope(account)) {
            connectDrive(account)
        } else {
            driveMessage = "Drive sign-in was cancelled or the Drive permission was declined."
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
                    Spacer(modifier = Modifier.size(8.dp))
                    Button(
                        onClick = {
                            if (DriveClient.isConnected) {
                                browseDriveRoot()
                            } else {
                                val existing = DriveAuth.authorizedAccount(context)
                                if (existing != null) {
                                    connectDrive(existing)
                                } else {
                                    driveSignInLauncher.launch(DriveAuth.signInClient(context).signInIntent)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(DriveClient.accountName?.let { "Google Drive ($it)" } ?: "Google Drive")
                    }
                    driveMessage?.let {
                        Spacer(modifier = Modifier.size(4.dp))
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
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
                    val browseUri = currentUri!!
                    val folders by produceState(initialValue = emptyList<FileEntry>(), browseUri) {
                        value = withContext(Dispatchers.IO) {
                            Storage.children(context, browseUri).filter { it.isDirectory }
                        }
                    }
                    val browseName by produceState(initialValue = "…", browseUri) {
                        value = withContext(Dispatchers.IO) { resolveDisplayPath(context, browseUri) }
                    }

                    Text(
                        text = "Browsing: $browseName",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.size(8.dp))

                    LazyColumn(modifier = Modifier.height(300.dp)) {
                        if (currentUri != rootUri) {
                            item {
                                TextButton(
                                    onClick = { currentUri = currentUri?.let { Storage.parent(context, it) } ?: rootUri },
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
                                    text = folder.name,
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

@Composable
private fun ReverseFlowDialog(
    onDismiss: () -> Unit,
    onReverse: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Illogical operation: Reverse flow?") },
        text = {
            Text("A folder cannot be transferred into the selected file(s). Reverse the arrow so the selected file(s) move into the folder instead.")
        },
        confirmButton = {
            TextButton(onClick = onReverse) { Text("Reverse flow") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun ConfirmFolderTransferDialog(
    prompt: FolderTransferPrompt,
    onDismiss: () -> Unit,
    onConfirm: (TransferRequest) -> Unit,
) {
    var deleteSource by remember(prompt) { mutableStateOf(false) }
    val verb = if (prompt.request.mode == TransferMode.MOVE) "Move" else "Copy"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Confirm folder transfer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("$verb ${prompt.sourceName} into ${prompt.targetName}?")
                if (prompt.offerDeleteSource) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = deleteSource, onCheckedChange = { deleteSource = it })
                        Text("Delete source directory")
                    }
                    Text(
                        "The source is on a cloud or external volume. Leave this unchecked to copy the folder and keep the original.",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val mode = if (prompt.offerDeleteSource && !deleteSource) TransferMode.COPY else prompt.request.mode
                    onConfirm(prompt.request.copy(mode = mode))
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
private fun ConfirmFilesIntoFolderDialog(
    prompt: FilesIntoFolderPrompt,
    onDismiss: () -> Unit,
    onConfirm: (TransferRequest) -> Unit,
) {
    val verb = if (prompt.request.mode == TransferMode.MOVE) "Move" else "Copy"
    val items = if (prompt.itemCount == 1) "1 item" else "${prompt.itemCount} items"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Illogical operation") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Files are selected on both sides, and a file cannot be a destination.")
                Text("$verb $items into ${prompt.targetName}, the folder holding the destination selection?")
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(prompt.request) }) { Text(verb) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private data class FolderStats(
    val directFiles: Int,
    val directFolders: Int,
    val totalFiles: Int,
    val totalFolders: Int,
    val bytes: Long,
)

/** Walks the tree iteratively; recursion would blow the stack on a deep folder. */
private fun scanFolder(context: Context, uri: Uri): FolderStats {
    val direct = Storage.children(context, uri)
    var totalFiles = 0
    var totalFolders = 0
    var bytes = 0L
    val pending = ArrayDeque<FileEntry>()
    pending += direct
    while (pending.isNotEmpty()) {
        val entry = pending.removeLast()
        if (entry.isDirectory) {
            totalFolders += 1
            pending += Storage.children(context, entry.uri)
        } else {
            totalFiles += 1
            bytes += entry.size
        }
    }
    return FolderStats(
        directFiles = direct.count { !it.isDirectory },
        directFolders = direct.count { it.isDirectory },
        totalFiles = totalFiles,
        totalFolders = totalFolders,
        bytes = bytes,
    )
}

@Composable
private fun FolderPropertiesDialog(
    activity: ComponentActivity,
    uri: Uri,
    onDismiss: () -> Unit,
) {
    val entry by produceState<FileEntry?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) { Storage.entry(activity, uri) }
    }
    val stats by produceState<FolderStats?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) { scanFolder(activity, uri) }
    }
    val measured = stats

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(entry?.name ?: uri.lastPathSegment ?: "Folder") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (measured == null) {
                    Text("Measuring folder contents…", style = MaterialTheme.typography.bodyMedium)
                } else {
                    PropertyRow("Contains", "${measured.directFolders} folders, ${measured.directFiles} files")
                    PropertyRow("All files", "${measured.totalFiles}")
                    PropertyRow("All subfolders", "${measured.totalFolders}")
                    PropertyRow("Total size", formatBytes(measured.bytes))
                }
                PropertyRow("Type", if (DriveUris.isDrive(uri)) "Google Drive folder" else "Folder")
                Text(
                    text = uri.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun PropertyRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun readExif(context: Context, uri: Uri): List<Pair<String, String>> {
    val stream = runCatching { Storage.openInput(context, uri) }.getOrNull() ?: return emptyList()
    val exif = stream.use { runCatching { ExifInterface(it) }.getOrNull() } ?: return emptyList()
    val tags = listOf(
        "Taken" to ExifInterface.TAG_DATETIME_ORIGINAL,
        "Camera" to ExifInterface.TAG_MAKE,
        "Model" to ExifInterface.TAG_MODEL,
        "Lens" to ExifInterface.TAG_LENS_MODEL,
        "Exposure" to ExifInterface.TAG_EXPOSURE_TIME,
        "Aperture" to ExifInterface.TAG_F_NUMBER,
        "ISO" to ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        "Focal length" to ExifInterface.TAG_FOCAL_LENGTH,
        "Flash" to ExifInterface.TAG_FLASH,
        "White balance" to ExifInterface.TAG_WHITE_BALANCE,
        "Orientation" to ExifInterface.TAG_ORIENTATION,
        "Software" to ExifInterface.TAG_SOFTWARE,
    )
    val rows = mutableListOf<Pair<String, String>>()
    val width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
    val height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
    if (width > 0 && height > 0) rows += "Dimensions" to "$width × $height"
    tags.forEach { (label, tag) ->
        exif.getAttribute(tag)?.takeIf { it.isNotBlank() }?.let { rows += label to it }
    }
    exif.latLong?.let { rows += "Location" to "${it[0]}, ${it[1]}" }
    return rows
}

@Composable
private fun ImageInfoDialog(
    activity: ComponentActivity,
    entry: FileEntry?,
    uri: Uri,
    onDismiss: () -> Unit,
) {
    val exifRows by produceState<List<Pair<String, String>>?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) { readExif(activity, uri) }
    }
    val rows = exifRows

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(entry?.name ?: uri.lastPathSegment ?: "Image") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                entry?.let {
                    PropertyRow("Size", formatBytes(it.size))
                    it.mimeType?.let { type -> PropertyRow("Type", type) }
                }
                when {
                    rows == null -> Text("Reading metadata…", style = MaterialTheme.typography.bodyMedium)
                    rows.isEmpty() -> Text("No EXIF metadata in this file.", style = MaterialTheme.typography.bodyMedium)
                    else -> rows.forEach { (label, value) -> PropertyRow(label, value) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageViewerScreen(
    activity: ComponentActivity,
    startingUri: Uri,
    directoryUri: Uri?,
    sortOrder: SortOrder,
    onClose: () -> Unit,
) {
    var currentUri by remember(startingUri) { mutableStateOf(startingUri) }
    var stage by remember { mutableStateOf(GalleryStage.SINGLE) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var showMenu by remember { mutableStateOf(false) }
    var showExif by remember { mutableStateOf(false) }
    var imageActionUri by remember { mutableStateOf<Uri?>(null) }
    var listingRefresh by remember { mutableIntStateOf(0) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedImages by remember { mutableStateOf(emptySet<Uri>()) }
    var pendingGalleryDelete by remember { mutableStateOf<Set<Uri>?>(null) }
    val scope = rememberCoroutineScope()

    val images by produceState(initialValue = emptyList<FileEntry>(), directoryUri, startingUri, listingRefresh, sortOrder) {
        value = withContext(Dispatchers.IO) {
            directoryUri?.let { dir ->
                Storage.children(activity, dir)
                    .filter { !it.isDirectory && isImageMimeType(it.mimeType ?: Storage.mimeType(activity, it.uri)) }
                    .sortedWith(comparatorFor(sortOrder))
            }?.takeIf { it.isNotEmpty() }
                ?: listOfNotNull(Storage.entry(activity, startingUri))
        }
    }

    val currentDoc = images.firstOrNull { it.uri == currentUri }
    // The single view intentionally decodes the file at native resolution, with no downsampling.
    val currentBitmap by produceState<ImageBitmap?>(initialValue = null, currentUri, stage) {
        value = if (stage == GalleryStage.SINGLE) {
            withContext(Dispatchers.IO) { loadBitmap(activity, currentUri) }
        } else {
            null
        }
    }

    fun resetTransform() {
        scale = 1f
        offset = Offset.Zero
    }

    fun zoomIn() {
        when (stage) {
            GalleryStage.GRID_SMALL -> stage = GalleryStage.GRID_MEDIUM
            GalleryStage.GRID_MEDIUM -> {
                stage = GalleryStage.SINGLE
                resetTransform()
            }
            GalleryStage.SINGLE -> scale = (scale * 2f).coerceAtMost(MAX_SINGLE_ZOOM)
        }
    }

    fun zoomOut() {
        when (stage) {
            GalleryStage.SINGLE -> if (scale > 1.05f) {
                scale = (scale / 2f).coerceAtLeast(1f)
                offset = Offset.Zero
            } else {
                stage = GalleryStage.GRID_MEDIUM
                resetTransform()
            }
            GalleryStage.GRID_MEDIUM -> stage = GalleryStage.GRID_SMALL
            GalleryStage.GRID_SMALL -> Unit
        }
    }

    fun showRelative(step: Int) {
        if (images.size < 2) return
        val index = images.indexOfFirst { it.uri == currentUri }
        val nextIndex = when {
            index < 0 -> 0
            else -> (index + step + images.size) % images.size
        }
        currentUri = images[nextIndex].uri
        resetTransform()
    }

    fun deleteImages(targets: Set<Uri>) {
        scope.launch {
            withContext(Dispatchers.IO) { targets.forEach { Storage.delete(activity, it) } }
            val remaining = images.filterNot { it.uri in targets }
            selectedImages = emptySet()
            selectionMode = false
            if (remaining.isEmpty()) {
                onClose()
            } else {
                if (currentUri in targets) {
                    val index = images.indexOfFirst { it.uri == currentUri }
                    currentUri = remaining[index.coerceIn(0, remaining.lastIndex)].uri
                }
                listingRefresh += 1
            }
        }
    }

    // The system back gesture returns to the browser rather than leaving the app.
    BackHandler(enabled = true) { onClose() }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AssistChip(
                onClick = onClose,
                label = { Text("File browser", fontSize = 10.sp) },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, Modifier.size(14.dp)) },
                modifier = Modifier.height(28.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (stage == GalleryStage.SINGLE && images.size > 1) {
                    GalleryAction(Icons.AutoMirrored.Filled.ArrowBack, "Previous image") { showRelative(-1) }
                    GalleryAction(Icons.AutoMirrored.Filled.ArrowForward, "Next image") { showRelative(1) }
                }
                if (stage != GalleryStage.SINGLE) {
                    GalleryAction(
                        icon = Icons.Filled.SelectAll,
                        description = "Select images",
                        tint = if (selectionMode) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                    ) {
                        selectionMode = !selectionMode
                        if (!selectionMode) selectedImages = emptySet()
                    }
                }
                GalleryAction(Icons.Filled.Delete, "Delete") {
                    val targets = when {
                        selectionMode && selectedImages.isNotEmpty() -> selectedImages
                        stage == GalleryStage.SINGLE -> setOf(currentUri)
                        else -> emptySet()
                    }
                    if (targets.isNotEmpty()) pendingGalleryDelete = targets
                }
                if (stage == GalleryStage.SINGLE) {
                    GalleryAction(Icons.Filled.ZoomOut, "Zoom out") { zoomOut() }
                    GalleryAction(Icons.Filled.ZoomIn, "Zoom in") { zoomIn() }
                    GalleryAction(Icons.Filled.Info, "Image information") { showExif = true }
                }
                GalleryAction(Icons.Filled.Image, "Toggle gallery view") {
                    stage = if (stage == GalleryStage.SINGLE) GalleryStage.GRID_SMALL else GalleryStage.SINGLE
                    resetTransform()
                }
            }
        }

        Text(
            text = if (selectionMode) {
                "${selectedImages.size} selected"
            } else {
                currentDoc?.name ?: currentUri.lastPathSegment ?: "Image viewer"
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 1.dp),
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            val singleBitmap = currentBitmap
            if (stage == GalleryStage.SINGLE && singleBitmap != null) {
                Image(
                    bitmap = singleBitmap,
                    contentDescription = "Zoomable image",
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(images, currentUri) {
                            detectImageGestures(
                                currentScale = { scale },
                                onTransform = { pan, zoom ->
                                    val next = scale * zoom
                                    if (next < 0.85f) {
                                        stage = GalleryStage.GRID_MEDIUM
                                        scale = 1f
                                        offset = Offset.Zero
                                    } else {
                                        scale = next.coerceIn(1f, MAX_SINGLE_ZOOM)
                                        if (scale > 1f) offset += pan
                                    }
                                },
                                onSwipe = { step -> showRelative(step) },
                            )
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

            if (stage != GalleryStage.SINGLE) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(stage.columns),
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(stage) {
                            detectPinchSteps { step -> if (step > 0) zoomIn() else zoomOut() }
                        },
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(images, key = { it.uri }) { file ->
                        val sizePx = stage.thumbnailPx
                        val lowQuality = stage.lowQuality
                        val thumb by produceState(ThumbnailCache.get(file.uri, sizePx), file.uri, sizePx) {
                            if (value == null) {
                                value = withContext(Dispatchers.IO) {
                                    loadBitmap(activity, file.uri, sizePx, sizePx, lowQuality)
                                        ?.also { ThumbnailCache.put(file.uri, sizePx, it) }
                                }
                            }
                        }
                        val thumbnail = thumb
                        val picked = file.uri in selectedImages
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.combinedClickable(
                                onClick = {
                                    if (selectionMode) {
                                        selectedImages = if (picked) selectedImages - file.uri else selectedImages + file.uri
                                    } else {
                                        currentUri = file.uri
                                        stage = GalleryStage.SINGLE
                                        resetTransform()
                                    }
                                },
                                onLongClick = { imageActionUri = file.uri },
                            ),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (thumbnail != null) {
                                    Image(
                                        bitmap = thumbnail,
                                        contentDescription = file.name,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                } else {
                                    Icon(Icons.Filled.Image, contentDescription = null)
                                }
                                if (selectionMode) {
                                    RadioButton(
                                        selected = picked,
                                        onClick = {
                                            selectedImages = if (picked) selectedImages - file.uri else selectedImages + file.uri
                                        },
                                        modifier = Modifier.align(Alignment.TopStart),
                                    )
                                }
                            }
                            Text(
                                text = file.name,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }

                // Thumbnail modes keep the zoom controls out of the toolbar, tucked into the corner.
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(topStart = 14.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                        .padding(horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GalleryAction(Icons.Filled.ZoomOut, "Zoom out") { zoomOut() }
                    GalleryAction(Icons.Filled.ZoomIn, "Zoom in") { zoomIn() }
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
                    Button(onClick = { stage = GalleryStage.GRID_SMALL; resetTransform(); showMenu = false }) { Text("Show thumbnails") }
                }
            }

            if (showExif) {
                ImageInfoDialog(
                    activity = activity,
                    entry = currentDoc,
                    uri = currentUri,
                    onDismiss = { showExif = false },
                )
            }

            if (imageActionUri != null) {
                AlertDialog(
                    onDismissRequest = { imageActionUri = null },
                    title = { Text("Image actions") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                launchExternalApp(activity, imageActionUri!!, null)
                                imageActionUri = null
                            }) { Text("Open in external app") }
                            Button(onClick = {
                                launchExternalApp(
                                    activity,
                                    imageActionUri!!,
                                    null,
                                    action = Intent.ACTION_EDIT,
                                    title = "Edit image",
                                )
                                imageActionUri = null
                            }) { Text("Edit in external editor") }
                            Button(onClick = {
                                val target = imageActionUri ?: return@Button
                                imageActionUri = null
                                pendingGalleryDelete = setOf(target)
                            }) { Text("Delete") }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { imageActionUri = null }) { Text("Close") }
                    },
                )
            }

            if (pendingGalleryDelete != null) {
                ConfirmDeleteDialog(
                    count = pendingGalleryDelete!!.size,
                    onDismiss = { pendingGalleryDelete = null },
                    onConfirm = {
                        val targets = pendingGalleryDelete!!
                        pendingGalleryDelete = null
                        deleteImages(targets)
                    },
                )
            }
        }
    }
}

@Composable
private fun GalleryAction(
    icon: ImageVector,
    description: String,
    tint: Color = LocalContentColor.current,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(30.dp)) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(17.dp))
    }
}

/**
 * Zoom, pan and swipe share one detector; separate ones would fight over consuming the same drag.
 * [onSwipe] gets -1 for the previous image and +1 for the next.
 */
private suspend fun PointerInputScope.detectImageGestures(
    currentScale: () -> Float,
    onTransform: (pan: Offset, zoom: Float) -> Unit,
    onSwipe: (Int) -> Unit,
) {
    val swipeThreshold = viewConfiguration.touchSlop * 6f
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var travel = Offset.Zero
        var pinched = false
        do {
            val event = awaitPointerEvent()
            val zoom = event.calculateZoom()
            val pan = event.calculatePan()
            if (zoom != 1f) pinched = true
            if (zoom != 1f || pan != Offset.Zero) onTransform(pan, zoom)
            travel += pan
            event.changes.forEach { if (it.positionChanged()) it.consume() }
        } while (event.changes.any { it.pressed })

        val horizontal = abs(travel.x) > abs(travel.y) && abs(travel.x) > swipeThreshold
        if (!pinched && currentScale() <= 1.02f && horizontal) {
            onSwipe(if (travel.x < 0) 1 else -1)
        }
    }
}

private fun loadBitmap(
    activity: ComponentActivity,
    uri: Uri,
    width: Int = 0,
    height: Int = 0,
    lowQuality: Boolean = false,
): ImageBitmap? {
    // Remote bytes are pulled once and decoded from memory; a second stream would re-download.
    if (DriveUris.isDrive(uri)) {
        val bytes = runCatching { Storage.openInput(activity, uri)?.use { it.readBytes() } }.getOrNull() ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = computeInSampleSize(
                bounds.outWidth,
                bounds.outHeight,
                if (width > 0) width else bounds.outWidth,
                if (height > 0) height else bounds.outHeight,
            )
            if (lowQuality) inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
    }

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
                if (lowQuality) inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeStream(stream, null, opts)?.asImageBitmap()
        }
    }
}

/** Keeps decoded grid thumbnails around so scrolling back over them costs nothing. */
private object ThumbnailCache {
    private val cache = object : LruCache<String, ImageBitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 6).toInt().coerceAtLeast(4 * 1024),
    ) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = (value.width * value.height * 4) / 1024
    }

    fun get(uri: Uri, sizePx: Int): ImageBitmap? = cache.get("$uri@$sizePx")

    fun put(uri: Uri, sizePx: Int, bitmap: ImageBitmap) {
        cache.put("$uri@$sizePx", bitmap)
    }
}

/**
 * Reports pinch gestures without swallowing single-finger drags, so a lazy grid keeps scrolling.
 * [onStep] receives +1 to zoom in a stage and -1 to zoom out.
 */
private suspend fun PointerInputScope.detectPinchSteps(onStep: (Int) -> Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var zoom = 1f
        do {
            val event = awaitPointerEvent()
            if (event.changes.count { it.pressed } < 2) continue
            zoom *= event.calculateZoom()
            if (zoom > 1.4f) {
                onStep(1)
                zoom = 1f
            } else if (zoom < 0.72f) {
                onStep(-1)
                zoom = 1f
            }
            event.changes.forEach { if (it.positionChanged()) it.consume() }
        } while (event.changes.any { it.pressed })
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
    state: BrowserPaneState,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    onActivate: () -> Unit,
    onNavigate: (Uri) -> Unit,
    onOpenFile: (Uri) -> Unit,
    onOpenWith: (Uri) -> Unit,
    onShowProperties: (Uri) -> Unit,
    onMoveUp: () -> Unit,
    onSelectionChange: (Set<Uri>) -> Unit,
    multiSelect: Boolean,
    selectionOutline: Color,
    showHiddenFiles: Boolean,
    showTrashFiles: Boolean,
    sortOptions: SortOptions,
) {
    val context = LocalContext.current
    val currentUri = state.current ?: state.root
    val listing by produceState<DirectoryListing?>(initialValue = null, currentUri, state.refreshKey, showHiddenFiles, showTrashFiles, sortOptions) {
        value = currentUri?.let { uri ->
            withContext(Dispatchers.IO) {
                DirectoryListing(
                    name = Storage.entry(context, uri)?.name,
                    files = sortEntries(
                        Storage.children(context, uri)
                            .filter { file -> shouldDisplayDocument(file, showHiddenFiles, showTrashFiles) },
                        sortOptions,
                    ),
                )
            }
        }
    }
    val files = listing?.files ?: emptyList()
    val isLoading = currentUri != null && listing == null

    Column(
        modifier = modifier
            .fillMaxHeight()
            .padding(2.dp)
            .border(
                width = 2.dp,
                color = if (isActive) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onActivate),
    ) {
        FlowRow(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (state.root != null && currentUri != null && currentUri != state.root) {
                AssistChip(onClick = onMoveUp, label = { Text("cd ..", fontSize = 12.sp) })
            }
        }

        listing?.name?.let { folderName ->
            Text(
                text = folderName,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium,
            )
        }

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
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(files, key = { it.uri }) { file ->
                    val selected = file.uri in state.selected
                    val icon = if (file.isDirectory) Icons.Filled.FolderOpen else Icons.AutoMirrored.Filled.InsertDriveFile
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .then(
                                if (selected) {
                                    Modifier.border(2.dp, selectionOutline, RoundedCornerShape(8.dp))
                                } else {
                                    Modifier
                                }
                            )
                            .combinedClickable(
                                onClick = {
                                    onActivate()
                                    onSelectionChange(
                                        when {
                                            multiSelect && selected -> state.selected - file.uri
                                            multiSelect -> state.selected + file.uri
                                            selected -> emptySet()
                                            else -> setOf(file.uri)
                                        }
                                    )
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
                                    onSelectionChange(setOf(file.uri))
                                },
                            )
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = icon,
                                contentDescription = if (file.isDirectory) "Folder" else "File",
                                modifier = Modifier.size(15.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = file.name,
                                modifier = Modifier
                                    .padding(start = 6.dp)
                                    .combinedClickable(
                                        onClick = {
                                            onActivate()
                                            if (file.isDirectory) onNavigate(file.uri) else onOpenFile(file.uri)
                                        },
                                        onLongClick = {
                                            onActivate()
                                            if (file.isDirectory) onShowProperties(file.uri) else onOpenWith(file.uri)
                                        },
                                    ),
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 12.sp,
                                lineHeight = 15.sp,
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
