package org.gpdresearch.hyperbrowser

import android.Manifest
import android.app.WallpaperManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.common.api.ApiException
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

private enum class Pane { LEFT, RIGHT }
private enum class TransferDirection { LEFT_TO_RIGHT, RIGHT_TO_LEFT }
private enum class TransferMode { COPY, MOVE }

private enum class AppTheme(val label: String) {
    LIGHT("Light"),
    INVERTED("Inverted"),
    HACKER("Hacker"),
}

private val MATRIX_GREEN = Color(0xFF00FF41)
private val MATRIX_DIM = Color(0xFF00B32D)

/**
 * Every slot is filled in: Material's defaults for the untouched ones are purple-tinted, which is
 * what used to leak into dialogs and chips regardless of the chosen theme.
 */
private fun colorSchemeFor(theme: AppTheme) = when (theme) {
    AppTheme.LIGHT -> lightColorScheme()
    AppTheme.INVERTED -> darkColorScheme(
        background = Color.Black,
        onBackground = Color.White,
        surface = Color.Black,
        onSurface = Color.White,
        surfaceVariant = Color(0xFF1C1C1C),
        onSurfaceVariant = Color(0xFFDDDDDD),
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color(0xFF0D0D0D),
        surfaceContainer = Color(0xFF141414),
        surfaceContainerHigh = Color(0xFF1A1A1A),
        surfaceContainerHighest = Color(0xFF212121),
        inverseSurface = Color.White,
        inverseOnSurface = Color.Black,
        primary = Color.White,
        onPrimary = Color.Black,
        primaryContainer = Color(0xFF2B2B2B),
        onPrimaryContainer = Color.White,
        secondary = Color.White,
        onSecondary = Color.Black,
        secondaryContainer = Color(0xFF2B2B2B),
        onSecondaryContainer = Color.White,
        tertiary = Color.White,
        onTertiary = Color.Black,
        tertiaryContainer = Color(0xFF2B2B2B),
        onTertiaryContainer = Color.White,
        outline = Color(0xFF8C8C8C),
        outlineVariant = Color(0xFF3A3A3A),
        error = Color(0xFFFF6B6B),
        onError = Color.Black,
        errorContainer = Color(0xFF3A1212),
        onErrorContainer = Color(0xFFFFB4AB),
        scrim = Color.Black,
    )
    AppTheme.HACKER -> darkColorScheme(
        background = Color.Black,
        onBackground = MATRIX_GREEN,
        surface = Color.Black,
        onSurface = MATRIX_GREEN,
        surfaceVariant = Color(0xFF071A0C),
        onSurfaceVariant = MATRIX_DIM,
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color(0xFF030D06),
        surfaceContainer = Color(0xFF05140A),
        surfaceContainerHigh = Color(0xFF071A0C),
        surfaceContainerHighest = Color(0xFF0A2410),
        inverseSurface = MATRIX_GREEN,
        inverseOnSurface = Color.Black,
        primary = MATRIX_GREEN,
        onPrimary = Color.Black,
        primaryContainer = Color(0xFF0A2410),
        onPrimaryContainer = MATRIX_GREEN,
        secondary = MATRIX_GREEN,
        onSecondary = Color.Black,
        secondaryContainer = Color(0xFF0A2410),
        onSecondaryContainer = MATRIX_GREEN,
        tertiary = MATRIX_GREEN,
        onTertiary = Color.Black,
        tertiaryContainer = Color(0xFF0A2410),
        onTertiaryContainer = MATRIX_GREEN,
        outline = MATRIX_DIM,
        outlineVariant = Color(0xFF0F3A18),
        error = Color(0xFFFF5252),
        onError = Color.Black,
        errorContainer = Color(0xFF2A0A0A),
        onErrorContainer = Color(0xFFFF8A80),
        scrim = Color.Black,
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

/**
 * RecordingCanvas throws "trying to draw too large bitmap" past 100 MB, and GPUs refuse textures
 * wider than their limit, so a 30+ MP photo would crash the viewer on draw. 16 MP keeps a decoded
 * ARGB_8888 frame near 64 MB, which every device can both allocate and draw.
 */
private const val MAX_DRAWABLE_PIXELS = 16_000_000L
private const val MAX_TEXTURE_EDGE = 8192

/**
 * How much finer than the viewport a fitted overview is decoded. Screen pixels are not the
 * whole story: the fitted image is what a small zoom magnifies before a sharper tile has been
 * decoded, and a downscaled render loses detail to sampling that this headroom buys back.
 */
private const val OVERVIEW_DETAIL = 2

/** High enough to reach 1:1 pixels on a gigapixel source; tiles keep the memory cost flat. */
private const val MAX_SINGLE_ZOOM = 64f

/**
 * A row tap turns into a selection one double-tap timeout (300ms) after the finger lifts, so a
 * touch older than this has had its window and the selection it makes has landed.
 */
private const val SELECTION_SETTLE_MS = 450L

private const val INTERNAL_STORAGE_LABEL = "Internal Storage"

private enum class LayoutMode(val label: String) {
    PHONE("Phone"),
    TABLET_BALANCED("Tablet"),
}

/**
 * The layout modes used to differ only in a few dp of strip width, which was invisible in practice.
 * Each mode now drives control size, list density and the size of the floating selection preview.
 */
private data class LayoutMetrics(
    val stripWidth: Dp,
    val previewSize: Dp,
    val commandHeight: Dp,
    val commandIcon: Dp,
    val commandLabel: TextUnit,
    val rowIcon: Dp,
    val rowFontSize: TextUnit,
    val rowPadding: Dp,
    val paneHeaderSize: TextUnit,
    val activePaneWeight: Float,
)

private fun metricsFor(mode: LayoutMode): LayoutMetrics = when (mode) {
    LayoutMode.PHONE -> LayoutMetrics(
        stripWidth = 56.dp,
        previewSize = 192.dp,
        commandHeight = 60.dp,
        commandIcon = 20.dp,
        commandLabel = 9.sp,
        rowIcon = 14.dp,
        rowFontSize = 12.sp,
        rowPadding = 4.dp,
        paneHeaderSize = 12.sp,
        activePaneWeight = 1f,
    )
    // Only the controls grow with the screen; the lists keep phone density, so the extra dp of a
    // tablet go into more visible rows rather than into the same rows written larger.
    LayoutMode.TABLET_BALANCED -> LayoutMetrics(
        stripWidth = 80.dp,
        previewSize = 288.dp,
        commandHeight = 72.dp,
        commandIcon = 26.dp,
        commandLabel = 11.sp,
        rowIcon = 14.dp,
        rowFontSize = 12.sp,
        rowPadding = 4.dp,
        paneHeaderSize = 12.sp,
        activePaneWeight = 1f,
    )
}

/** Best guess for a first run; the user can still pick any mode in settings. */
private fun defaultLayoutMode(smallestWidthDp: Int): LayoutMode = when {
    smallestWidthDp >= 600 -> LayoutMode.TABLET_BALANCED
    else -> LayoutMode.PHONE
}

private data class FileDisplayOptions(
    val showHiddenFiles: Boolean = false,
    val showTrashFiles: Boolean = false,
)

/** Flattened to strings: label, then one entry per trashed triple and per created URI. */
private val UndoRecordSaver = listSaver<UndoRecord?, String>(
    save = { record ->
        if (record == null) {
            emptyList()
        } else {
            listOf(record.label, record.trashed.size.toString()) +
                record.trashed.flatMap {
                    listOf(it.originalParent.toString(), it.originalName, it.trashedUri.toString())
                } +
                record.created.map(Uri::toString)
        }
    },
    restore = { stored ->
        if (stored.isEmpty()) {
            null
        } else {
            val trashedCount = stored[1].toInt()
            val trashed = (0 until trashedCount).map { index ->
                val base = 2 + index * 3
                TrashedItem(
                    originalParent = Uri.parse(stored[base]),
                    originalName = stored[base + 1],
                    trashedUri = Uri.parse(stored[base + 2]),
                )
            }
            UndoRecord(
                label = stored[0],
                trashed = trashed,
                created = stored.drop(2 + trashedCount * 3).map(Uri::parse),
            )
        }
    },
)

private val FileDisplayOptionsSaver = listSaver<FileDisplayOptions, Boolean>(
    save = { listOf(it.showHiddenFiles, it.showTrashFiles) },
    restore = { FileDisplayOptions(showHiddenFiles = it[0], showTrashFiles = it[1]) },
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

private val SortOptionsSaver = listSaver<SortOptions, String>(
    save = { listOf(it.split.toString(), it.folderOrder.name, it.fileOrder.name) },
    restore = {
        SortOptions(
            split = it[0].toBoolean(),
            folderOrder = SortOrder.valueOf(it[1]),
            fileOrder = SortOrder.valueOf(it[2]),
        )
    },
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
private const val LAYOUT_PREF = "layout_mode"
private const val BACKGROUND_DIM_PREF = "background_dim"

/** Keeps the browsed folder and the current selection across a rotation. */
private val PaneStateSaver = listSaver<BrowserPaneState, String>(
    save = { state ->
        listOf(state.root?.toString().orEmpty(), state.current?.toString().orEmpty()) +
            state.selected.map { it.toString() }
    },
    restore = { stored ->
        BrowserPaneState(
            root = stored[0].takeIf { it.isNotEmpty() }?.let(Uri::parse),
            current = stored[1].takeIf { it.isNotEmpty() }?.let(Uri::parse),
            selected = stored.drop(2).map(Uri::parse).toSet(),
        )
    },
)

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

private data class BulkFolderPrompt(
    val request: TransferRequest,
    val folderNames: List<String>,
    val fileCount: Int,
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

    /** Several folders at once can bury a destination, so they are listed before anything moves. */
    data class BulkFolders(val prompt: BulkFolderPrompt) : TransferPlan
}

private data class SelectionInfo(
    val title: String,
    val kind: String,
    val sizeLabel: String,
    val path: String,
    val details: String,
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
    val smallestWidthDp = LocalConfiguration.current.smallestScreenWidthDp
    var leftPane by rememberSaveable(stateSaver = PaneStateSaver) { mutableStateOf(loadPaneState(prefs, PANE_LEFT)) }
    var rightPane by rememberSaveable(stateSaver = PaneStateSaver) { mutableStateOf(loadPaneState(prefs, PANE_RIGHT)) }
    var activePane by rememberSaveable { mutableStateOf(Pane.LEFT) }
    var transferDirection by rememberSaveable { mutableStateOf(TransferDirection.LEFT_TO_RIGHT) }
    var layoutMode by rememberSaveable {
        val stored = prefs.getString(LAYOUT_PREF, null)
        mutableStateOf(LayoutMode.entries.firstOrNull { it.name == stored } ?: defaultLayoutMode(smallestWidthDp))
    }
    var pendingRequest by remember { mutableStateOf<TransferRequest?>(null) }
    var pendingFolderTransfer by remember { mutableStateOf<FolderTransferPrompt?>(null) }
    var pendingFilesTransfer by remember { mutableStateOf<FilesIntoFolderPrompt?>(null) }
    var pendingBulkFolders by remember { mutableStateOf<BulkFolderPrompt?>(null) }
    var reversePrompt by remember { mutableStateOf<TransferMode?>(null) }
    var pendingDelete by remember { mutableStateOf<DeletePlan?>(null) }
    // Saveable: a rotation or resize would otherwise strand the last operation with no way back.
    var undoRecord by rememberSaveable(stateSaver = UndoRecordSaver) { mutableStateOf<UndoRecord?>(null) }
    var lastRowTouchAt by remember { mutableLongStateOf(0L) }
    var propertiesUri by remember { mutableStateOf<Uri?>(null) }
    // Saved, so rotating while an image is open comes back to the gallery instead of the file tree.
    var galleryUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var galleryDirectory by rememberSaveable { mutableStateOf<Uri?>(null) }
    // A chosen image opens on itself; a chosen folder opens on its contents.
    var galleryStage by rememberSaveable { mutableStateOf(GalleryStage.SINGLE) }
    // Which pane the gallery was opened from, so closing it puts the selection back there.
    var galleryPane by rememberSaveable { mutableStateOf(Pane.LEFT) }
    var showLayoutSettings by rememberSaveable { mutableStateOf(false) }
    var fileDisplayOptions by rememberSaveable(stateSaver = FileDisplayOptionsSaver) { mutableStateOf(FileDisplayOptions()) }
    var sortOptions by rememberSaveable(stateSaver = SortOptionsSaver) { mutableStateOf(SortOptions()) }
    var gallerySort by rememberSaveable { mutableStateOf(SortOrder.MODIFIED_NEWEST) }
    var showSortSettings by rememberSaveable { mutableStateOf(false) }
    var selectionPreviewVisible by rememberSaveable { mutableStateOf(true) }
    var selectionPreviewOffset by remember { mutableStateOf(Offset.Zero) }
    // The image whose "set as" dialog is open, and the one currently behind the file tree.
    var setAsUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var background by remember { mutableStateOf<ImageBitmap?>(null) }
    var dimBackground by rememberSaveable { mutableStateOf(prefs.getBoolean(BACKGROUND_DIM_PREF, true)) }
    LaunchedEffect(Unit) {
        background = withContext(Dispatchers.IO) { AppBackground.load(activity)?.asImageBitmap() }
    }
    var renameTarget by rememberSaveable { mutableStateOf<Uri?>(null) }
    var renameValue by rememberSaveable { mutableStateOf("") }
    var newFolderParent by rememberSaveable { mutableStateOf<Uri?>(null) }

    var showLeftPicker by rememberSaveable { mutableStateOf(false) }
    var showRightPicker by rememberSaveable { mutableStateOf(false) }
    var multiSelect by rememberSaveable { mutableStateOf(false) }
    var appTheme by rememberSaveable {
        val stored = prefs.getString(THEME_PREF, null)
        mutableStateOf(AppTheme.entries.firstOrNull { it.name == stored } ?: AppTheme.LIGHT)
    }
    val metrics = metricsFor(layoutMode)

    val scope = rememberCoroutineScope()
    // Single-item commands follow the selection itself; only transfers care about the arrow.
    // Read through the states rather than a captured value, so a command tapped before the
    // recomposition that follows a selection still acts on what is selected now. Only the pane
    // the user is in counts: falling back to the other pane would aim a delete at whatever was
    // left selected over there.
    fun commandPane(): BrowserPaneState = if (activePane == Pane.LEFT) leftPane else rightPane

    fun transferPanes(): Pair<BrowserPaneState, BrowserPaneState> =
        if (transferDirection == TransferDirection.LEFT_TO_RIGHT) leftPane to rightPane else rightPane to leftPane

    // A row tap only becomes a selection once the double-tap window has passed. A command tapped
    // inside that window would otherwise run against the previous selection, so every command
    // waits the rest of the window out before reading one.
    suspend fun awaitSelectionSettled() {
        val pending = SELECTION_SETTLE_MS - (SystemClock.uptimeMillis() - lastRowTouchAt)
        if (pending > 0) delay(pending)
    }

    val commandState = commandPane()
    val selected = commandState.selected
    val selectedFile = selected.singleOrNull()
    val selectedMimeType by produceState(initialValue = "", selectedFile) {
        value = selectedFile?.let { uri -> withContext(Dispatchers.IO) { Storage.mimeType(activity, uri) } } ?: ""
    }
    val isImageSelected = selectedMimeType.startsWith("image/")
    val isDirectorySelected by produceState(initialValue = false, selectedFile) {
        value = selectedFile?.let { uri ->
            withContext(Dispatchers.IO) { Storage.entry(activity, uri)?.isDirectory == true }
        } ?: false
    }
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

    // Both selections are dropped: after a write their URIs may point at items that no longer exist.
    fun refreshPanesAfterWrite() {
        leftPane = leftPane.copy(refreshKey = leftPane.refreshKey + 1, selected = emptySet())
        rightPane = rightPane.copy(refreshKey = rightPane.refreshKey + 1, selected = emptySet())
    }

    fun commitRename() {
        val target = renameTarget ?: return
        val newName = renameValue.trim()
        renameTarget = null
        renameValue = ""
        if (newName.isEmpty()) return
        scope.launch {
            val previous = withContext(Dispatchers.IO) { Storage.entry(activity, target)?.name }
            if (previous == newName) return@launch
            val renamed = withContext(Dispatchers.IO) { Storage.rename(activity, target, newName) }
            if (renamed == null) {
                Toast.makeText(activity, "Could not rename to \"$newName\"", Toast.LENGTH_LONG).show()
                return@launch
            }
            fun follow(state: BrowserPaneState) = state.copy(
                refreshKey = state.refreshKey + 1,
                selected = if (target in state.selected) state.selected - target + renamed else state.selected,
            )
            leftPane = follow(leftPane)
            rightPane = follow(rightPane)
        }
    }

    fun startNewFolder() {
        val active = if (activePane == Pane.LEFT) leftPane else rightPane
        val parent = active.current ?: active.root
        if (parent == null) {
            Toast.makeText(activity, "Choose a folder first", Toast.LENGTH_SHORT).show()
            return
        }
        newFolderParent = parent
    }

    fun createFolder(parent: Uri, name: String) {
        scope.launch {
            val created = withContext(Dispatchers.IO) { Storage.createFolder(activity, parent, name) }
            if (created == null) {
                Toast.makeText(activity, "Could not create \"$name\"", Toast.LENGTH_LONG).show()
                return@launch
            }
            // Either pane can be showing the parent, so both are refreshed.
            leftPane = leftPane.copy(refreshKey = leftPane.refreshKey + 1)
            rightPane = rightPane.copy(refreshKey = rightPane.refreshKey + 1)
        }
    }

    fun startRename() {
        if (renameTarget != null) {
            commitRename()
            return
        }
        scope.launch {
            awaitSelectionSettled()
            val target = commandPane().selected.singleOrNull()
            if (target == null) {
                Toast.makeText(activity, "Select a single item to rename", Toast.LENGTH_SHORT).show()
                return@launch
            }
            renameValue = withContext(Dispatchers.IO) { Storage.entry(activity, target)?.name }
                ?: target.lastPathSegment
                ?: return@launch
            renameTarget = target
        }
    }

    fun runTransfer(request: TransferRequest) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { executeTransfer(activity, request) }
            val verb = if (request.mode == TransferMode.MOVE) "move" else "copy"
            undoRecord = UndoRecord(verb, result.trashed, result.created).takeIf { !it.isEmpty }
            refreshPanesAfterWrite()
            if (result.failures > 0) {
                Toast.makeText(activity, failureMessage(result.failures, "transferred"), Toast.LENGTH_LONG).show()
            }
        }
    }

    fun runUndo() {
        val record = undoRecord ?: return
        undoRecord = null
        scope.launch {
            val failures = withContext(Dispatchers.IO) { undoOperation(activity, record) }
            refreshPanesAfterWrite()
            val message = if (failures > 0) {
                failureMessage(failures, "restored")
            } else {
                "Undid the last ${record.label}"
            }
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
        }
    }

    fun startDelete() {
        scope.launch {
            awaitSelectionSettled()
            val items = commandPane().selected
            if (items.isEmpty()) {
                Toast.makeText(activity, "Select a file or folder to delete", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val plan = withContext(Dispatchers.IO) { buildDeletePlan(activity, items) }
            if (plan.entries.isEmpty()) {
                Toast.makeText(activity, "Nothing left to delete", Toast.LENGTH_SHORT).show()
                return@launch
            }
            pendingDelete = plan
        }
    }

    fun runDelete(plan: DeletePlan) {
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { deleteItems(activity, plan.uris) }
            undoRecord = UndoRecord("delete", trashed = outcome.trashed).takeIf { !it.isEmpty }
            refreshPanesAfterWrite()
            if (outcome.failures > 0) {
                Toast.makeText(activity, failureMessage(outcome.failures, "deleted"), Toast.LENGTH_LONG).show()
            }
        }
    }

    fun startOperation(mode: TransferMode, reversed: Boolean = false) {
        scope.launch {
            awaitSelectionSettled()
            val (from, to) = transferPanes()
            val source = if (reversed) to else from
            val target = if (reversed) from else to
            when (val plan = withContext(Dispatchers.IO) { planTransfer(activity, source, target, mode) }) {
                TransferPlan.Staged -> {
                    val sourceDir = source.current ?: source.root ?: return@launch
                    val targetDir = target.current ?: target.root ?: return@launch
                    val request = TransferRequest(
                        sourceDir = sourceDir,
                        targetDir = targetDir,
                        selected = source.selected,
                        wholeDirectory = source.selected.isEmpty(),
                        mode = mode,
                    )
                    if (request.wholeDirectory) pendingRequest = request else runTransfer(request)
                }
                TransferPlan.ReverseSuggested -> reversePrompt = mode
                is TransferPlan.FolderIntoFolder -> pendingFolderTransfer = plan.prompt
                is TransferPlan.FilesIntoFolder -> pendingFilesTransfer = plan.prompt
                is TransferPlan.BulkFolders -> pendingBulkFolders = plan.prompt
            }
        }
    }

    fun openGallery(uri: Uri, directory: Uri?, stage: GalleryStage = GalleryStage.SINGLE, pane: Pane = activePane) {
        galleryPane = pane
        galleryDirectory = directory
        galleryStage = stage
        galleryUri = uri
    }

    /**
     * Returns from the gallery onto whichever image was last on screen: it becomes the pane's
     * selection, which is what the list then scrolls back to, so a swipe through a folder does
     * not land the user back at the file they started from.
     */
    fun closeGallery(shown: Uri?) {
        galleryUri = null
        val target = shown ?: return
        activePane = galleryPane
        if (galleryPane == Pane.LEFT) {
            if (leftPane.current == galleryDirectory) leftPane = leftPane.copy(selected = setOf(target))
        } else {
            if (rightPane.current == galleryDirectory) rightPane = rightPane.copy(selected = setOf(target))
        }
    }

    fun openWith(uri: Uri) {
        scope.launch {
            val mime = withContext(Dispatchers.IO) { Storage.mimeType(activity, uri) }
            launchExternalApp(activity, uri, mime)
        }
    }

    fun openFileTarget(uri: Uri, directory: Uri?, pane: Pane) {
        scope.launch {
            val mime = withContext(Dispatchers.IO) { Storage.mimeType(activity, uri) }
            if (mime.startsWith("image/")) {
                openGallery(uri, directory, pane = pane)
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
            if (galleryUri != null) {
                ImageViewerScreen(
                    activity = activity,
                    startingUri = galleryUri!!,
                    directoryUri = galleryDirectory,
                    startStage = galleryStage,
                    sortOrder = gallerySort,
                    onUndoable = { record -> undoRecord = record },
                    onClose = { shown -> closeGallery(shown) },
                )
            } else {
                // Drawn behind everything in the file tree, dimmed so rows stay readable over it.
                background?.let { image ->
                    Image(
                        bitmap = image,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alpha = if (dimBackground) BACKGROUND_ALPHA else 1f,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                // The strip runs the full height beside everything else, so the root and direction
                // buttons sit centred over their own panes and the strip has room to grow.
                Row(modifier = Modifier.fillMaxSize()) {
                    CommandStrip(
                        metrics = metrics,
                        onCopy = { startOperation(TransferMode.COPY) },
                        onMove = { startOperation(TransferMode.MOVE) },
                        onDelete = { startDelete() },
                        onNewFolder = { startNewFolder() },
                        onRename = { startRename() },
                        renameActive = renameTarget != null,
                        onGallery = {
                            val target = selectedFile
                            when {
                                // An image opens in view mode among the rest of its folder.
                                target != null && isImageSelected ->
                                    openGallery(target, commandState.current)
                                // A folder — chosen, or just the one the pane is showing —
                                // opens as a grid of what is in it.
                                target != null && isDirectorySelected ->
                                    openGallery(target, target, GalleryStage.GRID_SMALL)
                                else -> commandState.current?.let { directory ->
                                    openGallery(directory, directory, GalleryStage.GRID_SMALL)
                                }
                            }
                        },
                        undoLabel = undoRecord?.label,
                        onUndo = { runUndo() },
                        multiSelect = multiSelect,
                        // Leaving multi mode doubles as "I changed my mind" and drops the payload.
                        onToggleMulti = {
                            if (multiSelect) {
                                multiSelect = false
                                // The row tap that prompted this may still be inside its
                                // double-tap window; clearing before it lands leaves its item
                                // selected as though the mode had never been left.
                                scope.launch {
                                    awaitSelectionSettled()
                                    leftPane = leftPane.copy(selected = emptySet())
                                    rightPane = rightPane.copy(selected = emptySet())
                                }
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

                    Column(modifier = Modifier.weight(1f)) {
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

                        Box(modifier = Modifier.weight(1f)) {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                DirectoryPane(
                                    state = leftPane,
                                    isActive = activePane == Pane.LEFT,
                                    modifier = Modifier.weight(if (activePane == Pane.LEFT) metrics.activePaneWeight else 1f),
                                    onActivate = { activePane = Pane.LEFT },
                                    onNavigate = { directory ->
                                        commitRename()
                                        leftPane = leftPane.copy(current = directory, selected = emptySet())
                                    },
                                    onOpenFile = { uri -> openFileTarget(uri, leftPane.current ?: leftPane.root, Pane.LEFT) },
                                    onOpenWith = { uri -> openWith(uri) },
                                    onShowProperties = { uri -> propertiesUri = uri },
                                    onMoveUp = {
                                        commitRename()
                                        scope.launch {
                                            val parent = withContext(Dispatchers.IO) {
                                                leftPane.current?.let { Storage.parent(activity, it) }
                                            }
                                            if (parent != null) {
                                                leftPane = leftPane.copy(current = parent, selected = emptySet())
                                            }
                                        }
                                    },
                                    onSelectionChange = { selectedSet ->
                                        // Moving to another file is the second way to commit a rename.
                                        if (renameTarget != null && renameTarget !in selectedSet) commitRename()
                                        leftPane = leftPane.copy(selected = selectedSet)
                                        // One selection at a time across the two panes, so a command
                                        // never has two candidate targets to choose between.
                                        if (selectedSet.isNotEmpty() && rightPane.selected.isNotEmpty()) {
                                            rightPane = rightPane.copy(selected = emptySet())
                                        }
                                    },
                                    onRowTouched = { lastRowTouchAt = SystemClock.uptimeMillis() },
                                    multiSelect = multiSelect,
                                    selectionOutline = selectionOutlineColor(appTheme),
                                    showHiddenFiles = fileDisplayOptions.showHiddenFiles,
                                    showTrashFiles = fileDisplayOptions.showTrashFiles,
                                    sortOptions = sortOptions,
                                    metrics = metrics,
                                    renameTarget = renameTarget,
                                    renameValue = renameValue,
                                    onRenameValueChange = { renameValue = it },
                                    onCommitRename = { commitRename() },
                                )

                                DirectoryPane(
                                    state = rightPane,
                                    isActive = activePane == Pane.RIGHT,
                                    modifier = Modifier.weight(if (activePane == Pane.RIGHT) metrics.activePaneWeight else 1f),
                                    onActivate = { activePane = Pane.RIGHT },
                                    onNavigate = { directory ->
                                        commitRename()
                                        rightPane = rightPane.copy(current = directory, selected = emptySet())
                                    },
                                    onOpenFile = { uri -> openFileTarget(uri, rightPane.current ?: rightPane.root, Pane.RIGHT) },
                                    onOpenWith = { uri -> openWith(uri) },
                                    onShowProperties = { uri -> propertiesUri = uri },
                                    onMoveUp = {
                                        commitRename()
                                        scope.launch {
                                            val parent = withContext(Dispatchers.IO) {
                                                rightPane.current?.let { Storage.parent(activity, it) }
                                            }
                                            if (parent != null) {
                                                rightPane = rightPane.copy(current = parent, selected = emptySet())
                                            }
                                        }
                                    },
                                    onSelectionChange = { selectedSet ->
                                        if (renameTarget != null && renameTarget !in selectedSet) commitRename()
                                        rightPane = rightPane.copy(selected = selectedSet)
                                        if (selectedSet.isNotEmpty() && leftPane.selected.isNotEmpty()) {
                                            leftPane = leftPane.copy(selected = emptySet())
                                        }
                                    },
                                    onRowTouched = { lastRowTouchAt = SystemClock.uptimeMillis() },
                                    multiSelect = multiSelect,
                                    selectionOutline = selectionOutlineColor(appTheme),
                                    showHiddenFiles = fileDisplayOptions.showHiddenFiles,
                                    showTrashFiles = fileDisplayOptions.showTrashFiles,
                                    sortOptions = sortOptions,
                                    metrics = metrics,
                                    renameTarget = renameTarget,
                                    renameValue = renameValue,
                                    onRenameValueChange = { renameValue = it },
                                    onCommitRename = { commitRename() },
                                )
                            }

                            if (selectedFile != null && isImageSelected && galleryUri == null && selectionPreviewVisible) {
                                SelectionThumbnail(
                                    activity = activity,
                                    uri = selectedFile,
                                    size = metrics.previewSize,
                                    offset = selectionPreviewOffset,
                                    onOffsetChange = { selectionPreviewOffset = it },
                                    onTap = { setAsUri = selectedFile },
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
                            onView = { selectedFile?.let { if (isImageSelected) openGallery(it, commandState.current) } },
                        )
                    }
                }
            }
            }
        }

        // Dialogs live inside the theme; outside it they fell back to Material's default palette.
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
                    startOperation(mode, reversed = true)
                },
            )
        }

        val setAsTarget = setAsUri
        if (setAsTarget != null) {
            SetImageAsDialog(
                hasBackground = background != null,
                dim = dimBackground,
                onDimChange = { dim ->
                    dimBackground = dim
                    prefs.edit().putBoolean(BACKGROUND_DIM_PREF, dim).apply()
                },
                onDismiss = { setAsUri = null },
                onClearBackground = {
                    setAsUri = null
                    background = null
                    scope.launch { withContext(Dispatchers.IO) { AppBackground.clear(activity) } }
                },
                onApply = { target ->
                    setAsUri = null
                    scope.launch {
                        val outcome = withContext(Dispatchers.IO) {
                            applyImageAs(activity, setAsTarget, target)
                        }
                        if (target.app) {
                            background = withContext(Dispatchers.IO) {
                                AppBackground.load(activity)?.asImageBitmap()
                            }
                        }
                        Toast.makeText(activity, outcome, Toast.LENGTH_SHORT).show()
                    }
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

        if (pendingBulkFolders != null) {
            ConfirmBulkFolderTransferDialog(
                prompt = pendingBulkFolders!!,
                onDismiss = { pendingBulkFolders = null },
                onConfirm = { request ->
                    pendingBulkFolders = null
                    runTransfer(request)
                },
            )
        }

        val deletePlan = pendingDelete
        if (deletePlan != null) {
            ConfirmDeletePlanDialog(
                plan = deletePlan,
                onDismiss = { pendingDelete = null },
                onConfirm = {
                    pendingDelete = null
                    runDelete(deletePlan)
                },
            )
        }

        val folderParent = newFolderParent
        if (folderParent != null) {
            NewFolderDialog(
                onDismiss = { newFolderParent = null },
                onConfirm = { name ->
                    newFolderParent = null
                    createFolder(folderParent, name)
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
                    prefs.edit().putString(LAYOUT_PREF, mode.name).apply()
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

private fun allFilesAccessGranted(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

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

/** Enough of the image to see behind the file tree, dim enough for rows to stay legible. */
private const val BACKGROUND_ALPHA = 0.15f

/**
 * Puts the image where the dialog asked for it. Both destinations take a decoded bitmap rather
 * than the file: RAW and TIFF have no bytes the wallpaper service could read, and the file itself
 * may be on a card or in Drive.
 */
private fun applyImageAs(activity: ComponentActivity, uri: Uri, target: WallpaperTarget): String {
    val (width, height) = Wallpapers.desiredSize(activity)
    val bitmap = loadBitmap(activity, uri, width, height)?.asAndroidBitmap()
        ?: return "That image could not be read"
    val done = mutableListOf<String>()
    if (target.app && AppBackground.store(activity, bitmap)) done += "file browser"
    if ((target.home || target.lock) && Wallpapers.apply(activity, bitmap, target.home, target.lock)) {
        if (target.home) done += "home screen"
        if (target.lock) done += "lock screen"
    }
    return if (done.isEmpty()) "Setting the image failed" else "Set as ${done.joinToString(", ")} background"
}

/**
 * Offers the image to the device's own wallpaper handling: the "Set as" targets, plus the platform
 * cropper, which on stock Android is the only thing that can set a wallpaper from a file.
 */
private fun setAsWallpaper(activity: ComponentActivity, uri: Uri, mimeType: String?) {
    val shared = if (DriveUris.isDrive(uri)) null else shareableUri(activity, uri)
    if (shared == null) {
        Toast.makeText(activity, "Copy this image to local storage to set it as wallpaper", Toast.LENGTH_SHORT).show()
        return
    }
    val type = mimeType?.takeIf { it.isNotBlank() } ?: "image/*"
    val attach = Intent(Intent.ACTION_ATTACH_DATA).apply {
        setDataAndType(shared, type)
        putExtra("mimeType", type)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    // Throws on anything the cropper cannot read, so its absence is a normal outcome.
    val cropper = runCatching {
        WallpaperManager.getInstance(activity).getCropAndSetWallpaperIntent(shared)
    }.getOrNull()?.apply { addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    val chooser = Intent.createChooser(attach, "Set as wallpaper").apply {
        if (cropper != null) putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(cropper))
    }
    runCatching { activity.startActivity(chooser) }
        .onFailure { Toast.makeText(activity, "No app on this device sets wallpapers", Toast.LENGTH_SHORT).show() }
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

    if (sourceFolders.size > 1) {
        val targetDir = targetFolders.singleOrNull()?.uri ?: target.current ?: target.root
        val sourceDir = source.current ?: source.root
        if (targetDir != null && sourceDir != null) {
            return TransferPlan.BulkFolders(
                BulkFolderPrompt(
                    request = TransferRequest(
                        sourceDir = sourceDir,
                        targetDir = targetDir,
                        selected = sourceDocs.map { it.uri }.toSet(),
                        wholeDirectory = false,
                        mode = mode,
                    ),
                    folderNames = sourceFolders.map { it.name },
                    fileCount = sourceDocs.count { !it.isDirectory },
                    targetName = resolveDisplayPath(activity, targetDir),
                ),
            )
        }
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

private data class TransferResult(
    val failures: Int,
    val created: List<Uri> = emptyList(),
    val trashed: List<TrashedItem> = emptyList(),
)

private fun executeTransfer(activity: ComponentActivity, request: TransferRequest): TransferResult {
    val takenNames = Storage.childNames(activity, request.targetDir)
    val wanted = if (request.wholeDirectory) listOf(request.sourceDir) else request.selected.toList()
    val sources = wanted.mapNotNull { Storage.entry(activity, it) }
    var failures = wanted.size - sources.size
    val created = mutableListOf<Uri>()
    val trashed = mutableListOf<TrashedItem>()
    sources.forEach { source ->
        val outcome = transferEntry(activity, source, request.targetDir, takenNames)
        failures += outcome.failures
        val copy = outcome.created ?: return@forEach
        created += copy
        // A move is a copy plus a trashed original, which keeps the whole operation reversible.
        if (request.mode == TransferMode.MOVE && outcome.failures == 0) {
            val parked = moveToTrash(activity, source.uri)
            when {
                parked != null -> trashed += parked
                !Storage.delete(activity, source.uri) -> failures += 1
            }
        }
    }
    return TransferResult(failures, created, trashed)
}

private fun failureMessage(count: Int, verb: String): String =
    if (count == 1) "1 item could not be $verb" else "$count items could not be $verb"

private data class EntryOutcome(val created: Uri?, val failures: Int)

/** [takenNames] is threaded through so a batch transfer does not re-list the destination per item. */
private fun transferEntry(
    context: Context,
    source: FileEntry,
    targetDir: Uri,
    takenNames: MutableSet<String>,
): EntryOutcome {
    if (source.isDirectory) {
        val folderName = nextAvailableName(takenNames, source.name)
        val destination = Storage.createFolder(context, targetDir, folderName)
            ?: return EntryOutcome(null, 1)
        val childNames = mutableSetOf<String>()
        var failures = 0
        Storage.children(context, source.uri).forEach { child ->
            if (child.uri == destination.uri) return@forEach
            failures += transferEntry(context, child, destination.uri, childNames).failures
        }
        return EntryOutcome(destination.uri, failures)
    }

    val copy = copyFileContents(context, source, targetDir, takenNames)
    return EntryOutcome(copy, if (copy == null) 1 else 0)
}

private fun copyFileContents(
    context: Context,
    source: FileEntry,
    targetDir: Uri,
    takenNames: MutableSet<String>,
): Uri? {
    val content = Storage.read(context, source) ?: return null
    val name = nextAvailableName(takenNames, content.fileName)
    return runCatching {
        content.stream.use { input -> Storage.writeChild(context, targetDir, name, content.mimeType, input) }
    }.getOrNull()
}

internal fun nextAvailableName(takenNames: MutableSet<String>, preferredName: String): String {
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

/**
 * The whole path rather than the last folder name, so a pane says where it is and not merely what
 * it is in. The shared storage root is named "0" on disk, which tells the user nothing.
 */
private fun resolveDisplayPath(context: Context, uri: Uri): String {
    if (DriveUris.isDrive(uri)) {
        val name = Storage.entry(context, uri)?.name.orEmpty()
        return if (name.isBlank()) "Google Drive" else "Google Drive/$name"
    }
    if (uri.scheme == ContentResolver.SCHEME_FILE) {
        return uri.path?.let(::labelForFilePath) ?: INTERNAL_STORAGE_LABEL
    }
    val documentId = runCatching {
        if (DocumentsContract.isDocumentUri(context, uri)) {
            DocumentsContract.getDocumentId(uri)
        } else {
            DocumentsContract.getTreeDocumentId(uri)
        }
    }.getOrNull()
    if (documentId != null) {
        val volume = documentId.substringBefore(':', "")
        val relative = documentId.substringAfter(':', "").trim('/')
        val root = if (volume.isBlank() || volume == "primary") INTERNAL_STORAGE_LABEL else volume
        return if (relative.isEmpty()) root else "$root/$relative"
    }
    return Storage.entry(context, uri)?.name ?: uri.lastPathSegment ?: "unknown"
}

private fun labelForFilePath(path: String): String {
    val trimmed = path.trimEnd('/')
    val externalRoot = Environment.getExternalStorageDirectory().path.trimEnd('/')
    val emulatedRoot = Regex("^/storage/emulated/\\d+").find(trimmed)?.value
        ?: externalRoot.takeIf { it.isNotEmpty() && (trimmed == it || trimmed.startsWith("$it/")) }
    if (emulatedRoot != null) {
        val relative = trimmed.removePrefix(emulatedRoot).trim('/')
        return if (relative.isEmpty()) INTERNAL_STORAGE_LABEL else "$INTERNAL_STORAGE_LABEL/$relative"
    }
    return trimmed.ifEmpty { "/" }
}

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

/**
 * Truncates from the left and sits against the right edge, so the deepest folder — the part that
 * says where the pane is — stays on screen however long the path in front of it grows.
 */
@Composable
private fun PathLabel(path: String) {
    val measurer = rememberTextMeasurer()
    val style = LocalTextStyle.current.copy(fontSize = 10.sp)
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val available = constraints.maxWidth
        val shown = remember(path, available, style) {
            fun fits(candidate: String) =
                measurer.measure(candidate, style, softWrap = false, maxLines = 1).size.width <= available
            if (fits(path)) {
                path
            } else {
                // Longest suffix that still fits, found by halving rather than character by character.
                var low = 0
                var high = path.length
                while (low < high) {
                    val mid = (low + high) / 2
                    if (fits("…" + path.substring(mid))) high = mid else low = mid + 1
                }
                "…" + path.substring(low)
            }
        }
        Text(
            text = shown,
            style = style,
            softWrap = false,
            maxLines = 1,
            textAlign = TextAlign.End,
            modifier = Modifier.fillMaxWidth(),
        )
    }
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
                label = { PathLabel(leftLabel) },
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
                label = { PathLabel(rightLabel) },
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

/** One dialog skin for the whole app so every prompt follows the active theme. */
@Composable
private fun HyperDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = title,
        text = text,
        confirmButton = confirmButton,
        dismissButton = dismissButton,
        shape = AlertDialogDefaults.shape,
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface,
        iconContentColor = MaterialTheme.colorScheme.primary,
        // The black themes put a black dialog on a black scrim, so it needs an edge.
        modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outline, AlertDialogDefaults.shape),
    )
}

@Composable
private fun CommandStrip(
    metrics: LayoutMetrics,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onNewFolder: () -> Unit,
    onRename: () -> Unit,
    renameActive: Boolean,
    onGallery: () -> Unit,
    undoLabel: String?,
    onUndo: () -> Unit,
    multiSelect: Boolean,
    onToggleMulti: () -> Unit,
    selectionActive: Boolean,
    onDeselect: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(metrics.stripWidth)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CommandButton(metrics, "Copy", Icons.Filled.ContentCopy, onCopy)
        CommandButton(metrics, "Move", Icons.AutoMirrored.Filled.DriveFileMove, onMove)
        CommandButton(metrics, "Delete", Icons.Filled.Delete, onDelete)
        CommandButton(metrics, "New Folder", Icons.Filled.CreateNewFolder, onNewFolder)
        CommandButton(
            metrics = metrics,
            label = if (renameActive) "Save" else "Rename",
            icon = Icons.Filled.DriveFileRenameOutline,
            onClick = onRename,
            active = renameActive,
        )
        CommandButton(metrics, "Gallery", Icons.Filled.Image, onGallery)
        if (undoLabel != null) {
            CommandButton(metrics, "Undo $undoLabel", Icons.AutoMirrored.Filled.Undo, onUndo)
        }
        CommandButton(metrics, "Multi", Icons.Filled.SelectAll, onToggleMulti, active = multiSelect)
        if (selectionActive) {
            CommandButton(metrics, "Deselect", Icons.Filled.Deselect, onDeselect)
        }
        CommandButton(metrics, "Settings", Icons.Filled.Settings, onOpenSettings, showLabel = false)
    }
}

@Composable
private fun CommandButton(
    metrics: LayoutMetrics,
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
            .height(metrics.commandHeight)
            .then(
                if (active) {
                    Modifier.border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
                } else {
                    Modifier
                },
            )
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val contentColor = when {
            !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            active -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurface
        }
        Icon(icon, contentDescription = label, modifier = Modifier.size(metrics.commandIcon), tint = contentColor)
        if (showLabel) {
            Text(
                label,
                fontSize = metrics.commandLabel,
                lineHeight = metrics.commandLabel,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** Where a tapped preview can be sent, chosen before anything is written. */
@Composable
private fun SetImageAsDialog(
    hasBackground: Boolean,
    dim: Boolean,
    onDimChange: (Boolean) -> Unit,
    onApply: (WallpaperTarget) -> Unit,
    onClearBackground: () -> Unit,
    onDismiss: () -> Unit,
) {
    var choice by remember { mutableStateOf(WallpaperTarget.APP) }
    HyperDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set image as") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                WallpaperTarget.entries.forEach { target ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { choice = target },
                    ) {
                        RadioButton(selected = choice == target, onClick = { choice = target })
                        Text(target.label)
                    }
                }
                Text(
                    text = "File browser background",
                    modifier = Modifier.padding(top = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                listOf(true to "Dimmed behind the panes", false to "Full brightness").forEach { (dimmed, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onDimChange(dimmed) },
                    ) {
                        RadioButton(selected = dim == dimmed, onClick = { onDimChange(dimmed) })
                        Text(label)
                    }
                }
                if (hasBackground) {
                    TextButton(onClick = onClearBackground) { Text("Remove the file browser background") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(choice) }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SelectionThumbnail(
    activity: ComponentActivity,
    uri: Uri,
    size: Dp,
    offset: Offset,
    onOffsetChange: (Offset) -> Unit,
    onTap: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Decoded at the density-resolved pixel size so the larger preview is not an upscaled thumbnail.
    val pixels = with(LocalDensity.current) { size.roundToPx() }
    val bitmap by produceState<ImageBitmap?>(initialValue = null, uri, pixels) {
        value = withContext(Dispatchers.IO) { loadBitmap(activity, uri, pixels, pixels) }
    }
    var currentOffset by remember(offset) { mutableStateOf(offset) }

    Box(
        modifier = modifier
            .offset { IntOffset(currentOffset.x.roundToInt(), currentOffset.y.roundToInt()) }
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(Unit) {
                detectDragGestures { _, dragAmount ->
                    currentOffset += dragAmount
                    onOffsetChange(currentOffset)
                }
            }
            // Declared after the drag so dragging the preview around does not count as a tap.
            .clickable(onClick = onTap),
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
    HyperDialog(
        onDismissRequest = onDismiss,
        title = { Text("Layout settings") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                LayoutMode.entries.forEach { mode ->
                    Button(
                        onClick = { onSelect(mode) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (mode == selectedMode) "${mode.label} (selected)" else mode.label)
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
    HyperDialog(
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
    HyperDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete") },
        text = { Text(if (count == 1) "Delete this item?" else "Delete $count items?") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ConfirmDeletePlanDialog(
    plan: DeletePlan,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    var acknowledged by remember(plan) { mutableStateOf(false) }
    val folders = plan.directories

    HyperDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (plan.needsAcknowledgement) "Delete ${folders.size} folders?" else "Delete") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                if (plan.needsAcknowledgement) {
                    Text(
                        "Deleting several folders at once is unusual. Everything listed below goes, " +
                            "including what is inside each folder.",
                    )
                }
                folders.forEach { folder ->
                    Text("▸ ${folder.name} — ${countLabel(folder.files, folder.folders)}")
                }
                if (plan.files.isNotEmpty()) {
                    val names = plan.files.take(MAX_LISTED_NAMES).joinToString(", ") { it.name }
                    val extra = plan.files.size - MAX_LISTED_NAMES
                    Text(if (extra > 0) "$names, and $extra more" else names)
                }
                Text(
                    "Deleted items go to a $TRASH_FOLDER_NAME folder beside them, so Undo can put them back.",
                    style = MaterialTheme.typography.labelSmall,
                )
                if (plan.needsAcknowledgement) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = acknowledged, onCheckedChange = { acknowledged = it })
                        Text("Yes, delete all ${folders.size} folders")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = acknowledged || !plan.needsAcknowledgement,
            ) {
                Text("Delete")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ConfirmBulkFolderTransferDialog(
    prompt: BulkFolderPrompt,
    onDismiss: () -> Unit,
    onConfirm: (TransferRequest) -> Unit,
) {
    var acknowledged by remember(prompt) { mutableStateOf(false) }
    val verb = if (prompt.request.mode == TransferMode.MOVE) "Move" else "Copy"

    HyperDialog(
        onDismissRequest = onDismiss,
        title = { Text("$verb ${prompt.folderNames.size} folders?") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Text("${verb.lowercase()} into ${prompt.targetName}, with everything inside them:")
                prompt.folderNames.take(MAX_LISTED_NAMES).forEach { name -> Text("▸ $name") }
                val extra = prompt.folderNames.size - MAX_LISTED_NAMES
                if (extra > 0) Text("▸ and $extra more")
                if (prompt.fileCount > 0) {
                    Text("Plus ${prompt.fileCount} loose ${if (prompt.fileCount == 1) "file" else "files"}.")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = acknowledged, onCheckedChange = { acknowledged = it })
                    Text("Yes, ${verb.lowercase()} all ${prompt.folderNames.size} folders")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(prompt.request) }, enabled = acknowledged) { Text(verb) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private const val MAX_LISTED_NAMES = 10

private fun countLabel(files: Int, folders: Int): String {
    val filePart = if (files == 1) "1 file" else "$files files"
    val folderPart = if (folders == 1) "1 subfolder" else "$folders subfolders"
    return "$filePart, $folderPart"
}

@Composable
private fun NewFolderDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    val trimmed = name.trim()
    // Separators would be read as a path by some providers and silently create nothing.
    val valid = trimmed.isNotEmpty() && '/' !in trimmed && '\\' !in trimmed
    HyperDialog(
        onDismissRequest = onDismiss,
        title = { Text("New folder") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Folder name") },
                singleLine = true,
                isError = name.isNotEmpty() && !valid,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (valid) onConfirm(trimmed) }),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(trimmed) }, enabled = valid) { Text("Create") }
        },
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
            val connected = withContext(Dispatchers.IO) {
                DriveClient.connect(context, androidAccount, account.email)
            }
            if (!connected) {
                driveMessage = "Could not reach Google Drive with that account. Check your connection and try again."
                return@launch
            }
            driveMessage = null
            browseDriveRoot()
        }
    }

    // The grant is made in a system settings screen, so it can only be re-read once we resume.
    var hasAllFilesAccess by remember { mutableStateOf(allFilesAccessGranted()) }
    DisposableEffect(context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) hasAllFilesAccess = allFilesAccessGranted()
        }
        context.lifecycle.addObserver(observer)
        onDispose { context.lifecycle.removeObserver(observer) }
    }

    val pickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            takeUriPermissionSafely(it)
            onFolderSelected(it)
        }
    }

    val driveSignInLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val accountResult = runCatching {
            GoogleSignIn.getSignedInAccountFromIntent(result.data).getResult(ApiException::class.java)
        }
        val account = accountResult.getOrNull()
        if (account != null && DriveAuth.hasDriveScope(account)) {
            connectDrive(account)
        } else {
            val error = accountResult.exceptionOrNull()
            if (error is ApiException) {
                android.util.Log.e("DriveSignIn", "ApiException code: ${error.statusCode}, message: ${error.message}")
                driveMessage = "Drive sign-in failed (code ${error.statusCode}): ${error.message}"
            } else {
                android.util.Log.e("DriveSignIn", "Sign-in error: ${error?.message}", error)
                driveMessage = "Drive sign-in was cancelled or the Drive permission was declined."
            }
        }
    }

    HyperDialog(
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
                        val volumes = remember(hasAllFilesAccess) { deviceStorageRoots(context) }
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
                                    onClick = {
                                        scope.launch {
                                            currentUri = withContext(Dispatchers.IO) {
                                                currentUri?.let { Storage.parent(context, it) }
                                            } ?: rootUri
                                        }
                                    },
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

    HyperDialog(
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
    HyperDialog(
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

    HyperDialog(
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

    HyperDialog(
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

internal data class FolderStats(
    val directFiles: Int,
    val directFolders: Int,
    val totalFiles: Int,
    val totalFolders: Int,
    val bytes: Long,
)

/** Walks the tree iteratively; recursion would blow the stack on a deep folder. */
internal fun scanFolder(context: Context, uri: Uri): FolderStats {
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

    HyperDialog(
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

    HyperDialog(
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
    startStage: GalleryStage,
    sortOrder: SortOrder,
    onUndoable: (UndoRecord?) -> Unit,
    /** Carries the image last on screen, so the browser can come back to it. */
    onClose: (Uri?) -> Unit,
) {
    var currentUri by rememberSaveable(startingUri) { mutableStateOf(startingUri) }
    var stage by rememberSaveable(startingUri) { mutableStateOf(startStage) }
    var scale by rememberSaveable { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var showMenu by remember { mutableStateOf(false) }
    var showExif by rememberSaveable { mutableStateOf(false) }
    var imageActionUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var listingRefresh by remember { mutableIntStateOf(0) }
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedImages by remember { mutableStateOf(emptySet<Uri>()) }
    var pendingGalleryDelete by remember { mutableStateOf<Set<Uri>?>(null) }
    var pendingRotate by remember { mutableStateOf<ImageRotation.Cost?>(null) }
    // Bumped when the file on disk changes, so the viewer decodes it again.
    var imageRevision by remember { mutableIntStateOf(0) }
    // The inspector shows the file's own pixels — TIFF at full resolution, RAW as sensor data —
    // instead of the embedded preview browsing uses.
    var inspect by rememberSaveable { mutableStateOf(false) }
    // Within the inspector, the camera's own JPEG rather than the sensor data behind it.
    var inspectCompressed by rememberSaveable { mutableStateOf(false) }
    var showMinimap by rememberSaveable { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    /**
     * Moves to another image. The inspector belongs to the file it was opened on — the next one may
     * have no pixels behind its preview at all, and even another RAW would be read at full
     * resolution unasked — so it closes here, before the load for the new image is started.
     */
    fun showImage(uri: Uri) {
        inspect = false
        inspectCompressed = false
        currentUri = uri
    }

    val images by produceState(initialValue = emptyList<FileEntry>(), directoryUri, startingUri, listingRefresh, sortOrder) {
        value = withContext(Dispatchers.IO) {
            directoryUri?.let { dir ->
                Storage.children(activity, dir)
                    .filter { !it.isDirectory && isImageMimeType(it.mimeType ?: Storage.mimeType(activity, it.uri)) }
                    .sortedWith(comparatorFor(sortOrder))
            }?.takeIf { it.isNotEmpty() }
                // The gallery can also be opened on a folder, which is not an image itself.
                ?: listOfNotNull(Storage.entry(activity, startingUri)?.takeIf { !it.isDirectory })
        }
    }

    // Opened on a folder, the gallery starts on nothing in particular; the first image stands in
    // so leaving the grid has somewhere to go.
    LaunchedEffect(images) {
        if (images.none { it.uri == currentUri }) {
            images.firstOrNull()?.let { showImage(it.uri) }
        }
    }

    val currentDoc = images.firstOrNull { it.uri == currentUri }
    val currentMimeType by produceState(initialValue = "", currentUri) {
        value = withContext(Dispatchers.IO) { Storage.mimeType(activity, currentUri) }
    }
    val isTiffFile = currentMimeType == "image/tiff"
    val isRawFile = currentMimeType.startsWith("image/x-") || currentMimeType in listOf(
        "image/x-sony-arw", "image/x-sony-srf", "image/x-sony-sr2",
        "image/x-canon-cr2", "image/x-canon-cr3", "image/x-canon-crw",
        "image/x-nikon-nef", "image/x-nikon-nrw",
        "image/x-adobe-dng",
        "image/x-olympus-orf",
        "image/x-fuji-raf",
        "image/x-panasonic-rw2", "image/x-panasonic-raw",
        "image/x-pentax-pef",
        "image/x-samsung-srw",
        "image/x-kodak-dcr",
        "image/x-epson-erf",
        "image/x-hasselblad-3fr",
        "image/x-mamiya-mef",
        "image/x-minolta-mrw",
        "image/x-sigma-x3f",
    )
    // Only these two carry pixels the platform decoder will not show as they were recorded.
    val inspectable = isTiffFile || isRawFile
    // The previous image stays on screen until the next one is ready, so a swipe never flashes an
    // empty frame.
    var single by remember { mutableStateOf<SingleImage?>(null) }
    var detail by remember { mutableStateOf<DetailTile?>(null) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    // Sensor data of a 40-megapixel frame takes seconds to decode; without this the previous
    // image sits there looking as though nothing happened.
    var loading by remember { mutableStateOf(false) }
    LaunchedEffect(currentUri, stage, viewport, inspect, inspectCompressed, imageRevision) {
        detail = null
        single = if (stage == GalleryStage.SINGLE) {
            loading = true
            try {
                withContext(Dispatchers.IO) {
                    loadSingleImage(activity, currentUri, viewport, inspect, inspectCompressed)
                }
            } finally {
                loading = false
            }
        } else {
            // A native-resolution bitmap is far too big to hold onto while the grid is showing.
            null
        }
    }

    // Images too large to decode whole are shown downscaled, then refined a tile at a time as the
    // viewport settles. This is what keeps gigapixel files inside a fixed memory budget, and what
    // the inspector navigates with: panning redraws the coarse pixels immediately and the sharp
    // crop arrives behind it, instead of blocking on a whole new region per move.
    LaunchedEffect(single, viewport) {
        val image = single
        if (image == null || !image.canTile || viewport == IntSize.Zero) {
            detail = null
            return@LaunchedEffect
        }
        snapshotFlow { scale to offset }.collectLatest { (currentScale, currentOffset) ->
            if (currentScale <= 1.02f) {
                detail = null
                return@collectLatest
            }
            delay(120)
            val region = visibleSourceRect(image, viewport, currentScale, currentOffset)
                ?: return@collectLatest
            val sample = tileSampleSize(region, viewport)
            if (sample >= image.sample) {
                detail = null
                return@collectLatest
            }
            // The previous tile stays on screen while this one decodes; a null here would drop
            // back to the overview and flash.
            val tile = withContext(Dispatchers.IO) {
                decodeRegion(activity, currentUri, image, region, sample, viewport)
            }
            detail = tile?.let {
                DetailTile(
                    bitmap = it,
                    source = Rect(
                        region.left.toFloat(),
                        region.top.toFloat(),
                        region.right.toFloat(),
                        region.bottom.toFloat(),
                    ),
                )
            }
        }
    }

    // A gigapixel TIFF needs far more than the 64x a normal photo does before its own pixels are
    // on screen one for one, so the ceiling follows the source.
    val maxZoom = single?.let { image ->
        if (viewport.width <= 0) {
            MAX_SINGLE_ZOOM
        } else {
            (image.width.toFloat() / viewport.width * 2f).coerceIn(MAX_SINGLE_ZOOM, 1024f)
        }
    } ?: MAX_SINGLE_ZOOM

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
            GalleryStage.SINGLE -> {
                val next = (scale * 2f).coerceAtMost(maxZoom)
                // Pan is applied after the zoom, so it has to track it or the view jumps to a
                // different part of the image on every step.
                offset *= next / scale
                scale = next
            }
        }
    }

    fun zoomOut() {
        when (stage) {
            GalleryStage.SINGLE -> if (scale > 1.05f) {
                val next = (scale / 2f).coerceAtLeast(1f)
                offset = if (next <= 1f) Offset.Zero else offset * (next / scale)
                scale = next
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
        showImage(images[nextIndex].uri)
        resetTransform()
    }

    fun rotateCurrent() {
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { ImageRotation.rotateClockwise(activity, currentUri) }
            when (outcome) {
                is ImageRotation.Outcome.Rotated -> {
                    ThumbnailCache.forget(currentUri)
                    resetTransform()
                    imageRevision += 1
                    listingRefresh += 1
                }
                is ImageRotation.Outcome.Failed ->
                    Toast.makeText(activity, outcome.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun requestRotate() {
        val cost = ImageRotation.cost(activity, currentDoc?.size ?: 0L, isRawFile)
        if (inspectable && cost.risky) pendingRotate = cost else rotateCurrent()
    }

    fun deleteImages(targets: Set<Uri>) {
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { deleteItems(activity, targets) }
            onUndoable(UndoRecord("delete", trashed = outcome.trashed).takeIf { !it.isEmpty })
            val remaining = images.filterNot { it.uri in targets }
            selectedImages = emptySet()
            selectionMode = false
            if (remaining.isEmpty()) {
                onClose(null)
            } else {
                if (currentUri in targets) {
                    val index = images.indexOfFirst { it.uri == currentUri }
                    showImage(remaining[index.coerceIn(0, remaining.lastIndex)].uri)
                }
                listingRefresh += 1
            }
        }
    }

    // The system back gesture returns to the browser rather than leaving the app.
    BackHandler(enabled = true) { onClose(currentUri) }

    // A single image is shown on its own; the chrome is summoned by a tap, like the menu.
    val immersive = stage == GalleryStage.SINGLE && !showMenu

    Column(modifier = Modifier.fillMaxSize()) {
        if (!immersive) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AssistChip(
                    onClick = { onClose(currentUri) },
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
                        if (inspectable) {
                            GalleryAction(
                                icon = Icons.Filled.CenterFocusStrong,
                                description = if (inspect) "Leave inspector" else "Inspect at full resolution",
                                tint = if (inspect) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                            ) {
                                inspect = !inspect
                                resetTransform()
                            }
                        }
                        if (inspect && isRawFile) {
                            GalleryAction(
                                icon = Icons.Filled.Compress,
                                description = if (inspectCompressed) {
                                    "Show sensor data"
                                } else {
                                    "Show the camera's JPEG"
                                },
                                tint = if (inspectCompressed) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    LocalContentColor.current
                                },
                            ) {
                                inspectCompressed = !inspectCompressed
                                resetTransform()
                            }
                        }
                        GalleryAction(Icons.Filled.RotateRight, "Rotate 90° clockwise") { requestRotate() }
                        GalleryAction(Icons.Filled.Wallpaper, "Set as wallpaper") {
                            setAsWallpaper(activity, currentUri, currentMimeType)
                        }
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
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                // A zoomed image is drawn beyond the viewport and would otherwise paint over
                // whatever sits above it.
                .clipToBounds()
                // An opened image sits on black in every theme; the controls stay themed.
                .background(if (stage == GalleryStage.SINGLE) Color.Black else MaterialTheme.colorScheme.background)
                .then(if (stage == GalleryStage.SINGLE) Modifier else Modifier.padding(horizontal = 8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val singleImage = single
            if (stage == GalleryStage.SINGLE && singleImage != null) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { viewport = it }
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
                                        scale = next.coerceIn(1f, maxZoom)
                                        if (scale > 1f) offset += pan
                                    }
                                },
                                onSwipe = { step -> showRelative(step) },
                            )
                        }
                        .combinedClickable(
                            // No ripple: it read as a glow trailing the swipe.
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { showMenu = !showMenu },
                            onLongClick = { imageActionUri = currentUri },
                        ),
                ) {
                    val fit = min(size.width / singleImage.width, size.height / singleImage.height)
                    val centreX = size.width / 2f
                    val centreY = size.height / 2f
                    val baseX = (size.width - singleImage.width * fit) / 2f
                    val baseY = (size.height - singleImage.height * fit) / 2f

                    // Pan and zoom are folded into each destination rect so the overview and the
                    // sharper tile stay locked together without a shared graphics layer.
                    fun draw(bitmap: ImageBitmap, source: Rect) {
                        val left = (baseX + source.left * fit - centreX) * scale + centreX + offset.x
                        val top = (baseY + source.top * fit - centreY) * scale + centreY + offset.y
                        val width = source.width * fit * scale
                        val height = source.height * fit * scale
                        if (width < 1f || height < 1f) return
                        drawImage(
                            image = bitmap,
                            dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                            dstSize = IntSize(width.roundToInt(), height.roundToInt()),
                        )
                    }

                    draw(singleImage.overview, Rect(0f, 0f, singleImage.width.toFloat(), singleImage.height.toFloat()))
                    detail?.let { draw(it.bitmap, it.source) }
                }
            }

            // With the toolbar out of the way, zoom stays reachable without a pinch.
            if (immersive) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(topStart = 14.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                        .padding(horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GalleryAction(Icons.Filled.ZoomOut, "Zoom out") { zoomOut() }
                    GalleryAction(Icons.Filled.ZoomIn, "Zoom in") { zoomIn() }
                }
            }

            if (stage != GalleryStage.SINGLE) {
                // Null overscroll config removes the stretch/glow the grid draws at its edges.
                CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
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
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {
                                    if (selectionMode) {
                                        selectedImages = if (picked) selectedImages - file.uri else selectedImages + file.uri
                                    } else {
                                        showImage(file.uri)
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
                    Button(onClick = { onClose(currentUri) }) { Text("Back to file browser") }
                    Button(onClick = { stage = GalleryStage.GRID_SMALL; resetTransform(); showMenu = false }) { Text("Show thumbnails") }
                    if (inspectable) {
                        Button(onClick = {
                            inspect = !inspect
                            resetTransform()
                            showMenu = false
                        }) {
                            Text(if (inspect) "Leave inspector" else "Inspect at full resolution")
                        }
                    }
                    if (inspect && isRawFile) {
                        Button(onClick = {
                            inspectCompressed = !inspectCompressed
                            resetTransform()
                            showMenu = false
                        }) {
                            Text(
                                if (inspectCompressed) {
                                    "Show uncompressed sensor data"
                                } else {
                                    "Show compressed (camera JPEG)"
                                },
                            )
                        }
                    }
                    if (inspect) {
                        Button(onClick = { showMinimap = !showMinimap; showMenu = false }) {
                            Text(if (showMinimap) "Hide locator" else "Show locator")
                        }
                    }
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

            // Locator: the whole frame with the part currently on screen marked, which is the
            // only orientation cue once the view sits deep inside a gigapixel image.
            val locator = single
            if (inspect && showMinimap && stage == GalleryStage.SINGLE && locator != null) {
                val aspect = locator.width.toFloat() / locator.height.toFloat()
                val locatorSize = 132.dp
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .width(if (aspect > 1f) locatorSize else locatorSize * aspect)
                        .height(if (aspect > 1f) locatorSize / aspect else locatorSize)
                        .background(Color.Black)
                        .border(1.dp, MaterialTheme.colorScheme.primary),
                ) {
                    Image(
                        bitmap = locator.overview,
                        contentDescription = "Image locator",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val visible = visibleSourceRect(locator, viewport, scale, offset) ?: return@Canvas
                        val across = size.width / locator.width
                        val down = size.height / locator.height
                        drawRect(
                            color = Color.Red,
                            topLeft = Offset(visible.left * across, visible.top * down),
                            size = androidx.compose.ui.geometry.Size(
                                (visible.width() * across).coerceAtLeast(2f),
                                (visible.height() * down).coerceAtLeast(2f),
                            ),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx()),
                        )
                    }
                }
            }

            // What the inspector is actually showing, so a preview is never taken for the
            // file's own pixels.
            val provenance = when {
                loading && inspect -> "Reading the file's own pixels…"
                else -> single?.let { listOfNotNull(it.source, it.notice).joinToString(" — ") }
                    ?.takeIf { it.isNotEmpty() }
            }
            // The tapped-up menu occupies the same corner of the screen as the label.
            if (inspect && provenance != null && stage == GalleryStage.SINGLE && !showMenu) {
                Text(
                    text = provenance,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(12.dp)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }

            if (imageActionUri != null) {
                HyperDialog(
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
                                setAsWallpaper(activity, target, null)
                            }) { Text("Set as wallpaper") }
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

            pendingRotate?.let { cost ->
                HyperDialog(
                    onDismissRequest = { pendingRotate = null },
                    title = { Text("Rotate this file?") },
                    text = {
                        Text(
                            "The turn itself only rewrites the orientation tag, but drawing this " +
                                "file again afterwards is " + cost.summary +
                                ". On this device that may take a long time, or run out of memory " +
                                "and close the app.",
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            pendingRotate = null
                            rotateCurrent()
                        }) { Text("Rotate") }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingRotate = null }) { Text("Cancel") }
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

/** Repeatable access to an image's bytes, whatever backend it lives on. */
private class ImageSource(
    val open: () -> InputStream?,
    val bytes: ByteArray?,
    /** True when the URI can be handed to a region decoder as a plain file descriptor. */
    val seekable: Boolean,
)

private fun imageSource(activity: ComponentActivity, uri: Uri): ImageSource? {
    if (DriveUris.isDrive(uri)) {
        val bytes = runCatching { Storage.openInput(activity, uri)?.use { it.readBytes() } }.getOrNull() ?: return null
        return ImageSource(open = { ByteArrayInputStream(bytes) }, bytes = bytes, seekable = false)
    }
    val resolver = activity.contentResolver
    val exportType = if (isVirtualDocument(resolver, uri)) exportMimeType(resolver, uri) else null
    return ImageSource(
        open = { openDocumentStream(resolver, uri, exportType) },
        bytes = null,
        seekable = exportType == null,
    )
}

/**
 * A full-frame overview plus what is needed to fetch sharper tiles. [sample] is how much the
 * overview was downscaled, so a tile is only worth decoding below that factor.
 */
private data class SingleImage(
    val overview: ImageBitmap,
    val width: Int,
    val height: Int,
    val sample: Int,
    val canTile: Boolean,
    /** Set for TIFF, which no platform region decoder can read. */
    val tiff: RawImage.TiffImage? = null,
    /** Set when the inspector is showing undemosaiced sensor data. */
    val sensor: RawImage.SensorImage? = null,
    /** Set when the inspector is showing the embedded JPEG; kept so crops can be region-decoded. */
    val jpeg: ByteArray? = null,
    /** What the inspector is showing: sensor data, full-resolution TIFF or embedded preview. */
    val source: String? = null,
    /** Why the inspector could not show the file's own pixels, when it could not. */
    val notice: String? = null,
)

/** A sharper crop of the source, drawn over the overview at [source] (in source pixels). */
private data class DetailTile(val bitmap: ImageBitmap, val source: Rect)

/** Memory-maps the file so gigapixel TIFFs are decoded without ever landing on the heap. */
private fun mappedByteSource(activity: ComponentActivity, uri: Uri): ByteSource? = runCatching {
    activity.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
        RawImage.mappedSource(descriptor.fileDescriptor)
    }
}.getOrNull()

/**
 * Loads the image for the single-image stage. With [inspect] set the file's own pixels are
 * decoded — TIFF at full resolution, RAW as sensor data — as an overview plus the handle that
 * lets the viewer pull sharper crops; otherwise the embedded preview browsing uses is decoded.
 */
private fun loadSingleImage(
    activity: ComponentActivity,
    uri: Uri,
    viewport: IntSize = IntSize.Zero,
    inspect: Boolean = false,
    compressed: Boolean = false,
): SingleImage? {
    val source = imageSource(activity, uri) ?: return null

    // RAW and TIFF: map the file when possible so size is bounded by the crop, not the source.
    fun sourceBytes(): Pair<ByteSource, ByteArray?>? {
        val mapped = if (source.seekable) mappedByteSource(activity, uri) else null
        val bytes = if (mapped == null) source.bytes ?: source.open()?.let { RawImage.readAll(it) } else null
        val byteSource = mapped ?: bytes?.let { RawImage.arraySource(it) } ?: return null
        return byteSource to bytes
    }

    // The inspector runs before any other decode: both the platform decoder and decode() prefer
    // an embedded preview, and would hand back the very image the gallery already shows.
    if (inspect) {
        sourceBytes()?.let { (byteSource, _) ->
            inspectorImage(byteSource, viewport, compressed)?.let { return it }
        }
    }

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    source.open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth > 0 && bounds.outHeight > 0) {
        val sample = drawableSampleSize(bounds.outWidth, bounds.outHeight, 1)
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = source.open()?.use { BitmapFactory.decodeStream(it, null, options) }
        if (bitmap != null) {
            val degrees = source.open()?.use { RawImage.orientationDegrees(it) } ?: 0
            val turned = degrees == 90 || degrees == 270
            return SingleImage(
                overview = RawImage.rotate(bitmap, degrees).asImageBitmap(),
                width = if (turned) bounds.outHeight else bounds.outWidth,
                height = if (turned) bounds.outWidth else bounds.outHeight,
                sample = sample,
                // Tiling only pays off once the overview itself had to be downscaled, and a
                // region decoder works in the file's own orientation rather than the shown one.
                canTile = sample > 1 && source.seekable && degrees == 0,
                // Reached with the inspector on only when the file's own pixels could not be
                // read; saying so beats passing a preview off as them.
                source = if (inspect) "Embedded preview ${bounds.outWidth}\u00d7${bounds.outHeight}" else null,
                notice = if (inspect) "sensor data in this file cannot be read" else null,
            )
        }
    }

    val (byteSource, bytes) = sourceBytes() ?: return null

    // A TIFF's own pixels beat the thumbnail a camera or scanner left in it: those previews are
    // routinely a few hundred pixels wide, which is what made a large TIFF look soft full screen.
    // RAW keeps preferring its embedded JPEG, whose full-size rendition is the point of it.
    val tiffImage = RawImage.openTiff(byteSource)
    if (tiffImage != null) {
        val preview = RawImage.embeddedJpeg(byteSource)
        if (preview == null || preview.width < minOf(tiffImage.width, viewport.width)) {
            tiffSingleImage(tiffImage, byteSource, viewport, null)?.let { return it }
        }
    }

    val decoded = RawImage.decode(byteSource, viewport.width, viewport.height, false)
    if (decoded != null) {
        // Mapped sources have no byte array; orientation is read from the source itself.
        val degrees = bytes?.let { RawImage.orientationDegrees(it) }
            ?: RawImage.orientationDegrees(byteSource)
        val rotated = RawImage.rotate(decoded, degrees)
        return SingleImage(
            overview = rotated.asImageBitmap(),
            width = rotated.width,
            height = rotated.height,
            sample = 1,
            canTile = false,
            source = if (inspect) "Embedded preview ${rotated.width}\u00d7${rotated.height}" else null,
            // Saying so beats showing the preview as though it were the sensor data.
            notice = if (inspect) "sensor data in this file cannot be read" else null,
        )
    }

    return tiffImage?.let { tiffSingleImage(it, byteSource, viewport, null) }
}

/**
 * A TIFF as the viewer holds it: an overview drawn from the smallest pyramid level that still
 * covers the screen, plus the handle crops are pulled through. The overview is decoded at
 * [OVERVIEW_DETAIL] times the viewport so the fitted image is sharp on a dense display and
 * survives a little zoom before a tile arrives.
 */
private fun tiffSingleImage(
    tiff: RawImage.TiffImage,
    byteSource: ByteSource,
    viewport: IntSize,
    label: String?,
): SingleImage? {
    val overview = tiff.render(
        viewport.width * OVERVIEW_DETAIL,
        viewport.height * OVERVIEW_DETAIL,
        null,
    ) ?: return null
    val degrees = RawImage.orientationDegrees(byteSource)
    val turned = degrees == 90 || degrees == 270
    return SingleImage(
        overview = RawImage.rotate(overview, degrees).asImageBitmap(),
        width = if (turned) tiff.height else tiff.width,
        height = if (turned) tiff.width else tiff.height,
        sample = (tiff.width / overview.width).coerceAtLeast(1),
        // Crops are rendered in the file's own orientation, so a turned frame cannot be refined
        // tile by tile without mapping every region back through the rotation.
        canTile = overview.width < tiff.width && degrees == 0,
        tiff = tiff,
        source = label,
    )
}

/**
 * The unfiltered view of a file: sensor data for RAW, the full-resolution directory for TIFF.
 * The overview is only ever decoded to viewport size; everything sharper arrives as tiles.
 */
private fun inspectorImage(byteSource: ByteSource, viewport: IntSize, compressed: Boolean): SingleImage? {
    if (compressed) {
        compressedInspectorImage(byteSource, viewport)?.let { return it }
    }
    RawImage.openSensor(byteSource)?.let { sensor ->
        val overview = sensor.render(viewport.width, viewport.height, null) ?: return@let
        return SingleImage(
            overview = overview.asImageBitmap(),
            // A filter cell is 2x2 photosites and becomes one pixel, so the inspector's
            // coordinate space is half the sensor's.
            width = sensor.width / 2,
            height = sensor.height / 2,
            sample = ((sensor.width / 2) / overview.width).coerceAtLeast(1),
            canTile = overview.width < sensor.width / 2,
            sensor = sensor,
            source = "Sensor data ${sensor.width}\u00d7${sensor.height} (${sensor.source})",
        )
    }
    val tiff = RawImage.openTiff(byteSource) ?: return null
    return tiffSingleImage(
        tiff,
        byteSource,
        viewport,
        "Full resolution ${tiff.width}\u00d7${tiff.height}",
    )
}

/**
 * The camera's own rendition of the frame: the largest JPEG in the file, decoded at its native
 * resolution in full colour and kept whole so zooming pulls crops out of it rather than upscaling
 * the overview. This is as good as the compressed data in the file gets.
 */
private fun compressedInspectorImage(byteSource: ByteSource, viewport: IntSize): SingleImage? {
    val jpeg = RawImage.embeddedJpeg(byteSource) ?: return null
    val fit = computeInSampleSize(
        jpeg.width,
        jpeg.height,
        viewport.width.coerceAtLeast(1),
        viewport.height.coerceAtLeast(1),
    )
    val sample = drawableSampleSize(jpeg.width, jpeg.height, fit)
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val overview = runCatching {
        BitmapFactory.decodeByteArray(jpeg.bytes, 0, jpeg.bytes.size, options)
    }.getOrNull() ?: return null
    val degrees = RawImage.orientationDegrees(byteSource)
    val rotated = RawImage.rotate(overview, degrees)
    val upright = degrees == 90 || degrees == 270
    return SingleImage(
        overview = rotated.asImageBitmap(),
        width = if (upright) jpeg.height else jpeg.width,
        height = if (upright) jpeg.width else jpeg.height,
        sample = sample,
        // Region decoding works in the JPEG's own orientation, so a rotated frame cannot be
        // refined tile by tile without mapping every crop back through the rotation.
        canTile = sample > 1 && degrees == 0,
        jpeg = jpeg.bytes,
        source = "Embedded JPEG ${jpeg.width}\u00d7${jpeg.height}",
    )
}

/**
 * Source rectangle currently on screen, given the fit-to-container placement and the pan/zoom
 * transform applied around the container's centre.
 */
private fun visibleSourceRect(
    image: SingleImage,
    viewport: IntSize,
    scale: Float,
    offset: Offset,
): android.graphics.Rect? {
    val containerWidth = viewport.width.toFloat()
    val containerHeight = viewport.height.toFloat()
    if (containerWidth <= 0f || containerHeight <= 0f) return null
    val fit = min(containerWidth / image.width, containerHeight / image.height)
    if (fit <= 0f) return null
    val centreX = containerWidth / 2f
    val centreY = containerHeight / 2f
    val baseX = (containerWidth - image.width * fit) / 2f
    val baseY = (containerHeight - image.height * fit) / 2f

    fun sourceX(screen: Float) = (((screen - centreX - offset.x) / scale) + centreX - baseX) / fit
    fun sourceY(screen: Float) = (((screen - centreY - offset.y) / scale) + centreY - baseY) / fit

    val left = sourceX(0f).coerceIn(0f, image.width.toFloat())
    val right = sourceX(containerWidth).coerceIn(0f, image.width.toFloat())
    val top = sourceY(0f).coerceIn(0f, image.height.toFloat())
    val bottom = sourceY(containerHeight).coerceIn(0f, image.height.toFloat())
    if (right - left < 2f || bottom - top < 2f) return null
    return android.graphics.Rect(
        floor(left).toInt(),
        floor(top).toInt(),
        ceil(right).toInt(),
        ceil(bottom).toInt(),
    )
}

/** Picks the coarsest sampling that still puts roughly one source pixel on each screen pixel. */
private fun tileSampleSize(region: android.graphics.Rect, viewport: IntSize): Int {
    var sample = 1
    while (
        region.width() / (sample * 2) >= viewport.width.coerceAtLeast(1) &&
        region.height() / (sample * 2) >= viewport.height.coerceAtLeast(1)
    ) {
        sample *= 2
    }
    return drawableSampleSize(region.width(), region.height(), sample)
}

@Suppress("DEPRECATION")
private fun decodeRegion(
    activity: ComponentActivity,
    uri: Uri,
    image: SingleImage,
    region: android.graphics.Rect,
    sample: Int,
    viewport: IntSize,
): ImageBitmap? {
    image.sensor?.let { sensor ->
        // The inspector's coordinates are filter cells; the sensor renders photosites.
        val sensorRegion = android.graphics.Rect(
            region.left * 2,
            region.top * 2,
            region.right * 2,
            region.bottom * 2,
        )
        return runCatching {
            sensor.render(viewport.width, viewport.height, sensorRegion)
        }.getOrNull()?.asImageBitmap()
    }
    image.tiff?.let { tiff ->
        return runCatching { tiff.render(viewport.width, viewport.height, region) }
            .getOrNull()?.asImageBitmap()
    }
    image.jpeg?.let { bytes ->
        return runCatching {
            val decoder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                BitmapRegionDecoder.newInstance(bytes, 0, bytes.size)
            } else {
                BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
            } ?: return@runCatching null
            try {
                decoder.decodeRegion(region, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?.asImageBitmap()
            } finally {
                decoder.recycle()
            }
        }.getOrNull()
    }
    return runCatching {
        activity.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            val decoder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                BitmapRegionDecoder.newInstance(descriptor)
            } else {
                BitmapRegionDecoder.newInstance(descriptor.fileDescriptor, false)
            } ?: return@use null
            try {
                decoder.decodeRegion(region, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            } finally {
                decoder.recycle()
            }
        }
    }.getOrNull()
}

private fun loadBitmap(
    activity: ComponentActivity,
    uri: Uri,
    width: Int = 0,
    height: Int = 0,
    lowQuality: Boolean = false,
): ImageBitmap? {
    // Remote bytes are pulled once and decoded from memory; a second stream would re-download.
    val remoteBytes = if (DriveUris.isDrive(uri)) {
        runCatching { Storage.openInput(activity, uri)?.use { it.readBytes() } }.getOrNull() ?: return null
    } else {
        null
    }
    val resolver = activity.contentResolver
    val exportType = if (remoteBytes == null && isVirtualDocument(resolver, uri)) {
        exportMimeType(resolver, uri)
    } else {
        null
    }
    fun open(): InputStream? =
        remoteBytes?.let { ByteArrayInputStream(it) } ?: openDocumentStream(resolver, uri, exportType)

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth > 0 && bounds.outHeight > 0) {
        val opts = BitmapFactory.Options().apply {
            inSampleSize = drawableSampleSize(
                bounds.outWidth,
                bounds.outHeight,
                computeInSampleSize(
                    bounds.outWidth,
                    bounds.outHeight,
                    if (width > 0) width else bounds.outWidth,
                    if (height > 0) height else bounds.outHeight,
                ),
            )
            if (lowQuality) inPreferredConfig = Bitmap.Config.RGB_565
        }
        open()?.use { BitmapFactory.decodeStream(it, null, opts) }?.let { bitmap ->
            val degrees = open()?.use { RawImage.orientationDegrees(it) } ?: 0
            return RawImage.rotate(bitmap, degrees).asImageBitmap()
        }
    }

    // RAW and TIFF are invisible to BitmapFactory and go through the embedded preview decoder.
    // Local files are mapped rather than read: a scientific TIFF can be hundreds of megabytes.
    val mapped = if (remoteBytes == null && exportType == null) mappedByteSource(activity, uri) else null
    val bytes = if (mapped == null) remoteBytes ?: open()?.let { RawImage.readAll(it) } else null
    val byteSource = mapped ?: bytes?.let { RawImage.arraySource(it) } ?: return null
    val decoded = RawImage.decode(byteSource, width, height, lowQuality) ?: return null
    val degrees = bytes?.let { RawImage.orientationDegrees(it) } ?: RawImage.orientationDegrees(byteSource)
    return RawImage.rotate(decoded, degrees).asImageBitmap()
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

    /** Drops every size of one image, for when the file behind it has changed. */
    fun forget(uri: Uri) {
        cache.snapshot().keys.filter { it.startsWith("$uri@") }.forEach { cache.remove(it) }
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

/** Raises [initial] until the decoded frame is small enough for the platform to draw it. */
private fun drawableSampleSize(srcWidth: Int, srcHeight: Int, initial: Int): Int {
    var sample = initial.coerceAtLeast(1)
    while (
        (srcWidth / sample).toLong() * (srcHeight / sample).toLong() > MAX_DRAWABLE_PIXELS ||
        srcWidth / sample > MAX_TEXTURE_EDGE ||
        srcHeight / sample > MAX_TEXTURE_EDGE
    ) {
        sample *= 2
    }
    return sample
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
    onRowTouched: () -> Unit,
    multiSelect: Boolean,
    selectionOutline: Color,
    showHiddenFiles: Boolean,
    showTrashFiles: Boolean,
    sortOptions: SortOptions,
    metrics: LayoutMetrics,
    renameTarget: Uri?,
    renameValue: String,
    onRenameValueChange: (String) -> Unit,
    onCommitRename: () -> Unit,
) {
    val context = LocalContext.current
    val currentUri = state.current ?: state.root
    val listing by produceState<DirectoryListing?>(initialValue = null, currentUri, state.refreshKey, DriveClient.connectionGeneration, showHiddenFiles, showTrashFiles, sortOptions) {
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
    // A row tap is held back for the double-tap window, so it can land after the mode or the
    // selection it was based on has already moved on; it reads the current ones instead.
    val multiSelectNow by rememberUpdatedState(multiSelect)
    val selectionNow by rememberUpdatedState(state.selected)
    val listState = rememberLazyListState()

    // The list is rebuilt from nothing whenever the pane comes back — after the gallery, after a
    // write — and would start at the top. Bringing the selection back into view is what keeps a
    // file deep in a large folder from being lost; it is centred rather than scrolled to the
    // edge, and a row already on screen is left where it is so a tap never shifts the list.
    LaunchedEffect(files, state.selected) {
        val target = state.selected.singleOrNull() ?: return@LaunchedEffect
        val index = files.indexOfFirst { it.uri == target }
        if (index < 0) return@LaunchedEffect
        if (listState.layoutInfo.visibleItemsInfo.any { it.index == index }) return@LaunchedEffect
        listState.scrollToItem(index)
        val row = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
            ?: return@LaunchedEffect
        val margin = (listState.layoutInfo.viewportSize.height - row.size) / 2
        if (margin > 0) listState.scrollToItem(index, -margin)
    }

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
                AssistChip(onClick = onMoveUp, label = { Text("cd ..", fontSize = metrics.rowFontSize) })
            }
        }

        listing?.name?.let { folderName ->
            Text(
                text = folderName,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = metrics.paneHeaderSize,
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
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(files, key = { it.uri }) { file ->
                    val selected = file.uri in state.selected
                    val renaming = file.uri == renameTarget
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
                            // Reported as the finger goes down and again as it lifts, ahead of the
                            // click the double-tap window holds back, so commands know a selection
                            // is on its way and how long ago its window started.
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                    onRowTouched()
                                    waitForUpOrCancellation(pass = PointerEventPass.Initial)
                                    onRowTouched()
                                }
                            }
                            .combinedClickable(
                                onClick = {
                                    onActivate()
                                    val current = selectionNow
                                    val alreadySelected = file.uri in current
                                    onSelectionChange(
                                        when {
                                            multiSelectNow && alreadySelected -> current - file.uri
                                            multiSelectNow -> current + file.uri
                                            alreadySelected -> emptySet()
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
                            .padding(horizontal = 8.dp, vertical = metrics.rowPadding),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = icon,
                                contentDescription = if (file.isDirectory) "Folder" else "File",
                                modifier = Modifier.size(metrics.rowIcon),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (renaming) {
                                val focusRequester = remember { FocusRequester() }
                                LaunchedEffect(file.uri) { runCatching { focusRequester.requestFocus() } }
                                OutlinedTextField(
                                    value = renameValue,
                                    onValueChange = onRenameValueChange,
                                    singleLine = true,
                                    textStyle = TextStyle(fontSize = metrics.rowFontSize),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = { onCommitRename() }),
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(start = 6.dp)
                                        .focusRequester(focusRequester),
                                )
                                TextButton(onClick = onCommitRename, contentPadding = PaddingValues(6.dp)) {
                                    Text("Rename", fontSize = metrics.rowFontSize)
                                }
                            } else {
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
                                    fontSize = metrics.rowFontSize,
                                    lineHeight = metrics.rowFontSize * 1.25f,
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
}
