package saaicom.tcb.docuscanner.screens.settings

import android.app.Activity
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload // *** CHANGED: Use Upload icon ***
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.launch
import saaicom.tcb.docuscanner.DriveRepository
import saaicom.tcb.docuscanner.Routes
import saaicom.tcb.docuscanner.UserDataStore
import java.io.File

@Composable
fun SettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // --- UI State ---
    var showTermsDialog by remember { mutableStateOf(false) }

    // --- Drive Repository State ---
    val driveService by DriveRepository.driveService.collectAsState()
    val isSyncing by DriveRepository.isSyncing.collectAsState()
    val syncStatusMessage by DriveRepository.syncStatus.collectAsState()

    // 1. UPDATE PATH LOGIC
    val readablePath = remember {
        // App Internal Storage Path
        val dir = context.getExternalFilesDir(null)
        // This usually looks like: /Android/data/saaicom.tcb.docuscanner/files
        // Let's make it readable:
        val root = Environment.getExternalStorageDirectory().absolutePath
        val path = dir?.absolutePath ?: ""
        if (path.startsWith(root)) {
            path.substring(root.length).trimStart('/')
        } else {
            "App Storage (Internal)"
        }
    }

    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(ApiException::class.java)
                if (account != null) {
                    scope.launch {
                        DriveRepository.initialize(context, account)
                        Toast.makeText(context, "Connected: ${account.email}", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: ApiException) {
                Log.e("SettingsScreen", "Sign-in failed code: ${e.statusCode}", e)
                Toast.makeText(context, "Sign-in failed.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // --- Terms & Conditions Dialog ---
    if (showTermsDialog) {
        AlertDialog(
            onDismissRequest = { showTermsDialog = false },
            title = { Text("Terms & Conditions") },
            text = {
                Text(
                    "Welcome to DocuScanner! This is a free-to-use application provided by Saaicom. " +
                            "By using this app, you agree that Saaicom holds no liability for any data loss or any issues that may arise from this app's use." + "" +
                            "We do not collect any personal information from you. This app is not connected to our data servers and does not store any data. " +
                            "This app displays advertisements to support its development and maintenance. " +
                            "If you change your acceptance, please uninstall this app.",
                )
            },
            confirmButton = {
                TextButton(onClick = { showTermsDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    // --- MAIN LAYOUT ---
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        // 1. SCROLLABLE SECTION
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            HorizontalDivider()

            // --- Files Path Section ---
            Text(
                text = "Files Path:",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
            )
            Text(
                text = "primary: $readablePath",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            // --- Google Drive Section ---
            Text(
                text = "Google Drive Backup",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            if (driveService == null) {
                // DISCONNECTED
                Text(
                    text = "Not connected. Sign in to backup and sync your documents.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                Button(
                    onClick = {
                        val client = DriveRepository.getGoogleSignInClient(context)
                        googleSignInLauncher.launch(client.signInIntent)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Cloud, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Connect to Google Drive")
                }
            } else {
                // CONNECTED
                val email = GoogleSignIn.getLastSignedInAccount(context)?.email ?: "Connected"

                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(text = "Status: Connected", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(text = email, style = MaterialTheme.typography.bodyLarge)

                        if (syncStatusMessage != "Idle") {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = syncStatusMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                        if (isSyncing) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                        }
                    }
                }

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // IMPORT BUTTON
                    OutlinedButton(
                        onClick = {
                            navController.navigate(Routes.IMPORT)
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !isSyncing
                    ) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Import")
                    }

                    // BACKUP BUTTON (Renamed from Sync)
                    Button(
                        onClick = { DriveRepository.startSync(context) },
                        modifier = Modifier.weight(1f),
                        enabled = !isSyncing
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        } else {
                            // *** CHANGED: Icon to CloudUpload ***
                            Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        // *** CHANGED: Text to "Backup" ***
                        Text("Backup")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Disconnect Button
                Button(
                    onClick = {
                        val client = DriveRepository.getGoogleSignInClient(context)
                        client.revokeAccess().addOnCompleteListener {
                            DriveRepository.clear()
                            Toast.makeText(context, "Disconnected & Permissions Revoked", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isSyncing
                ) {
                    Icon(Icons.Default.CloudOff, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Disconnect / Revoke Access")
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // 2. PINNED BOTTOM SECTION
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = LocalIndication.current
                ) {
                    showTermsDialog = true
                }
                .padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.CheckBox,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "Terms & Conditions",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
    }
}