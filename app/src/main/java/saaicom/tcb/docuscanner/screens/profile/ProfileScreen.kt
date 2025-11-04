package saaicom.tcb.docuscanner.screens.profile

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
// We are now using the stable, legacy sign-in client.
import com.google.android.gms.auth.api.signin.GoogleSignIn
import kotlinx.coroutines.launch
import saaicom.tcb.docuscanner.UserData
import saaicom.tcb.docuscanner.UserDataStore
// *** FIX HERE: Import from DriveRepository now ***
import saaicom.tcb.docuscanner.DriveRepository


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val userDataStore = remember { UserDataStore(context) }
    val userData by userDataStore.userDataFlow.collectAsState(
        initial = UserData(firstName = "", lastName = "", termsAccepted = false)
    )

    var googleAccount by remember { mutableStateOf(GoogleSignIn.getLastSignedInAccount(context)) }

    var firstName by remember(userData.firstName) { mutableStateOf(userData.firstName) }
    var lastName by remember(userData.lastName) { mutableStateOf(userData.lastName) }
    var termsAccepted by remember(userData.termsAccepted) { mutableStateOf(userData.termsAccepted) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Profile", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = firstName,
            onValueChange = { firstName = it },
            label = { Text("First Name") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = lastName,
            onValueChange = { lastName = it },
            label = { Text("Last Name") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(24.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Cloud Connection", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(16.dp))
                if (googleAccount == null) {
                    Text("Not connected to Google Drive.")
                } else {
                    Text("Connected as: ${googleAccount?.email}")
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            scope.launch {
                                // *** FIX HERE: Call function via repository ***
                                val signInClient = DriveRepository.getGoogleSignInClient(context)
                                // This is the full disconnection logic for the legacy client
                                signInClient.revokeAccess().addOnCompleteListener {
                                    signInClient.signOut().addOnCompleteListener {
                                        googleAccount = null
                                        DriveRepository.clear() // Clear the service on sign out
                                        Log.d("ProfileScreen", "User disconnected and signed out.")
                                    }
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Disconnect from Google Drive")
                    }
                }
            }
        }


        Spacer(modifier = Modifier.weight(1f))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Checkbox(
                checked = termsAccepted,
                onCheckedChange = { termsAccepted = it }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("I agree to the terms and conditions.", style = MaterialTheme.typography.bodySmall)
        }

        Button(
            onClick = {
                scope.launch {
                    userDataStore.saveUserData(
                        UserData(
                            firstName = firstName,
                            lastName = lastName,
                            termsAccepted = termsAccepted
                        )
                    )
                    Toast.makeText(context, "Profile Saved!", Toast.LENGTH_SHORT).show()
                }
            },
            enabled = termsAccepted,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        ) {
            Text("Save Profile")
        }
    }
}