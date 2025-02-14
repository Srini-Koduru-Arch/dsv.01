package saaicom.tcb.docuscanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import saaicom.tcb.docuscanner.R

@Composable
fun BottomMenu() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Gray)
    ){
        Icon(
            imageVector = Icons.Filled.Home,
            contentDescription = stringResource(id= R.string.home_icon)
        )

        Icon(
            imageVector = Icons.Filled.Folder,
            contentDescription = stringResource(id=R.string.file_icon)
        )

        Icon(
            imageVector = Icons.Filled.CameraAlt,
            contentDescription = stringResource(id=R.string.camera_icon)
        )

        Icon(
            imageVector = Icons.Filled.Cloud,
            contentDescription = stringResource(id=R.string.cloud_icon)
        )

        Icon(
            imageVector = Icons.Filled.Person,
            contentDescription = stringResource(id=R.string.person_icon)
        )
    }
}