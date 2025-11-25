package saaicom.tcb.docuscanner

import android.Manifest
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
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
import com.google.android.gms.auth.api.signin.GoogleSignIn
import kotlinx.coroutines.launch
import org.opencv.android.OpenCVLoader
import saaicom.tcb.docuscanner.screens.camera.CameraScreen
import saaicom.tcb.docuscanner.screens.edit.ScannedDocumentEditScreen
import saaicom.tcb.docuscanner.screens.files.FilesScreen
import saaicom.tcb.docuscanner.screens.home.HomeScreen
import saaicom.tcb.docuscanner.screens.settings.SettingsScreen
import saaicom.tcb.docuscanner.screens.sign.PdfSignScreen
import saaicom.tcb.docuscanner.screens.sign.SignScreen
import saaicom.tcb.docuscanner.screens.viewer.PdfViewScreen
import saaicom.tcb.docuscanner.ui.theme.DocuScannerTheme
import android.os.Environment
import androidx.compose.ui.platform.LocalConfiguration
import saaicom.tcb.docuscanner.screens.settings.ImportFilesScreen
import androidx.fragment.app.FragmentActivity // <--- ADD THIS IMPORT

// Define your routes as constants
object Routes {
    const val HOME = "home"
    const val FILES = "files"
    const val CAMERA = "camera"
    const val SIGN = "sign"
    const val SETTINGS = "settings"
    const val EDIT_DOCUMENT = "edit_document/{imageUri}"
    const val PDF_SIGN = "pdf_sign/{pdfUri}"
    const val PDF_VIEW = "pdf_view/{pdfUri}"
    const val IMPORT = "import" // for now it is for importing from Google Drive
}

class MainActivity : FragmentActivity() { //ComponentActivity() {

    private val _hasStoragePermission = mutableStateOf(false)
    val hasStoragePermission: State<Boolean> = _hasStoragePermission

    private val storageManagerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            _hasStoragePermission.value = Environment.isExternalStorageManager()
        }
    }

    companion object {
        init {
            if (!OpenCVLoader.initLocal()) {
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

    // Standard launcher for multiple permissions
    private val requestStoragePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Check for read permission (handles both READ_EXTERNAL_STORAGE and READ_MEDIA_IMAGES)
        val readGranted = permissions[Manifest.permission.READ_EXTERNAL_STORAGE] == true ||
                permissions[Manifest.permission.READ_MEDIA_IMAGES] == true

        // Write permission is implicitly granted on Android 10+ (API 29+), so we assume true if not found
        val writeGranted = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            permissions[Manifest.permission.WRITE_EXTERNAL_STORAGE] == true
        } else {
            true
        }

        _hasStoragePermission.value = readGranted && writeGranted

        if (_hasStoragePermission.value) {
            Log.d("MainActivity", "Storage permissions granted. ✅")
        } else {
            Log.w("MainActivity", "Storage permissions denied. ❌")
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        MobileAds.initialize(this) {}
        requestStoragePermissions()

        setContent {
            DocuScannerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    DocuScannerApp(
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
    private fun checkAndRequestCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> {
                Log.d("MainActivity", "Camera permission already granted. ✅")
            }
            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                Log.i("MainActivity", "Showing rationale for camera permission. ℹ️")
                requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
            else -> {
                Log.i("MainActivity", "Requesting camera permission directly. 🚦")
                requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun requestStoragePermissions() {
        val permissionsToRequest = mutableListOf<String>()

        // 1. Determine which READ permission to ask for
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        // 2. Determine if we need WRITE permission (only for Android < 10)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestStoragePermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            _hasStoragePermission.value = true
        }
    }
}


@Composable
fun DocuScannerApp(requestCameraPermission: () -> Unit, hasStoragePermission: Boolean) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isDrawingSignature by remember { mutableStateOf(false) }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val activity = LocalContext.current as? Activity

    val smallestWidthDp = LocalConfiguration.current.smallestScreenWidthDp

    // 2. Define the threshold for a compact device (phone)
    val IS_COMPACT_SCREEN = smallestWidthDp < 600 // Android's definition of a phone/compact width

    LaunchedEffect(currentRoute, activity) {
        if (activity == null) return@LaunchedEffect

        if (IS_COMPACT_SCREEN) {
            // 1. SMALL DEVICE (Phone): Enforce Portrait lock for all screens except SIGN
            val targetOrientation = if (currentRoute == Routes.SIGN) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }

            if (activity.requestedOrientation != targetOrientation) {
                activity.requestedOrientation = targetOrientation
            }
        } else {
            // 2. LARGE DEVICE (Tablet/Foldable): Allow OS to manage orientation
            if (activity.requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
        // We let SignScreen handle its own forcing to Landscape
    }
    // *** END CRITICAL FIX ***

    LaunchedEffect(Unit) {
        val lastSignedInAccount = GoogleSignIn.getLastSignedInAccount(context)
        if (lastSignedInAccount != null) {
            Log.d("MainActivity", "Found last signed-in account: ${lastSignedInAccount.email}. Initializing DriveRepository.")
            scope.launch {
                DriveRepository.initialize(context, lastSignedInAccount)
            }
        } else {
            Log.d("MainActivity", "No previously signed-in account found.")
        }
    }


    Scaffold(
        topBar = {
            if(!isDrawingSignature) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) { AdBanner(adUnitId = "ca-app-pub-3940256099942544/6300978111") }
            }
        },
        bottomBar = {
            if(!isDrawingSignature){
                DocuScannerBottomNavigationBar(navController)
            }
        }
    ) { innerPadding ->
        val navHostPadding = if(isDrawingSignature) {
            PaddingValues(0.dp)
        } else {
            PaddingValues(
                top = innerPadding.calculateTopPadding(),
                bottom = if (isDrawingSignature) 0.dp else innerPadding.calculateBottomPadding(),
                start = innerPadding.calculateLeftPadding(LocalLayoutDirection.current),
                end = innerPadding.calculateRightPadding(LocalLayoutDirection.current)
            )
        }
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier
                .fillMaxSize()
                .padding(navHostPadding)
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

                BackHandler(enabled = true) {
                    showExitDialog = true
                }

                HomeScreen(navController = navController, hasStoragePermission = hasStoragePermission)
            }

            composable(Routes.FILES) { FilesScreen(navController) }
            composable(Routes.CAMERA) { CameraScreen(navController, requestCameraPermission) }

            composable(Routes.SIGN) { SignScreen(
                navController = navController,
                onDrawingChange = { isDrawingSignature = it }
            ) }

            composable(Routes.SETTINGS) { SettingsScreen(navController) }
            composable(Routes.IMPORT) { ImportFilesScreen(navController) }

            composable(
                route = Routes.EDIT_DOCUMENT + "?corners={corners}",
                arguments = listOf(
                    navArgument("imageUri") { type = NavType.StringType },
                    navArgument("corners") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = "null"
                    }
                )
            ) { backStackEntry ->
                val imageUriString = backStackEntry.arguments?.getString("imageUri")
                val cornersJson = backStackEntry.arguments?.getString("corners")
                val imageUri = imageUriString?.let { Uri.parse(it) }
                imageUri?.let {
                    ScannedDocumentEditScreen(
                        navController = navController,
                        imageUri = it,
                        cornersJson = if (cornersJson == "null") null else cornersJson
                    )
                }
            }

            composable(
                route = Routes.PDF_SIGN,
                arguments = listOf(
                    navArgument("pdfUri") { type = NavType.StringType }
                )
            ) { backStackEntry ->
                val pdfUriString = backStackEntry.arguments?.getString("pdfUri")
                pdfUriString?.let {
                    val decodedUri = Uri.parse(it)
                    PdfSignScreen(navController = navController, pdfUri = decodedUri)
                }
            }

            composable(
                route = Routes.PDF_VIEW,
                arguments = listOf(
                    navArgument("pdfUri") { type = NavType.StringType }
                )
            ) { backStackEntry ->
                val pdfUriString = backStackEntry.arguments?.getString("pdfUri")
                pdfUriString?.let {
                    val decodedUri = Uri.parse(it)
                    PdfViewScreen(navController = navController, pdfUri = decodedUri)
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
        modifier = modifier.statusBarsPadding() //modifier.windowInsetsPadding(WindowInsets.statusBars)
    )
}

@Composable
fun DocuScannerBottomNavigationBar(navController: NavController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Standard navigation logic for other screens (Files, Camera, Sign)
    val navigateToOtherScreen = { destinationRoute: String ->
        navController.navigate(destinationRoute) {
            // Pop up to the start destination to clear out the inner back stack of the current tab
            popUpTo(navController.graph.findStartDestination().id) {
                // Save state of the current tab's content
                saveState = true
            }
            // Prevent multiple copies of the same destination
            launchSingleTop = true
            // Restore state of the destination tab if it was saved
            restoreState = true
        }
    }

    // Navigation logic for Home (the start destination)
    val navigateToHome = {
        navController.navigate(Routes.HOME) {
            // Pop all destinations up to the Home route itself
            popUpTo(Routes.HOME) {
                // Inclusive = false means: pop everything *except* the Home route itself,
                // ensuring a clean stack with only the Home route visible.
                inclusive = false
            }
            launchSingleTop = true
            // Setting restoreState to false (or omitting it) guarantees you land on a fresh Home.
            restoreState = false
        }
    }

    // Navigation logic for Settings (which you previously identified as having a crash risk with restoreState)
    val navigateToSettingsSafe = {
        navController.navigate(Routes.SETTINGS) {
            popUpTo(navController.graph.findStartDestination().id)
            launchSingleTop = true
            restoreState = false // Explicitly set to false for safety
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
            onClick = navigateToHome, // *** Use the new dedicated logic ***
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
            onClick = { navigateToOtherScreen(Routes.FILES) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = Color.White,
                selectedTextColor = Color.White,
                unselectedIconColor = Color.LightGray,
                unselectedTextColor = Color.LightGray,
                indicatorColor = Color.White.copy(alpha = 0.3f)
            )
        )
        NavigationBarItem(
            icon = { Icon(Icons.Filled.PhotoCamera, contentDescription = "Camera") },
            label = { Text("Camera") },
            selected = currentRoute == Routes.CAMERA,
            onClick = { navigateToOtherScreen(Routes.CAMERA) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = Color.White,
                selectedTextColor = Color.White,
                unselectedIconColor = Color.LightGray,
                unselectedTextColor = Color.LightGray,
                indicatorColor = Color.White.copy(alpha = 0.3f)
            )
        )

        NavigationBarItem(
            icon = { Icon(Icons.Filled.Draw, contentDescription = "Sign") },
            label = { Text("Sign") },
            selected = currentRoute == Routes.SIGN,
            onClick = { navigateToOtherScreen(Routes.SIGN) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = Color.White,
                selectedTextColor = Color.White,
                unselectedIconColor = Color.LightGray,
                unselectedTextColor = Color.LightGray,
                indicatorColor = Color.White.copy(alpha = 0.3f)
            )
        )

        NavigationBarItem(
            icon = { Icon(Icons.Filled.Settings, contentDescription = "Settings") },
            label = { Text("Settings") },
            selected = currentRoute == Routes.SETTINGS,
            onClick = navigateToSettingsSafe,
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