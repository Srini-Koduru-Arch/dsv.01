package saaicom.tcb.docuscanner

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.navigation.NavController
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
import saaicom.tcb.docuscanner.screens.camera.CameraScreen
import saaicom.tcb.docuscanner.screens.cloud.CloudFilesScreen
import saaicom.tcb.docuscanner.screens.edit.ScannedDocumentEditScreen
import saaicom.tcb.docuscanner.screens.files.FilesScreen
import saaicom.tcb.docuscanner.screens.home.HomeScreen
import saaicom.tcb.docuscanner.screens.profile.ProfileScreen
import saaicom.tcb.docuscanner.ui.theme.DocuScannerTheme
import android.net.Uri
import org.opencv.android.OpenCVLoader


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

    Scaffold(
        topBar = {
            AdBanner(adUnitId = "ca-app-pub-3940256099942544/6300978111")
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
            composable(Routes.HOME) { HomeScreen(hasStoragePermission = hasStoragePermission) }
            composable(Routes.FILES) { FilesScreen() }
            composable(Routes.CAMERA) { CameraScreen(navController, requestCameraPermission) }
            composable(Routes.CLOUD_FILES) { CloudFilesScreen() }
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

    NavigationBar(
        containerColor = Color.DarkGray,
        contentColor = Color.White
    ) {
        NavigationBarItem(
            icon = { Icon(Icons.Filled.Home, contentDescription = "Home") },
            label = { Text("Home") },
            selected = currentRoute == Routes.HOME,
            onClick = { navController.navigate(Routes.HOME) },
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
            onClick = { navController.navigate(Routes.FILES) },
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
            onClick = { navController.navigate(Routes.CAMERA) },
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
            onClick = { navController.navigate(Routes.CLOUD_FILES) },
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
            onClick = { navController.navigate(Routes.PROFILE) },
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
