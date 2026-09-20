package com.mossreader.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** 几个 material-icons-core 里没有的图标，直接用 Material 官方路径数据。 */
object AppIcons {
    private fun icon(name: String, path: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(PathParser().parsePathString(path).toNodes(), fill = SolidColor(Color.Black))
            .build()

    val Play: ImageVector by lazy { icon("Play", "M8 5v14l11-7z") }
    val Pause: ImageVector by lazy { icon("Pause", "M6 19h4V5H6v14zm8-14v14h4V5h-4z") }
    val SkipNext: ImageVector by lazy { icon("SkipNext", "M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z") }
    val SkipPrevious: ImageVector by lazy { icon("SkipPrevious", "M6 6h2v12H6zm3.5 6l8.5 6V6z") }
    val Mic: ImageVector by lazy {
        icon("Mic", "M12 14c1.66 0 3-1.34 3-3V5c0-1.66-1.34-3-3-3S9 3.34 9 5v6c0 1.66 1.34 3 3 3zm5.3-3c0 3-2.54 5.1-5.3 5.1S6.7 14 6.7 11H5c0 3.41 2.72 6.23 6 6.72V21h2v-3.28c3.28-.48 6-3.3 6-6.72h-1.7z")
    }
    val Stop: ImageVector by lazy { icon("Stop", "M6 6h12v12H6z") }
}
