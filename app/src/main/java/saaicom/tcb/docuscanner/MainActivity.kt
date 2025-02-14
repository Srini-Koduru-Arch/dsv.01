package saaicom.tcb.docuscanner

import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import saaicom.tcb.docuscanner.ui.BottomMenu
import saaicom.tcb.docuscanner.ui.theme.DocuScannerTheme

class MainActivity : ComponentActivity() {

    private val TAG:String = "SC-TCB"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if(!OpenCVLoader.initLocal()){
            Log.e(TAG, "OpenCV failed to load")
        }else{
            Log.e(TAG, "OpencV Loaded successfully")
        }
        enableEdgeToEdge()
        setContent {
            DocuScannerTheme {
                HomeScreen()
            }
        }
    }
}

@Composable
fun HomeScreen() {

    Column (
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .background(Color.LightGray)
            .border(2.dp, Color.Blue, shape = RoundedCornerShape(8.dp)) // Border
            .padding(WindowInsets.statusBars.asPaddingValues()), // Adds padding below the status bar
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally
    ){
        //Top Banner Ad
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .border(2.dp, Color.Blue, shape = RoundedCornerShape(8.dp))
                .height(67.dp)
        ){
            Text("This is top advertizement banner", fontSize = 18.sp)
        }

        //Search Bar

        //Files Area

        // Bottom Menu
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 27.dp),
            verticalArrangement = Arrangement.Bottom
        ){
            BottomMenu()
        }

    }


}
