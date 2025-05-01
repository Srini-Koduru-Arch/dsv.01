package saaicom.tcb.docuscanner.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.launch
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.sharp.Home
import androidx.compose.material.icons.sharp.Folder
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.sharp.Cloud
import androidx.compose.material.icons.sharp.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.compose.rememberAsyncImagePainter
import saaicom.tcb.docuscanner.R
import saaicom.tcb.docuscanner.processImageWithOpenCV
import saaicom.tcb.docuscanner.saveBitmapToCache

@Composable
fun BottomMenu(navController: NavController) {
    val context = LocalContext.current  // Get context for starting an intent
    val activity = context as ComponentActivity
    val imageUri = remember { mutableStateOf<Uri?>(null) }
    val processedBitmap = remember { mutableStateOf<Bitmap?>(null) }
    val density = LocalDensity.current
    val iconSize = with(density){42.dp}
    val topPadding = with(density){7.dp}

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) {bitmap ->
        bitmap?.let{
            val savedUri = saveBitmapToCache(context, it)
            imageUri.value = savedUri

            // Navigate to EditDocumentActivity with the image URI
            navController.navigate("editDocument/${savedUri}")
        }
    }
    Box(
        modifier = Modifier.fillMaxWidth()
            .height(2.dp)
            .background(Color.LightGray)
    )
    Row(
        modifier = Modifier
            .fillMaxWidth() //.border(width = 2.dp, color = Color.LightGray)
            .padding(top = topPadding),
        horizontalArrangement = Arrangement.SpaceEvenly
    ){
        Icon(
            imageVector = Icons.Sharp.Home,
            contentDescription = stringResource(id= R.string.home_icon),
            modifier = Modifier.size(iconSize),
            tint = Color.LightGray
        )

        Icon(
            imageVector = Icons.Sharp.Folder,
            contentDescription = stringResource(id = R.string.file_icon),
            modifier = Modifier.size(iconSize),
            tint = Color.LightGray
        )

        IconButton(onClick = {launcher.launch()
            //val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            //context.startActivity(cameraIntent)
            },
            modifier = Modifier
                .size(67.dp)
                .offset(y = (-27).dp)
                .clip(CircleShape)
                .background(Color.Cyan)
                .padding(7.dp),
        ) {
            Icon(Icons.Rounded.CameraAlt,
                contentDescription = stringResource(id = R.string.camera_icon),
                modifier = Modifier
                    .size(57.dp)
            )
        }

        imageUri.value?.let {
            Image(
                painter = rememberAsyncImagePainter(it),
                contentDescription = "Captured Image",
                modifier = Modifier.size(200.dp).clip(RoundedCornerShape(12.dp))
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        processedBitmap.value?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "Processed Image",
                modifier = Modifier.size(200.dp).clip(RoundedCornerShape(12.dp))
            )
        }
        Icon(
            imageVector = Icons.Sharp.Cloud,
            contentDescription = stringResource(id=R.string.cloud_icon),
            modifier = Modifier.size(iconSize),
            tint = Color.LightGray
        )

        Icon(
            imageVector = Icons.Sharp.Person,
            contentDescription = stringResource(id=R.string.person_icon),
            modifier = Modifier.size(iconSize),
            tint = Color.LightGray
        )
    }
}