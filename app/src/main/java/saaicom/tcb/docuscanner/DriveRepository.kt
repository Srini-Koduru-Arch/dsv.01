package saaicom.tcb.docuscanner

import android.content.Context
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import com.google.api.services.drive.model.File as DriveFile // Alias Drive File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream


object DriveRepository {

    private const val FOLDER_NAME = "DocuScanner"
    private const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"

    // This is the Client ID from your "Web application" type credential
    // Used for requestServerAuthCode if needed, keep for reference
    private const val WEB_CLIENT_ID = "YOUR_WEB_CLIENT_ID.apps.googleusercontent.com"


    // StateFlow to hold the Drive service instance
    private val _driveService = MutableStateFlow<Drive?>(null)
    val driveService = _driveService.asStateFlow()

    // --- Authentication ---

    /**
     * Initializes the Drive service after successful sign-in.
     */
    suspend fun initialize(context: Context, account: GoogleSignInAccount) {
        Log.d("DriveRepository", "Initialize called for account: ${account.email}")
        _driveService.value = getDriveService(context, account)
        Log.d("DriveRepository", "Initialize complete. Drive service is null: ${_driveService.value == null}")
    }

    /**
     * Clears the Drive service instance on sign-out.
     */
    fun clear() {
        Log.d("DriveRepository", "Clear called.")
        _driveService.value = null
        // TODO: Consider if signing out of GoogleSignIn client is needed here too
    }

    /**
     * Gets the GoogleSignInClient configured for Drive access.
     */
    fun getGoogleSignInClient(context: Context): GoogleSignInClient {
        Log.d("DriveRepository", "Building GoogleSignInClient")
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_FILE)) // Request Drive scope needed by credential
            .build()
        return GoogleSignIn.getClient(context, gso)
    }


    /**
     * Builds the Drive service instance using credentials from the signed-in account.
     */
    private suspend fun getDriveService(context: Context, account: GoogleSignInAccount): Drive? = withContext(Dispatchers.IO) {
        Log.d("DriveRepository", "Starting getDriveService for ${account.email}")
        // First, check if the required scope was granted during sign-in
        val requiredScope = Scope(DriveScopes.DRIVE_FILE)
        val hasPermission = GoogleSignIn.hasPermissions(account, requiredScope)
        Log.d("DriveRepository", "Checking for Drive scope permission: $hasPermission")

        if (!hasPermission) {
            Log.w("DriveRepository", "Drive scope not granted for account ${account.email}. Cannot build Drive service.")
            Log.w("DriveRepository", "Granted scopes: ${account.grantedScopes}")
            return@withContext null
        }
        Log.d("DriveRepository", "Drive scope permission confirmed.")

        try {
            Log.d("DriveRepository", "Attempting to create GoogleAccountCredential for ${account.email}")
            val credential = GoogleAccountCredential.usingOAuth2(
                context, listOf(DriveScopes.DRIVE_FILE)
            )
            Log.d("DriveRepository", "GoogleAccountCredential created.")
            credential.selectedAccount = account.account
            Log.d("DriveRepository", "Credential linked to account: ${account.account?.name}")


            // Build Drive service
            Log.d("DriveRepository", "Building Drive service...")
            val transport = AndroidHttp.newCompatibleTransport()
            val jsonFactory = GsonFactory.getDefaultInstance()
            val service = Drive.Builder(transport, jsonFactory, credential)
                .setApplicationName("DocuScanner")
                .build()
            Log.d("DriveRepository", "Drive service built successfully.")
            service
        } catch (e: Exception) {
            Log.e("DriveRepository", "Failed to build GoogleAccountCredential or Drive service", e)
            null
        }
    }

    // --- Drive Operations ---

    /**
     * Finds the "DocuScanner" folder in the user's Drive root, or creates it if not found.
     * Returns the folder ID or null on error.
     */
    suspend fun findOrCreateDocuScannerFolder(driveService: Drive): String? = withContext(Dispatchers.IO) {
        val folderName = FOLDER_NAME
        try {
            // Search for the folder
            val query = "mimeType='$FOLDER_MIME_TYPE' and name='$folderName' and 'root' in parents and trashed=false"
            Log.d("DriveRepository", "Searching for folder with query: $query")
            val result = driveService.files().list()
                .setQ(query)
                .setSpaces("drive")
                .setFields("files(id, name)")
                .execute()

            if (result.files.isNotEmpty()) {
                Log.d("DriveRepository", "Found folder '$folderName' with ID: ${result.files[0].id}")
                return@withContext result.files[0].id
            } else {
                // Folder not found, create it
                Log.d("DriveRepository", "Folder '$folderName' not found, creating...")
                val fileMetadata = DriveFile().apply {
                    name = folderName
                    mimeType = FOLDER_MIME_TYPE
                }
                val createdFolder = driveService.files().create(fileMetadata)
                    .setFields("id")
                    .execute()
                Log.d("DriveRepository", "Created folder '$folderName' with ID: ${createdFolder.id}")
                return@withContext createdFolder.id
            }
        } catch (e: Exception) {
            Log.e("DriveRepository", "Error finding or creating folder '$folderName'", e)
            return@withContext null // Indicate error
        }
    }


    /**
     * Creates a new subfolder within a specified parent folder on Google Drive.
     * Returns the created folder object or null on error.
     */
    suspend fun createDriveFolder(driveService: Drive, parentFolderId: String, folderName: String): DriveFile? = withContext(Dispatchers.IO) {
        try {
            Log.d("DriveRepository", "Creating subfolder '$folderName' in parent '$parentFolderId'")
            val fileMetadata = DriveFile().apply {
                name = folderName
                mimeType = FOLDER_MIME_TYPE
                parents = listOf(parentFolderId)
            }
            val createdFolder = driveService.files().create(fileMetadata)
                .setFields("id, name, mimeType")
                .execute()
            Log.d("DriveRepository", "Created subfolder '$folderName' with ID: ${createdFolder.id}")
            createdFolder
        } catch (e: Exception) {
            Log.e("DriveRepository", "Error creating subfolder '$folderName'", e)
            return@withContext null // Return null on error
        }
    }

    /**
     * Loads files and folders from a specific folder ID on Google Drive, optionally filtering by name.
     * Returns a list of Drive Files or an empty list on error.
     */
    suspend fun loadDriveFiles(
        driveService: Drive,
        folderId: String,
        nameFilter: String? = null // *** ADDED: Name filter parameter ***
        // TODO: Add date range parameters later
    ): List<DriveFile> = withContext(Dispatchers.IO) {
        try {
            Log.d("DriveRepository", "Loading files from folder ID: $folderId with name filter: '$nameFilter'")
            // *** UPDATED: Build query string ***
            var queryString = "'$folderId' in parents and trashed=false"
            if (!nameFilter.isNullOrBlank()) {
                // Escape single quotes in the filter text
                val escapedFilter = nameFilter.replace("'", "\\'")
                queryString += " and name contains '$escapedFilter'"
            }
            // TODO: Add date range filters (e.g., " and modifiedTime > 'YYYY-MM-DDTHH:MM:SSZ'")

            Log.d("DriveRepository", "Executing query: $queryString")
            val result = driveService.files().list()
                .setQ(queryString) // Use the constructed query
                .setSpaces("drive")
                .setFields("files(id, name, mimeType)") // Add mimeType
                .setOrderBy("folder, name") // Optional: Sort folders first, then by name
                .execute()
            Log.d("DriveRepository", "Found ${result.files.size} files in folder $folderId matching filter.")
            result.files ?: emptyList() // Return files or empty list
        } catch (e: Exception) {
            Log.e("DriveRepository", "Error loading files from folder '$folderId'", e)
            return@withContext emptyList<DriveFile>() // Return empty list on error
        }
    }

    /**
     * Downloads a file from Google Drive to a specified local output stream.
     * Returns true on success, false on failure.
     */
    suspend fun downloadDriveFile(driveService: Drive, fileId: String, outputStream: OutputStream): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d("DriveRepository", "Attempting to download file ID: $fileId")
            driveService.files().get(fileId).executeMediaAndDownloadTo(outputStream)
            Log.d("DriveRepository", "File download successful for ID: $fileId")
            true
        } catch (e: IOException) {
            Log.e("DriveRepository", "Error downloading file ID: $fileId", e)
            false
        } catch (e: Exception) {
            Log.e("DriveRepository", "Unexpected error downloading file ID: $fileId", e)
            false
        } finally {
            try {
                outputStream.close() // Ensure stream is closed
            } catch (e: IOException) {
                Log.e("DriveRepository", "Error closing output stream for file ID: $fileId", e)
            }
        }
    }

}