package org.gpdresearch.hyperbrowser

import android.content.Context
import android.net.Uri

/**
 * Deleted items are parked here, beside whatever they were deleted from, so the move stays inside
 * one provider and one volume. The leading dot and the word "trash" both keep it out of the panes
 * unless hidden or trash-related files are switched on.
 */
const val TRASH_FOLDER_NAME = ".HyperBrowserTrash"

/** A parked item plus everything needed to put it back where it came from. */
data class TrashedItem(
    val originalParent: Uri,
    val originalName: String,
    val trashedUri: Uri,
)

/** The last reversible operation: [created] items get removed and [trashed] items get restored. */
data class UndoRecord(
    val label: String,
    val trashed: List<TrashedItem> = emptyList(),
    val created: List<Uri> = emptyList(),
) {
    val isEmpty: Boolean get() = trashed.isEmpty() && created.isEmpty()
}

/** One item queued for deletion, with the recursive counts shown in the confirmation. */
data class DeleteEntry(
    val uri: Uri,
    val name: String,
    val isDirectory: Boolean,
    val files: Int = 0,
    val folders: Int = 0,
)

data class DeletePlan(val entries: List<DeleteEntry>) {
    val directories: List<DeleteEntry> = entries.filter { it.isDirectory }
    val files: List<DeleteEntry> = entries.filterNot { it.isDirectory }
    val uris: Set<Uri> = entries.map { it.uri }.toSet()

    /** Wiping several directories at once is rare enough to be spelled out and acknowledged. */
    val needsAcknowledgement: Boolean get() = directories.size > 1
}

data class DeleteOutcome(val failures: Int, val trashed: List<TrashedItem>)

fun buildDeletePlan(context: Context, uris: Collection<Uri>): DeletePlan = DeletePlan(
    uris.mapNotNull { uri ->
        val entry = Storage.entry(context, uri) ?: return@mapNotNull null
        if (!entry.isDirectory) return@mapNotNull DeleteEntry(uri, entry.name, isDirectory = false)
        val stats = scanFolder(context, uri)
        DeleteEntry(uri, entry.name, isDirectory = true, files = stats.totalFiles, folders = stats.totalFolders)
    },
)

/** Moves [uri] into the trash folder beside it; null when the backend cannot move it. */
fun moveToTrash(context: Context, uri: Uri): TrashedItem? {
    val entry = Storage.entry(context, uri) ?: return null
    val parent = Storage.parent(context, uri) ?: return null
    if (entry.name == TRASH_FOLDER_NAME) return null
    val trashDir = trashFolder(context, parent) ?: return null
    var source = uri
    if (entry.name in Storage.childNames(context, trashDir)) {
        val taken = Storage.childNames(context, trashDir).apply { addAll(Storage.childNames(context, parent)) }
        source = Storage.rename(context, uri, nextAvailableName(taken, entry.name)) ?: return null
    }
    val moved = Storage.move(context, source, parent, trashDir) ?: return null
    return TrashedItem(originalParent = parent, originalName = entry.name, trashedUri = moved)
}

fun restoreFromTrash(context: Context, item: TrashedItem): Boolean {
    val trashDir = Storage.parent(context, item.trashedUri) ?: return false
    val moved = Storage.move(context, item.trashedUri, trashDir, item.originalParent) ?: return false
    if (Storage.entry(context, moved)?.name != item.originalName &&
        item.originalName !in Storage.childNames(context, item.originalParent)
    ) {
        Storage.rename(context, moved, item.originalName)
    }
    return true
}

/** Deletes by parking in the trash, falling back to a permanent delete where that is impossible. */
fun deleteItems(context: Context, uris: Collection<Uri>): DeleteOutcome {
    val trashed = mutableListOf<TrashedItem>()
    var failures = 0
    uris.forEach { uri ->
        val parked = moveToTrash(context, uri)
        when {
            parked != null -> trashed += parked
            !Storage.delete(context, uri) -> failures += 1
        }
    }
    return DeleteOutcome(failures, trashed)
}

/** Removes what the operation created, then puts back what it took away. Returns the failure count. */
fun undoOperation(context: Context, record: UndoRecord): Int {
    var failures = 0
    record.created.forEach { uri ->
        if (Storage.entry(context, uri) != null && !Storage.delete(context, uri)) failures += 1
    }
    record.trashed.forEach { item ->
        if (!restoreFromTrash(context, item)) failures += 1
    }
    return failures
}

private fun trashFolder(context: Context, parent: Uri): Uri? {
    val existing = Storage.children(context, parent)
        .firstOrNull { it.isDirectory && it.name == TRASH_FOLDER_NAME }
    return existing?.uri ?: Storage.createFolder(context, parent, TRASH_FOLDER_NAME)?.uri
}
