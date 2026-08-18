package org.gpdresearch.hyperbrowser

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import java.io.InputStream
import java.io.OutputStream

private enum class Pane { LEFT, RIGHT }

private data class BrowserPaneState(
    val root: Uri? = null,
    val current: Uri? = null,
    val selected: Set<Uri> = emptySet(),
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
    var targetPane by remember { mutableStateOf(Pane.RIGHT) }

    val directoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        activity.contentResolver.takePersistableUriPermission(uri, flags)
        val nextState = BrowserPaneState(root = uri, current = uri, selected = emptySet())
        if (activePane == Pane.LEFT) {
            leftPane = nextState
        } else {
            rightPane = nextState
        }
    }

    val activeState = if (activePane == Pane.LEFT) leftPane else rightPane
    val targetState = if (targetPane == Pane.LEFT) leftPane else rightPane
    val selectedFile = activeState.selected.singleOrNull()

    MaterialTheme {
        Scaffold { contentPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            ) {
                ActionBar(
                    directionIsRight = targetPane == Pane.RIGHT,
                    hasSelection = activeState.selected.isNotEmpty(),
                    canOpen = selectedFile != null,
                    onFlip = { targetPane = if (targetPane == Pane.LEFT) Pane.RIGHT else Pane.LEFT },
                    onCopy = {
                        val targetDir = targetState.current ?: targetState.root ?: return@ActionBar
                        performTransfer(activity, activeState, targetDir, true)
                    },
                    onMove = {
                        val targetDir = targetState.current ?: targetState.root ?: return@ActionBar
                        performTransfer(activity, activeState, targetDir, false)
                    },
                    onOpen = {
                        selectedFile?.let { uri ->
                            activity.startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_VIEW, uri).apply {
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    },
                                    null,
                                ),
                            )
                        }
                    },
                )

                Row(Modifier.fillMaxSize()) {
                    DirectoryPane(
                        title = "Left",
                        state = leftPane,
                        isActive = activePane == Pane.LEFT,
                        modifier = Modifier.fillMaxWidth(0.5f),
                        onActivate = { activePane = Pane.LEFT },
                        onChooseRoot = { directoryPicker.launch(null) },
                        onNavigate = { directory -> leftPane = leftPane.copy(current = directory, selected = emptySet()) },
                        onMoveUp = {
                            val currentDoc = leftPane.current?.let { DocumentFile.fromTreeUri(activity, it) }
                            val parent = currentDoc?.parentFile ?: return@DirectoryPane
                            leftPane = leftPane.copy(current = parent.uri, selected = emptySet())
                        },
                        onSelectionChange = { selected -> leftPane = leftPane.copy(selected = selected) },
                    )
                    DirectoryPane(
                        title = "Right",
                        state = rightPane,
                        isActive = activePane == Pane.RIGHT,
                        modifier = Modifier.fillMaxWidth(0.5f),
                        onActivate = { activePane = Pane.RIGHT },
                        onChooseRoot = { directoryPicker.launch(null) },
                        onNavigate = { directory -> rightPane = rightPane.copy(current = directory, selected = emptySet()) },
                        onMoveUp = {
                            val currentDoc = rightPane.current?.let { DocumentFile.fromTreeUri(activity, it) }
                            val parent = currentDoc?.parentFile ?: return@DirectoryPane
                            rightPane = rightPane.copy(current = parent.uri, selected = emptySet())
                        },
                        onSelectionChange = { selected -> rightPane = rightPane.copy(selected = selected) },
                    )
                }
            }
        }
    }
}

private fun performTransfer(
    activity: ComponentActivity,
    sourceState: BrowserPaneState,
    targetUri: Uri,
    copyMode: Boolean,
) {
    val targetDoc = DocumentFile.fromTreeUri(activity, targetUri) ?: return
    sourceState.selected.forEach { uri ->
        val srcDoc = DocumentFile.fromSingleUri(activity, uri) ?: return@forEach
        transferDocument(activity.contentResolver, srcDoc, targetDoc, copyMode)
    }
}

private fun transferDocument(
    resolver: ContentResolver,
    source: DocumentFile,
    targetDir: DocumentFile,
    copyMode: Boolean,
) {
    if (source.isDirectory) {
        val targetName = source.name ?: "folder"
        val destination = targetDir.findFile(targetName) ?: targetDir.createDirectory(targetName) ?: return
        source.listFiles().forEach { child -> transferDocument(resolver, child, destination, copyMode) }
        if (!copyMode) {
            source.delete()
        }
        return
    }

    val targetName = source.name ?: "file"
    val destination = targetDir.findFile(targetName) ?: targetDir.createFile("application/octet-stream", targetName) ?: return
    resolver.openInputStream(source.uri)?.use { input ->
        resolver.openOutputStream(destination.uri)?.use { output -> copyStream(input, output) }
    }
    if (!copyMode) {
        source.delete()
    }
}

private fun copyStream(input: InputStream, output: OutputStream) {
    input.copyTo(output)
    output.flush()
}

@Composable
private fun ActionBar(
    directionIsRight: Boolean,
    hasSelection: Boolean,
    canOpen: Boolean,
    onFlip: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onFlip) {
            Icon(
                imageVector = if (directionIsRight) Icons.AutoMirrored.Filled.ArrowForward else Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Flip transfer direction",
            )
        }

        Row {
            IconButton(onClick = onCopy, enabled = hasSelection) {
                Icon(Icons.Filled.ContentCopy, contentDescription = "Copy")
            }
            IconButton(onClick = onMove, enabled = hasSelection) {
                Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = "Move")
            }
            IconButton(onClick = onOpen, enabled = canOpen) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open with another app")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DirectoryPane(
    title: String,
    state: BrowserPaneState,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    onActivate: () -> Unit,
    onChooseRoot: () -> Unit,
    onNavigate: (Uri) -> Unit,
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
        FlowRow(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
            modifier = Modifier.padding(horizontal = 8.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium,
        )

        LazyColumn(Modifier.fillMaxSize()) {
            items(files, key = { it.uri }) { file ->
                val selected = file.uri in state.selected
                Text(
                    text = if (file.isDirectory) "[${file.name}]" else file.name ?: "Unnamed file",
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onActivate()
                            if (file.isDirectory) {
                                onNavigate(file.uri)
                            } else {
                                onSelectionChange(if (selected) state.selected - file.uri else state.selected + file.uri)
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
