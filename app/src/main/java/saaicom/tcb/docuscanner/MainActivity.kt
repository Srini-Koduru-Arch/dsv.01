package saaicom.tcb.docuscanner

import android.Manifest
import android.app.Activity
import android.content.Context // *** ADDED: Import ***
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.auth.api.signin.GoogleSignIn // *** ADDED: Import ***
import kotlinx.coroutines.launch // *** ADDED: Import ***
import org.opencv.android.OpenCVLoader
import saaicom.tcb.docuscanner.screens.camera.CameraScreen
import saaicom.tcb.docuscanner.screens.cloud.CloudFilesScreen
import saaicom.tcb.docuscanner.screens.edit.ScannedDocumentEditScreen
import saaicom.tcb.docuscanner.screens.files.FilesScreen
import saaicom.tcb.docuscanner.screens.home.HomeScreen
import saaicom.tcb.docuscanner.screens.profile.ProfileScreen
import saaicom.tcb.docuscanner.ui.theme.DocuScannerTheme


// Define your routes as constants for better type safety and readability
object Routes {
    const val HOME = "home"
    const val FILES = "files"
    const val CAMERA = "camera"
    const val CLOUD_FILES = "cloud_files"
    const val PROFILE = "profile"
    const val EDIT_DOCUMENT = "edit_document/{imageUri}"
}

class MainActivity : ComponentActivity() {

    private val _hasStoragePermission = mutableStateOf(false)
    val hasStoragePermission: State<Boolean> = _hasStoragePermission

    companion object {
        init {
            if (!OpenCVLoader.initDebug()) {
                Log.e("MainActivity", "OpenCV initialization failed!")
            } else {
                Log.d("MainActivity", "OpenCV initialization succeeded!")
            }
        }
    }


    // Launcher for a single permission request (e.g., Camera)
    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Log.d("MainActivity", "Camera permission granted. ✅")
        } else {
            Log.w("MainActivity", "Camera permission denied. ❌")
        }
    }

    // Launcher for multiple permissions (e.g., Storage permissions for newer Android versions)
    private val requestStoragePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val readImagesGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Conditionally check for READ_MEDIA_IMAGES on API 33+
            permissions[Manifest.permission.READ_MEDIA_IMAGES] ?: false
        } else {
            false
        }
        val readExternalGranted = permissions[Manifest.permission.READ_EXTERNAL_STORAGE] ?: false

        // Check if any relevant read permission is granted
        _hasStoragePermission.value = readImagesGranted || readExternalGranted

        if (_hasStoragePermission.value) {
            Log.d("MainActivity", "Storage permissions granted (READ_MEDIA_IMAGES or READ_EXTERNAL_STORAGE). ✅")
        } else {
            Log.w("MainActivity", "Storage permissions denied. ❌")
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Hide the system bars for a full-screen, immersive experience
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Initialize the Mobile Ads SDK
        MobileAds.initialize(this) {}

        // Request storage permissions on startup
        requestStoragePermissions()

        setContent {
            DocuScannerTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    DocuScannerApp(
                        // Pass the permission request function down to CameraScreen
                        requestCameraPermission = {
                            checkAndRequestCameraPermission()
                        },
                        hasStoragePermission = hasStoragePermission.value
                    )
                }
            }
        }
    }


    // --- Permission Handling Functions ---

    /**
     * Checks and requests camera permission.
     * This function is passed to the CameraScreen to trigger permission request.
     */
    private fun checkAndRequestCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> {
                Log.d("MainActivity", "Camera permission already granted. ✅")
            }
            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                // Explain why you need the permission (e.g., show a dialog)
                Log.i("MainActivity", "Showing rationale for camera permission. ℹ️")
                requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
            else -> {
                // Directly request the permission
                Log.i("MainActivity", "Requesting camera permission directly. 🚦")
                requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    /**
     * Checks and requests relevant storage permissions based on Android version.
     * This is called once during onCreate of the MainActivity.
     */
    private fun requestStoragePermissions() {
        val permissionsToRequest = mutableListOf<String>()

        // For Android 13 (API 33) and above, request granular media permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
        } else {
            // For Android 12 (API 32) and below, request READ_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            Log.i("MainActivity", "Requesting storage permissions: $permissionsToRequest 🚦")
            requestStoragePermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            _hasStoragePermission.value = true
            Log.d("MainActivity", "All necessary storage permissions already granted. ✅")
        }
    }
}


@Composable
fun DocuScannerApp(requestCameraPermission: () -> Unit, hasStoragePermission: Boolean) {
    val navController = rememberNavController()
    val context = LocalContext.current // *** ADDED: Get context ***
    val scope = rememberCoroutineScope() // *** ADDED: Get scope ***

    // *** ADDED: LaunchedEffect to check for last signed-in user on app start ***
    LaunchedEffect(Unit) {
        val lastSignedInAccount = GoogleSignIn.getLastSignedInAccount(context)
        if (lastSignedInAccount != null) {
            Log.d("MainActivity", "Found last signed-in account: ${lastSignedInAccount.email}. Initializing DriveRepository.")
            scope.launch { // Launch in coroutine scope
                DriveRepository.initialize(context, lastSignedInAccount)
            }
        } else {
            Log.d("MainActivity", "No previously signed-in account found.")
        }
    }


    Scaffold(
        topBar = {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ){AdBanner(adUnitId = "ca-app-pub-3940256099942544/6300978111")}

        },
        bottomBar = { DocuScannerBottomNavigationBar(navController) }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            composable(Routes.HOME) {
                var showExitDialog by remember { mutableStateOf(false) }
                val activity = (LocalContext.current as? Activity)

                if (showExitDialog) {
                    AlertDialog(
                        onDismissRequest = { showExitDialog = false },
                        title = { Text("Exit App") },
                        text = { Text("Are you sure you want to quit?") },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    showExitDialog = false
                                    activity?.finish()
                                }
                            ) {
                                Text("Yes")
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = { showExitDialog = false }
                            ) {
                                Text("No")
                            }
                        }
                    )
                }

                // This BackHandler will be active only when on the home screen.
                // It intercepts the back press that would normally exit the app.
                BackHandler(enabled = true) {
                    showExitDialog = true
                }

                HomeScreen(navController = navController, hasStoragePermission = hasStoragePermission)
            }
            composable(Routes.FILES) { FilesScreen() }
            composable(Routes.CAMERA) { CameraScreen(navController, requestCameraPermission) }
            // *** Pass NavController to CloudFilesScreen ***
            composable(Routes.CLOUD_FILES) { CloudFilesScreen(navController) }
            composable(Routes.PROFILE) { ProfileScreen() }
            composable(
                route = Routes.EDIT_DOCUMENT,
                arguments = listOf(
                    navArgument("imageUri") { type = NavType.StringType }
                )
            ) { backStackEntry ->
                val imageUriString = backStackEntry.arguments?.getString("imageUri")
                val imageUri = imageUriString?.let { Uri.parse(it) }
                imageUri?.let {
                    ScannedDocumentEditScreen(
                        navController = navController,
                        imageUri = it,
                    )
                }
            }
        }
    }
}


@Composable
fun AdBanner(adUnitId: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val adView = remember {
        AdView(context).apply {
            setAdSize(AdSize.BANNER)
            this.adUnitId = adUnitId
        }
    }

    DisposableEffect(key1 = adView) {
        val adRequest = AdRequest.Builder().build()
        adView.loadAd(adRequest)
        onDispose {}
    }

    AndroidView(
        factory = { adView },
        modifier = modifier.windowInsetsPadding(WindowInsets.statusBars)
    )
}

@Composable
fun DocuScannerBottomNavigationBar(navController: NavController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val navigateToScreen: (String) -> Unit = { route ->
        navController.navigate(route) {
            // Pop up to the start destination of the graph to
            // avoid building up a large stack of destinations
            // on the back stack as users select items
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            // Avoid multiple copies of the same destination when
            // re-selecting the same item
            launchSingleTop = true
            // Restore state when re-selecting a previously selected item
            restoreState = true
        }
    }

    NavigationBar(
        containerColor = Color.DarkGray,
        contentColor = Color.White
    ) {
        NavigationBarItem(
            icon = { Icon(Icons.Filled.Home, contentDescription = "Home") },
            label = { Text("Home") },
            selected = currentRoute == Routes.HOME,
            onClick = { navigateToScreen(Routes.HOME) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = Color.White,
                selectedTextColor = Color.White,
                unselectedIconColor = Color.LightGray,
                unselectedTextColor = Color.LightGray,
                indicatorColor = Color.White.copy(alpha = 0.3f)
            )
        )
        NavigationBarItem(
            icon = { Icon(Icons.Filled.List, contentDescription = "Files") },
            label = { Text("Files") },
            selected = currentRoute == Routes.FILES,
            onClick = { navigateToScreen(Routes.FILES) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = Color.White,
                selectedTextColor = Color.White,
                unselectedIconColor = Color.LightGray,
                unselectedTextColor = Color.LightGray,
                indicatorColor = Color.White.copy(alpha = 0.3f)
            )
        )
        NavigationBarItem(
            icon = { Icon(Icons.Filled.Camera, contentDescription = "Camera") },
            label = { Text("Camera") },
            selected = currentRoute == Routes.CAMERA,
            onClick = { navigateToScreen(Routes.CAMERA) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = Color.White,
                selectedTextColor = Color.White,
                unselectedIconColor = Color.LightGray,
                unselectedTextColor = Color.LightGray,
                indicatorColor = Color.White.copy(alpha = 0.3f)
            )
        )
        NavigationBarItem(
            icon = { Icon(Icons.Filled.Cloud, contentDescription = "Cloud") },
            label = { Text("Cloud") },
            selected = currentRoute == Routes.CLOUD_FILES,
            onClick = { navigateToScreen(Routes.CLOUD_FILES) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = Color.White,
                selectedTextColor = Color.White,
                unselectedIconColor = Color.LightGray,
                unselectedTextColor = Color.LightGray,
                indicatorColor = Color.White.copy(alpha = 0.3f)
            )
        )
        NavigationBarItem(
            icon = { Icon(Icons.Filled.Person, contentDescription = "Profile") },
            label = { Text("Profile") },
            selected = currentRoute == Routes.PROFILE,
            onClick = { navigateToScreen(Routes.PROFILE) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = Color.White,
                selectedTextColor = Color.White,
                unselectedIconColor = Color.LightGray,
                unselectedTextColor = Color.LightGray,
                indicatorColor = Color.White.copy(alpha = 0.3f)
            )
        )
    }
}