package saaicom.tcb.docuscanner

import android.Manifest
import android.app.Activity
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
import com.google.android.gms.auth.api.signin.GoogleSignIn
import kotlinx.coroutines.launch
import org.opencv.android.OpenCVLoader
import saaicom.tcb.docuscanner.screens.camera.CameraScreen
import saaicom.tcb.docuscanner.screens.edit.ScannedDocumentEditScreen
import saaicom.tcb.docuscanner.screens.files.FilesScreen
import saaicom.tcb.docuscanner.screens.home.HomeScreen
import saaicom.tcb.docuscanner.screens.profile.ProfileScreen
import saaicom.tcb.docuscanner.screens.sign.PdfSignScreen
import saaicom.tcb.docuscanner.screens.sign.SignScreen
import saaicom.tcb.docuscanner.screens.viewer.PdfViewScreen
import saaicom.tcb.docuscanner.ui.theme.DocuScannerTheme
import android.content.pm.ActivityInfo
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp

// Define your routes as constants
object Routes {
    const val HOME = "home"
    const val FILES = "files"
    const val CAMERA = "camera"
    const val SIGN = "sign" // Replaced CLOUD_FILES
    const val PROFILE = "profile"
    const val EDIT_DOCUMENT = "edit_document/{imageUri}"
    const val PDF_SIGN = "pdf_sign/{pdfUri}" // For signing a PDF
    const val PDF_VIEW = "pdf_view/{pdfUri}" // For viewing a PDF
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

    // Launcher for multiple permissions
    private val requestStoragePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val readImagesGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions[Manifest.permission.READ_MEDIA_IMAGES] ?: false
        } else {
            false
        }
        val readExternalGranted = permissions[Manifest.permission.READ_EXTERNAL_STORAGE] ?: false

        _hasStoragePermission.value = readImagesGranted || readExternalGranted

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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
        } else {
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isDrawingSignature by remember { mutableStateOf(false) }

    // <<< NEW: Get current route and activity >>>
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val activity = LocalContext.current as? Activity


    // <<< NEW: Global orientation manager >>>
    LaunchedEffect(currentRoute, activity) {
        // Force portrait for all screens *except* SignScreen.
        // SignScreen will manage its own orientation (portrait list -> landscape canvas).
        if (currentRoute != Routes.SIGN) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }
    // <<< END NEW >>>

    // Persistent sign-in check (can remain, won't hurt)
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
            PaddingValues(0.dp) // No padding at all when drawing
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
        modifier = modifier.windowInsetsPadding(WindowInsets.statusBars)
    )
}

@Composable
fun DocuScannerBottomNavigationBar(navController: NavController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val navigateToScreen = { destinationRoute: String ->
        navController.navigate(destinationRoute) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
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
            icon = { Icon(Icons.Filled.Draw, contentDescription = "Sign") },
            label = { Text("Sign") },
            selected = currentRoute == Routes.SIGN,
            onClick = { navigateToScreen(Routes.SIGN) },
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