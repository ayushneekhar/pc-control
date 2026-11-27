package com.neekhar.pccontrol.core.ui

import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.neekhar.pccontrol.R

/**
 * Renders the official colored Claude starburst (`#D97757`) or Google Antigravity multicolor arch
 * brand logo without monochrome tinting so true brand colors shine.
 */
@Composable
fun ProviderBrandIcon(
    isClaude: Boolean,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
    contentDescription: String? = null,
) {
    Icon(
        painter = painterResource(
            id = if (isClaude) R.drawable.ic_brand_claude else R.drawable.ic_brand_antigravity,
        ),
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier,
    )
}

/**
 * Custom vector icons used across feature screens.
 */
object PcBrandIcons {

    /**
     * Minimalist cat face silhouette (MaterialCommunityIcons `cat`, 24×24 viewport)
     * with pointed ears, whiskers, eyes, and nose/mouth cutout.
     */
    val Cat: ImageVector by lazy {
        ImageVector.Builder(
            name = "PcBrandIcons.Cat",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.EvenOdd,
            ) {
                // Outer cat silhouette with ears & whiskers
                moveTo(12f, 8f)
                lineTo(10.67f, 8.09f)
                curveTo(9.81f, 7.07f, 7.4f, 4.5f, 5f, 4.5f)
                curveTo(5f, 4.5f, 3.03f, 7.46f, 4.96f, 11.41f)
                curveTo(4.41f, 12.24f, 4.07f, 12.67f, 4f, 13.66f)
                lineTo(2.07f, 13.95f)
                lineTo(2.28f, 14.93f)
                lineTo(4.04f, 14.67f)
                lineTo(4.18f, 15.38f)
                lineTo(2.61f, 16.32f)
                lineTo(3.08f, 17.21f)
                lineTo(4.53f, 16.32f)
                curveTo(5.68f, 18.76f, 8.59f, 20f, 12f, 20f)
                curveTo(15.41f, 20f, 18.32f, 18.76f, 19.47f, 16.32f)
                lineTo(20.92f, 17.21f)
                lineTo(21.39f, 16.32f)
                lineTo(19.82f, 15.38f)
                lineTo(19.96f, 14.67f)
                lineTo(21.72f, 14.93f)
                lineTo(21.93f, 13.95f)
                lineTo(20f, 13.66f)
                curveTo(19.93f, 12.67f, 19.59f, 12.24f, 19.04f, 11.41f)
                curveTo(20.97f, 7.46f, 19f, 4.5f, 19f, 4.5f)
                curveTo(16.6f, 4.5f, 14.19f, 7.07f, 13.33f, 8.09f)
                lineTo(12f, 8f)
                close()

                // Left eye cutout
                moveTo(9f, 11f)
                arcTo(1f, 1f, 0f, isMoreThanHalf = true, isPositiveArc = true, 9f, 13f)
                arcTo(1f, 1f, 0f, isMoreThanHalf = true, isPositiveArc = true, 9f, 11f)
                close()

                // Right eye cutout
                moveTo(15f, 11f)
                arcTo(1f, 1f, 0f, isMoreThanHalf = true, isPositiveArc = true, 15f, 13f)
                arcTo(1f, 1f, 0f, isMoreThanHalf = true, isPositiveArc = true, 15f, 11f)
                close()

                // Nose + mouth cutout
                moveTo(11f, 14f)
                horizontalLineTo(13f)
                lineTo(12.3f, 15.39f)
                curveTo(12.5f, 16.03f, 13.06f, 16.5f, 13.75f, 16.5f)
                arcTo(1.5f, 1.5f, 0f, isMoreThanHalf = false, isPositiveArc = false, 15.25f, 15f)
                horizontalLineTo(15.75f)
                arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 13.75f, 17f)
                curveTo(13f, 17f, 12.35f, 16.59f, 12f, 16f)
                curveTo(11.65f, 16.59f, 11f, 17f, 10.25f, 17f)
                arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 8.25f, 15f)
                horizontalLineTo(8.75f)
                arcTo(1.5f, 1.5f, 0f, isMoreThanHalf = false, isPositiveArc = false, 10.25f, 16.5f)
                curveTo(10.94f, 16.5f, 11.5f, 16.03f, 11.7f, 15.39f)
                lineTo(11f, 14f)
                close()
            }
        }.build()
    }
}
