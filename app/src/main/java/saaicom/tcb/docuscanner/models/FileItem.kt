package saaicom.tcb.docuscanner.models

import android.net.Uri

/**
 * A centralized data class to represent a local file item.
 */
data class FileItem(val name: String?, val sizeInBytes: Long, val uri: Uri)