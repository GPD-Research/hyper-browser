package org.gpdresearch.hyperbrowser

import android.accounts.Account
import android.app.Activity
import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.InputStreamContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File as DriveFile
import java.io.InputStream

const val DRIVE_FOLDER_MIME = "application/vnd.google-apps.folder"
private const val GOOGLE_NATIVE_PREFIX = "application/vnd.google-apps."
private const val DRIVE_FIELDS = "id,name,mimeType,size,trashed,modifiedTime,createdTime"

/** Synthetic URIs so Drive items can flow through the same pane/transfer code as SAF documents. */
object DriveUris {
    const val SCHEME = "gdrive"
    const val ROOT_ID = "root"

    val ROOT: Uri = forId(ROOT_ID)

    fun forId(id: String): Uri = Uri.parse("$SCHEME://drive/$id")

    fun idOf(uri: Uri): String = uri.lastPathSegment ?: ROOT_ID

    fun isDrive(uri: Uri): Boolean = uri.scheme == SCHEME
}

/** Google sign-in for the full `drive` scope; the app-folder scopes cannot see existing user files. */
object DriveAuth {
    private val driveScope = Scope(DriveScopes.DRIVE)

    fun signInClient(activity: Activity): GoogleSignInClient = GoogleSignIn.getClient(
        activity,
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(driveScope)
            .build(),
    )

    fun authorizedAccount(context: Context): GoogleSignInAccount? =
        GoogleSignIn.getLastSignedInAccount(context)?.takeIf { GoogleSignIn.hasPermissions(it, driveScope) }

    fun hasDriveScope(account: GoogleSignInAccount?): Boolean =
        account != null && GoogleSignIn.hasPermissions(account, driveScope)
}

/**
 * Thin Drive v3 wrapper. Every call is blocking and must be invoked from a background dispatcher.
 */
object DriveClient {
    @Volatile
    private var service: Drive? = null

    @Volatile
    var accountName: String? = null
        private set

    val isConnected: Boolean
        get() = service != null

    fun connect(context: Context, account: Account, displayName: String?) {
        val credential = GoogleAccountCredential.usingOAuth2(context, listOf(DriveScopes.DRIVE))
        credential.selectedAccount = account
        service = Drive.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), credential)
            .setApplicationName("Hyper Browser")
            .build()
        accountName = displayName ?: account.name
    }

    fun disconnect() {
        service = null
        accountName = null
    }

    fun listChildren(folderId: String): List<FileEntry> {
        val drive = service ?: return emptyList()
        val results = mutableListOf<FileEntry>()
        var pageToken: String? = null
        do {
            val page = runCatching {
                drive.files().list()
                    .setQ("'$folderId' in parents and trashed = false")
                    .setFields("nextPageToken, files($DRIVE_FIELDS)")
                    .setPageSize(200)
                    .setSpaces("drive")
                    .setSupportsAllDrives(true)
                    .setIncludeItemsFromAllDrives(true)
                    .setPageToken(pageToken)
                    .execute()
            }.getOrNull() ?: return results
            page.files?.forEach { results += it.toEntry() }
            pageToken = page.nextPageToken
        } while (pageToken != null)
        return results
    }

    fun metadata(fileId: String): FileEntry? {
        val drive = service ?: return null
        if (fileId == DriveUris.ROOT_ID) {
            return FileEntry(DriveUris.ROOT, "My Drive", isDirectory = true, mimeType = DRIVE_FOLDER_MIME)
        }
        return runCatching {
            drive.files().get(fileId).setFields(DRIVE_FIELDS).setSupportsAllDrives(true).execute().toEntry()
        }.getOrNull()
    }

    fun parentId(fileId: String): String? {
        val drive = service ?: return null
        if (fileId == DriveUris.ROOT_ID) return null
        return runCatching {
            drive.files().get(fileId).setFields("parents").setSupportsAllDrives(true).execute().parents?.firstOrNull()
        }.getOrNull()
    }

    /** Google-native documents hold no bytes, so they are exported the way the SAF path exports virtual files. */
    fun read(entry: FileEntry): ReadableContent? {
        val drive = service ?: return null
        val fileId = DriveUris.idOf(entry.uri)
        val nativeType = entry.mimeType?.takeIf { it.startsWith(GOOGLE_NATIVE_PREFIX) }
        return if (nativeType != null) {
            val exportType = exportTypeFor(nativeType)
            val stream = runCatching { drive.files().export(fileId, exportType).executeMediaAsInputStream() }
                .getOrNull() ?: return null
            ReadableContent(stream, exportType, withExportExtension(entry.name, exportType))
        } else {
            val stream = runCatching {
                drive.files().get(fileId).setSupportsAllDrives(true).executeMediaAsInputStream()
            }.getOrNull() ?: return null
            ReadableContent(stream, entry.mimeType ?: "application/octet-stream", entry.name)
        }
    }

    fun createFolder(parentId: String, name: String): FileEntry? {
        val drive = service ?: return null
        val metadata = DriveFile().apply {
            this.name = name
            mimeType = DRIVE_FOLDER_MIME
            parents = listOf(parentId)
        }
        return runCatching {
            drive.files().create(metadata).setFields(DRIVE_FIELDS).setSupportsAllDrives(true).execute().toEntry()
        }.getOrNull()
    }

    fun upload(parentId: String, name: String, mimeType: String?, input: InputStream): Boolean {
        val drive = service ?: return false
        val metadata = DriveFile().apply {
            this.name = name
            parents = listOf(parentId)
        }
        val content = InputStreamContent(mimeType ?: "application/octet-stream", input)
        return runCatching {
            drive.files().create(metadata, content).setFields("id").setSupportsAllDrives(true).execute()
        }.isSuccess
    }

    fun delete(fileId: String): Boolean {
        val drive = service ?: return false
        return runCatching { drive.files().delete(fileId).setSupportsAllDrives(true).execute() }.isSuccess
    }

    private fun exportTypeFor(nativeType: String): String = when (nativeType) {
        "application/vnd.google-apps.drawing" -> "image/png"
        "application/vnd.google-apps.script" -> "application/vnd.google-apps.script+json"
        else -> "application/pdf"
    }

    private fun DriveFile.toEntry(): FileEntry = FileEntry(
        uri = DriveUris.forId(id),
        name = name ?: id,
        isDirectory = mimeType == DRIVE_FOLDER_MIME,
        size = getSize() ?: 0L,
        mimeType = mimeType,
        lastModified = modifiedTime?.value ?: 0L,
        createdAt = createdTime?.value ?: modifiedTime?.value ?: 0L,
    )
}

internal fun withExportExtension(name: String, mimeType: String): String {
    val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: return name
    return if (name.endsWith(".$extension", ignoreCase = true)) name else "$name.$extension"
}
