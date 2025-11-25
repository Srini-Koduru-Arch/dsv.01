package saaicom.tcb.docuscanner

import android.content.Context
import android.os.Environment
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.FileContent
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.api.services.drive.model.File as DriveFile
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

object DriveRepository {

    private const val FOLDER_NAME = "DocuScanner"
    private const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"

    // *** SIMPLIFIED SCOPE: Only access files/folders created by THIS app ***
    private val SCOPES = listOf(DriveScopes.DRIVE_FILE)

    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing = _isSyncing.asStateFlow()

    private val _syncStatus = MutableStateFlow("Idle")
    val syncStatus = _syncStatus.asStateFlow()

    private val _driveService = MutableStateFlow<Drive?>(null)
    val driveService = _driveService.asStateFlow()

    suspend fun initialize(context: Context, account: GoogleSignInAccount) {
        Log.d("DriveRepository", "Initialize called for account: ${account.email}")
        _driveService.value = getDriveService(context.applicationContext, account)
    }

    fun clear() {
        _driveService.value = null
        _syncStatus.value = "Idle"
    }

    fun getGoogleSignInClient(context: Context): GoogleSignInClient {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            // *** SIMPLIFIED: Only asking for DRIVE_FILE ***
            .requestScopes(Scope(DriveScopes.DRIVE_FILE))
            .build()
        return GoogleSignIn.getClient(context, gso)
    }

    private suspend fun getDriveService(context: Context, account: GoogleSignInAccount): Drive? = withContext(Dispatchers.IO) {
        try {
            val credential = GoogleAccountCredential.usingOAuth2(context, SCOPES)
            credential.selectedAccount = account.account
            val transport = AndroidHttp.newCompatibleTransport()
            val jsonFactory = GsonFactory.getDefaultInstance()
            Drive.Builder(transport, jsonFactory, credential)
                .setApplicationName("DocuScanner")
                .build()
        } catch (e: Exception) {
            Log.e("DriveRepository", "Error building Drive service", e)
            null
        }
    }

    // --- Background Sync/Import Functions ---

    fun startSync(context: Context) { // *** ADDED Context parameter ***
        if (_isSyncing.value) return
        val service = _driveService.value ?: run {
            _syncStatus.value = "Not connected to Drive."
            return
        }

        repositoryScope.launch {
            _isSyncing.value = true
            _syncStatus.value = "Preparing sync..."
            try {
                // *** FIX: Use App Storage (Same as Import) ***
                // previously: Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val localFolder = context.getExternalFilesDir(null)
                    ?: File(context.filesDir, ".")

                if (!localFolder.exists()) {
                    _syncStatus.value = "Local folder not found."
                    return@launch
                }

                // ... rest of the function remains the same ...
                val localFiles = localFolder.listFiles()?.filter {
                    it.isFile && it.name.endsWith(".pdf", ignoreCase = true)
                } ?: emptyList()

                // ... (copy the rest of your existing logic here) ...

                if (localFiles.isEmpty()) {
                    _syncStatus.value = "No local files to sync."
                    return@launch
                }

                _syncStatus.value = "Checking Drive folder..."
                val folderId = findOrCreateDocuScannerFolder(service)
                if (folderId == null) {
                    _syncStatus.value = "Failed to access Drive folder."
                    return@launch
                }

                val remoteFiles = loadDriveFiles(service, folderId)
                val remoteFileNames = remoteFiles.map { it.name }.toSet()
                val filesToUpload = localFiles.filter { !remoteFileNames.contains(it.name) }
                val totalToUpload = filesToUpload.size

                if (totalToUpload == 0) {
                    _syncStatus.value = "All files are already synced. ✅"
                } else {
                    var uploadCount = 0
                    var failCount = 0
                    filesToUpload.forEachIndexed { index, file ->
                        _syncStatus.value = "Uploading ${index + 1} of $totalToUpload: ${file.name}"
                        val result = uploadDriveFile(service, folderId, file)
                        if (result != null) uploadCount++ else failCount++
                        delay(1000)
                    }
                    _syncStatus.value = "Sync complete. Uploaded $uploadCount. Failed $failCount."
                }
            } catch (e: Exception) {
                Log.e("DriveRepository", "Sync failed", e)
                _syncStatus.value = "Sync failed: ${e.message}"
            } finally {
                _isSyncing.value = false
            }
        }
    }

    // Updated startImport accepts a specific targetFolder
    fun startImport(filesToImport: List<DriveFile>, targetFolder: File) {
        if (_isSyncing.value) return
        val service = _driveService.value ?: run {
            _syncStatus.value = "Not connected to Drive."
            return
        }

        repositoryScope.launch {
            _isSyncing.value = true
            _syncStatus.value = "Preparing import..."
            try {
                if (!targetFolder.exists()) {
                    targetFolder.mkdirs()
                }

                val total = filesToImport.size
                var successCount = 0
                var failCount = 0

                filesToImport.forEachIndexed { index, driveFile ->
                    _syncStatus.value = "Importing ${index + 1} of $total: ${driveFile.name}"

                    val localFile = File(targetFolder, driveFile.name ?: "imported_${System.currentTimeMillis()}.pdf")

                    try {
                        if (driveFile.id != null) {
                            val outputStream = FileOutputStream(localFile)
                            val success = downloadDriveFile(service, driveFile.id, outputStream)
                            if (success) {
                                successCount++
                            } else {
                                failCount++
                                Log.e("DriveRepository", "Failed to download content for ${driveFile.name}")
                            }
                        }
                    } catch (e: Exception) {
                        failCount++
                        Log.e("DriveRepository", "Exception importing ${driveFile.name}", e)
                    }
                    delay(500)
                }
                _syncStatus.value = "Import complete. Imported $successCount. Failed $failCount."
            } catch (e: Exception) {
                Log.e("DriveRepository", "Import failed", e)
                _syncStatus.value = "Import failed: ${e.message}"
            } finally {
                _isSyncing.value = false
            }
        }
    }

    // --- Drive Operations ---

    suspend fun findOrCreateDocuScannerFolder(driveService: Drive): String? = withContext(Dispatchers.IO) {
        val folderName = FOLDER_NAME
        try {
            val query = "mimeType='$FOLDER_MIME_TYPE' and name='$folderName' and 'root' in parents and trashed=false"
            val result = driveService.files().list()
                .setQ(query)
                .setSpaces("drive")
                .setFields("files(id, name)")
                .execute()

            if (result.files.isNotEmpty()) {
                return@withContext result.files[0].id
            } else {
                val fileMetadata = DriveFile().apply {
                    name = folderName
                    mimeType = FOLDER_MIME_TYPE
                }
                val createdFolder = driveService.files().create(fileMetadata)
                    .setFields("id")
                    .execute()
                return@withContext createdFolder.id
            }
        } catch (e: Exception) {
            return@withContext null
        }
    }

    suspend fun loadDriveFiles(driveService: Drive, folderId: String, nameFilter: String? = null): List<DriveFile> = withContext(Dispatchers.IO) {
        try {
            var queryString = "'$folderId' in parents and trashed=false"

            // Only show Folders and PDF files
            queryString += " and (mimeType = 'application/vnd.google-apps.folder' or mimeType = 'application/pdf')"

            if (!nameFilter.isNullOrBlank()) {
                val escapedFilter = nameFilter.replace("'", "\\'")
                queryString += " and name contains '$escapedFilter'"
            }

            val request = driveService.files().list()
                .setQ(queryString)
                .setSpaces("drive")
                .setFields("files(id, name, mimeType)")

            request.orderBy = "folder, name"

            val result = request.execute()
            result.files ?: emptyList()
        } catch (e: Exception) {
            Log.e("DriveRepository", "Error loading files", e)
            emptyList()
        }
    }

    suspend fun uploadDriveFile(driveService: Drive, parentFolderId: String, localFile: File, mimeType: String = "application/pdf"): String? = withContext(Dispatchers.IO) {
        try {
            val fileMetadata = DriveFile().apply {
                name = localFile.name
                parents = listOf(parentFolderId)
            }
            val mediaContent = FileContent(mimeType, localFile)
            val uploadedFile = driveService.files().create(fileMetadata, mediaContent).setFields("id").execute()
            uploadedFile.id
        } catch (e: Exception) {
            null
        }
    }

    suspend fun downloadDriveFile(driveService: Drive, fileId: String, outputStream: OutputStream): Boolean = withContext(Dispatchers.IO) {
        try {
            driveService.files().get(fileId).executeMediaAndDownloadTo(outputStream)
            true
        } catch (e: Exception) {
            false
        }
    }
}