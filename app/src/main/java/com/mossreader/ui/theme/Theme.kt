package com.mossreader.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Material You：Android 12+ 使用系统取色（动态配色），Android 11 使用下面这套固定配色。 */
@Composable
fun MossTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(
            primary = Color(0xFFB9C3FF), onPrimary = Color(0xFF1B2B82), primaryContainer = Color(0xFF33449B), onPrimaryContainer = Color(0xFFDDE1FF),
            secondary = Color(0xFFC2C5DD), secondaryContainer = Color(0xFF424659), background = Color(0xFF121318), surface = Color(0xFF121318),
        )
        else -> lightColorScheme(
            primary = Color(0xFF4A5BB5), onPrimary = Color.White, primaryContainer = Color(0xFFDDE1FF), onPrimaryContainer = Color(0xFF00105C),
            secondary = Color(0xFF5A5D72), secondaryContainer = Color(0xFFDFE1F9), background = Color(0xFFFBF8FF), surface = Color(0xFFFBF8FF),
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
