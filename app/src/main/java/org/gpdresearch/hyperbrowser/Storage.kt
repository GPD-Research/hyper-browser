package org.gpdresearch.hyperbrowser

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes

/** A storage-agnostic file or folder, backed by either SAF or the Drive REST API. */
data class FileEntry(
    val uri: Uri,
    val name: String,
    val isDirectory: Boolean,
    val size: Long = 0L,
    val mimeType: String? = null,
    val lastModified: Long = 0L,
    /** Falls back to [lastModified] when the backend does not track a creation time. */
    val createdAt: Long = 0L,
)

/** Bytes plus the effective type and name once a virtual or Google-native document has been exported. */
class ReadableContent(
    val stream: InputStream,
    val mimeType: String,
    val fileName: String,
)

/**
 * Single entry point for every file operation, dispatching between SAF documents and Drive so the
 * browser panes and transfer engine never need to know which backend they are looking at.
 */
object Storage {

    fun entry(context: Context, uri: Uri): FileEntry? = if (DriveUris.isDrive(uri)) {
        DriveClient.metadata(DriveUris.idOf(uri))
    } else {
        documentFile(context, uri)?.toEntry()
    }

    fun children(context: Context, uri: Uri): List<FileEntry> = if (DriveUris.isDrive(uri)) {
        DriveClient.listChildren(DriveUris.idOf(uri))
    } else {
        documentFile(context, uri)?.listFiles()?.map { it.toEntry() }.orEmpty()
    }

    fun parent(context: Context, uri: Uri): Uri? = if (DriveUris.isDrive(uri)) {
        DriveClient.parentId(DriveUris.idOf(uri))?.let { DriveUris.forId(it) }
    } else {
        parentDocumentUri(context, uri)
    }

    fun mimeType(context: Context, uri: Uri): String {
        val name = uri.lastPathSegment ?: uri.toString()
        if (DriveUris.isDrive(uri)) {
            return entry(context, uri)?.let { refineMimeType(it.mimeType, it.name) } ?: GENERIC_MIME
        }
        val reported = uri.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT }
            ?.let { context.contentResolver.getType(it) }
        return refineMimeType(reported, name)
    }

    fun openInput(context: Context, uri: Uri): InputStream? = if (DriveUris.isDrive(uri)) {
        entry(context, uri)?.let { DriveClient.read(it)?.stream }
    } else {
        openDocumentStream(context.contentResolver, uri)
    }

    fun read(context: Context, entry: FileEntry): ReadableContent? {
        if (DriveUris.isDrive(entry.uri)) return DriveClient.read(entry)
        val resolver = context.contentResolver
        val exportType = if (isVirtualDocument(resolver, entry.uri)) {
            exportMimeType(resolver, entry.uri) ?: return null
        } else {
            null
        }
        val stream = openDocumentStream(resolver, entry.uri, exportType) ?: return null
        return ReadableContent(
            stream = stream,
            mimeType = exportType ?: entry.mimeType ?: "application/octet-stream",
            fileName = if (exportType != null) withExportExtension(entry.name, exportType) else entry.name,
        )
    }

    fun createFolder(context: Context, parentUri: Uri, name: String): FileEntry? = if (DriveUris.isDrive(parentUri)) {
        DriveClient.createFolder(DriveUris.idOf(parentUri), name)
    } else {
        documentFile(context, parentUri)?.createDirectory(name)?.toEntry()
    }

    /** Returns the URI of the written child, or null when the backend refused the write. */
    fun writeChild(context: Context, parentUri: Uri, name: String, mimeType: String, input: InputStream): Uri? {
        android.util.Log.d("Storage", "writeChild: parentUri=$parentUri, name=$name, mimeType=$mimeType")
        if (DriveUris.isDrive(parentUri)) {
            android.util.Log.d("Storage", "Writing to Drive")
            return DriveClient.upload(DriveUris.idOf(parentUri), name, mimeType, input)?.uri
        }
        if (parentUri.scheme == ContentResolver.SCHEME_FILE) {
            // RawDocumentFile.createFile() re-appends the MIME extension, turning photo.jpg into photo.jpg.jpg.
            val parent = parentUri.path?.let(::File) ?: return null
            val destination = File(parent, safeFileName(name) ?: return null)
            return runCatching {
                destination.outputStream().use { output -> input.copyTo(output) }
                Uri.fromFile(destination)
            }.onFailure {
                android.util.Log.e("Storage", "Failed to write ${destination.path}", it)
                destination.delete()
            }.getOrNull()
        }
        android.util.Log.d("Storage", "Writing to local storage")
        val targetDir = documentFile(context, parentUri) ?: run {
            android.util.Log.e("Storage", "Failed to get target directory for $parentUri")
            return null
        }
        android.util.Log.d("Storage", "Target directory obtained: ${targetDir.uri}")
        val destination = targetDir.createFile(mimeType, name) ?: run {
            android.util.Log.e("Storage", "Failed to create file $name with mime type $mimeType")
            return null
        }
        android.util.Log.d("Storage", "File created: ${destination.uri}")
        val written = runCatching {
            context.contentResolver.openOutputStream(destination.uri)?.use { output ->
                val bytesCopied = input.copyTo(output)
                android.util.Log.d("Storage", "Copied $bytesCopied bytes")
                output.flush()
                true
            }
        }.onFailure { error ->
            android.util.Log.e("Storage", "Failed to write to ${destination.uri}", error)
        }.getOrNull() == true
        if (!written) {
            android.util.Log.e("Storage", "Write failed, deleting destination")
            destination.delete()
        }
        android.util.Log.d("Storage", "Write result: $written")
        return destination.uri.takeIf { written }
    }

    /**
     * Relocates [uri] inside one backend. Cross-provider moves return null, as do providers that
     * cannot move documents; callers fall back to copying or to a plain delete.
     */
    fun move(context: Context, uri: Uri, sourceParent: Uri, targetParent: Uri): Uri? {
        if (DriveUris.isDrive(uri) != DriveUris.isDrive(targetParent)) return null
        if (DriveUris.isDrive(uri)) {
            return DriveClient.move(DriveUris.idOf(uri), DriveUris.idOf(targetParent), DriveUris.idOf(sourceParent))?.uri
        }
        if (uri.scheme == ContentResolver.SCHEME_FILE && targetParent.scheme == ContentResolver.SCHEME_FILE) {
            val source = uri.path?.let(::File) ?: return null
            val target = File(targetParent.path ?: return null, source.name)
            if (target.exists()) return null
            return if (source.renameTo(target)) Uri.fromFile(target) else null
        }
        if (uri.scheme != ContentResolver.SCHEME_CONTENT || targetParent.scheme != ContentResolver.SCHEME_CONTENT) return null
        // moveDocument() only works inside a single provider, and only with a real source parent.
        if (uri.authority != targetParent.authority || sourceParent.authority != uri.authority) return null
        return runCatching {
            DocumentsContract.moveDocument(context.contentResolver, uri, sourceParent, targetParent)
        }.getOrNull()
    }

    fun delete(context: Context, uri: Uri): Boolean = if (DriveUris.isDrive(uri)) {
        DriveClient.delete(DriveUris.idOf(uri))
    } else {
        documentFile(context, uri)?.delete() == true
    }

    /** Returns the URI the item lives at after the rename, or null when the backend refused it. */
    fun rename(context: Context, uri: Uri, newName: String): Uri? {
        val safeName = safeFileName(newName) ?: return null
        if (DriveUris.isDrive(uri)) return DriveClient.rename(DriveUris.idOf(uri), safeName)?.uri
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val source = uri.path?.let(::File) ?: return null
            val target = File(source.parentFile ?: return null, safeName)
            if (target.exists()) return null
            return if (source.renameTo(target)) Uri.fromFile(target) else null
        }
        return runCatching { DocumentsContract.renameDocument(context.contentResolver, uri, safeName) }.getOrNull()
    }

    fun childNames(context: Context, uri: Uri): MutableSet<String> =
        children(context, uri).mapTo(mutableSetOf()) { it.name }

    private fun DocumentFile.toEntry(): FileEntry {
        val modified = runCatching { lastModified() }.getOrDefault(0L)
        val entryName = name ?: uri.lastPathSegment ?: "unknown"
        return FileEntry(
            uri = uri,
            name = entryName,
            isDirectory = isDirectory,
            size = if (isDirectory) 0L else length(),
            mimeType = if (isDirectory) type else refineMimeType(type, entryName),
            lastModified = modified,
            createdAt = creationTimeMillis(uri) ?: modified,
        )
    }
}

const val GENERIC_MIME = "application/octet-stream"

/**
 * Types the platform [MimeTypeMap] either misses or reports inconsistently across OEMs. Without
 * them RAW and HEIF files never register as images, so they never reach the viewer.
 */
private val EXTRA_MIME_TYPES = mapOf(
    "arw" to "image/x-sony-arw",
    "srf" to "image/x-sony-srf",
    "sr2" to "image/x-sony-sr2",
    "cr2" to "image/x-canon-cr2",
    "cr3" to "image/x-canon-cr3",
    "crw" to "image/x-canon-crw",
    "nef" to "image/x-nikon-nef",
    "nrw" to "image/x-nikon-nrw",
    "dng" to "image/x-adobe-dng",
    "orf" to "image/x-olympus-orf",
    "raf" to "image/x-fuji-raf",
    "rw2" to "image/x-panasonic-rw2",
    "raw" to "image/x-panasonic-raw",
    "pef" to "image/x-pentax-pef",
    "srw" to "image/x-samsung-srw",
    "dcr" to "image/x-kodak-dcr",
    "erf" to "image/x-epson-erf",
    "3fr" to "image/x-hasselblad-3fr",
    "mef" to "image/x-mamiya-mef",
    "mrw" to "image/x-minolta-mrw",
    "x3f" to "image/x-sigma-x3f",
    "heic" to "image/heic",
    "heif" to "image/heif",
    "hif" to "image/heif",
    "avif" to "image/avif",
    "tif" to "image/tiff",
    "tiff" to "image/tiff",
)

fun fileExtensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()

/** Providers routinely report octet-stream for RAW and HEIF, so the extension gets the last word. */
fun refineMimeType(reported: String?, name: String): String {
    val trimmed = reported?.takeIf { it.isNotBlank() && it != GENERIC_MIME && it != "image/bitmap" }
    // A Google-native title may end in ".pdf" without being one; the reported type decides.
    if (trimmed != null && trimmed.startsWith(GOOGLE_NATIVE_PREFIX)) return trimmed
    val extension = fileExtensionOf(name)
    return EXTRA_MIME_TYPES[extension]
        ?: trimmed
        ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        ?: GENERIC_MIME
}

/** Strips any path so a user supplied or remote name can never escape its parent directory. */
private fun safeFileName(name: String): String? =
    File(name.trim()).name.takeIf { it.isNotBlank() && it != "." && it != ".." }

/** Only local paths expose a creation time; SAF providers have no such column. */
private fun creationTimeMillis(uri: Uri): Long? {
    if (uri.scheme != ContentResolver.SCHEME_FILE) return null
    val path = uri.path ?: return null
    return runCatching {
        Files.readAttributes(Paths.get(path), BasicFileAttributes::class.java).creationTime().toMillis()
    }.getOrNull()?.takeIf { it > 0L }
}

fun documentFile(context: Context, uri: Uri): DocumentFile? {
    return if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
        if (uri.toString().contains("tree")) {
            DocumentFile.fromTreeUri(context, uri)
        } else {
            DocumentFile.fromSingleUri(context, uri)
        }
    } else if (uri.scheme == ContentResolver.SCHEME_FILE) {
        uri.path?.let { DocumentFile.fromFile(File(it)) }
    } else {
        null
    }
}

// fromTreeUri()/fromSingleUri() always report a null parent, so derive it from the document id.
private fun parentDocumentUri(context: Context, uri: Uri): Uri? {
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

// Cloud documents (Google Docs, Sheets, …) hold no bytes; they must be exported to a real type.
fun isVirtualDocument(resolver: ContentResolver, uri: Uri): Boolean {
    if (uri.scheme != ContentResolver.SCHEME_CONTENT) return false
    return runCatching {
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use { cursor ->
            cursor.moveToFirst() &&
                (cursor.getInt(0) and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT) != 0
        }
    }.getOrNull() == true
}

fun exportMimeType(resolver: ContentResolver, uri: Uri): String? {
    val available = runCatching { resolver.getStreamTypes(uri, "*/*") }.getOrNull()?.filterNotNull().orEmpty()
    if (available.isEmpty()) return null
    val preferred = listOf("application/pdf", "image/png", "image/jpeg", "text/plain")
    return preferred.firstOrNull { it in available } ?: available.first()
}

fun openDocumentStream(resolver: ContentResolver, uri: Uri, exportType: String? = null): InputStream? {
    val mimeType = exportType ?: if (isVirtualDocument(resolver, uri)) exportMimeType(resolver, uri) else null
    return if (mimeType == null) {
        resolver.openInputStream(uri)
    } else {
        resolver.openTypedAssetFileDescriptor(uri, mimeType, null)?.createInputStream()
    }
}
