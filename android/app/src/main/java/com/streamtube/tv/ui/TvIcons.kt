package com.streamtube.tv.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Lightweight inline 24dp Material vector paths so the APK drops the entire
 * multi-megabyte material-icons-extended artifact.
 */
object TvIcons {
    private val black = SolidColor(Color.Black)

    private inline fun vec(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply(block).build()

    val Home: ImageVector by lazy {
        vec("Home") {
            path(fill = black) {
                moveTo(10f, 20f); verticalLineTo(14f); horizontalLineTo(14f); verticalLineTo(20f)
                horizontalLineTo(19f); verticalLineTo(12f); horizontalLineTo(22f)
                lineTo(12f, 3f); lineTo(2f, 12f); horizontalLineTo(5f); verticalLineTo(20f); close()
            }
        }
    }

    val Subscriptions: ImageVector by lazy {
        vec("Subscriptions") {
            path(fill = black) {
                moveTo(20f, 8f); horizontalLineTo(4f); verticalLineTo(6f); horizontalLineTo(20f); verticalLineTo(8f); close()
                moveTo(18f, 2f); horizontalLineTo(6f); verticalLineTo(4f); horizontalLineTo(18f); verticalLineTo(2f); close()
                moveTo(22f, 12f); verticalLineTo(20f)
                curveTo(22f, 21.1f, 21.1f, 22f, 20f, 22f)
                horizontalLineTo(4f); curveTo(2.9f, 22f, 2f, 21.1f, 2f, 20f)
                verticalLineTo(12f); curveTo(2f, 10.9f, 2.9f, 10f, 4f, 10f)
                horizontalLineTo(20f); curveTo(21.1f, 10f, 22f, 10.9f, 22f, 12f); close()
                moveTo(16f, 16f); lineTo(10f, 12.73f); verticalLineTo(19.26f); close()
            }
        }
    }

    val Search: ImageVector by lazy {
        vec("Search") {
            path(fill = black) {
                moveTo(15.5f, 14f); horizontalLineToRelative(-0.79f); lineToRelative(-0.28f, -0.27f)
                curveTo(15.41f, 12.59f, 16f, 11.11f, 16f, 9.5f)
                curveTo(16f, 5.91f, 13.09f, 3f, 9.5f, 3f)
                reflectiveCurveTo(3f, 5.91f, 3f, 9.5f)
                reflectiveCurveTo(5.91f, 16f, 9.5f, 16f)
                curveToRelative(1.61f, 0f, 3.09f, -0.59f, 4.23f, -1.57f)
                lineToRelative(0.27f, 0.28f); verticalLineToRelative(0.79f)
                lineToRelative(5f, 4.99f); lineTo(20.49f, 19f); lineToRelative(-4.99f, -5f); close()
                moveTo(9.5f, 14f); curveTo(7.01f, 14f, 5f, 11.99f, 5f, 9.5f)
                reflectiveCurveTo(7.01f, 5f, 9.5f, 5f)
                reflectiveCurveTo(14f, 7.01f, 14f, 9.5f)
                reflectiveCurveTo(11.99f, 14f, 9.5f, 14f); close()
            }
        }
    }

    val Shorts: ImageVector by lazy {
        vec("Shorts") {
            path(fill = black) {
                moveTo(8f, 5f); verticalLineTo(19f); lineTo(19f, 12f); close()
            }
        }
    }

    val Library: ImageVector by lazy {
        vec("Library") {
            path(fill = black, pathFillType = PathFillType.NonZero) {
                moveTo(4f, 6f); horizontalLineTo(2f); verticalLineTo(20f)
                curveTo(2f, 21.1f, 2.9f, 22f, 4f, 22f)
                horizontalLineTo(18f); verticalLineTo(20f); horizontalLineTo(4f); verticalLineTo(6f); close()
                moveTo(20f, 2f); horizontalLineTo(8f)
                curveTo(6.9f, 2f, 6f, 2.9f, 6f, 4f)
                verticalLineTo(16f); curveTo(6f, 17.1f, 6.9f, 18f, 8f, 18f)
                horizontalLineTo(20f); curveTo(21.1f, 18f, 22f, 17.1f, 22f, 16f)
                verticalLineTo(4f); curveTo(22f, 2.9f, 21.1f, 2f, 20f, 2f); close()
                moveTo(12f, 14.5f); verticalLineTo(5.5f); lineTo(18f, 10f); close()
            }
        }
    }

    val Settings: ImageVector by lazy {
        vec("Settings") {
            path(fill = black) {
                moveTo(19.14f, 12.94f)
                curveToRelative(0.04f, -0.3f, 0.06f, -0.61f, 0.06f, -0.94f)
                curveToRelative(0f, -0.32f, -0.02f, -0.64f, -0.07f, -0.94f)
                lineToRelative(2.03f, -1.58f); curveToRelative(0.18f, -0.14f, 0.23f, -0.41f, 0.12f, -0.61f)
                lineToRelative(-1.92f, -3.32f); curveToRelative(-0.12f, -0.22f, -0.37f, -0.29f, -0.59f, -0.22f)
                lineToRelative(-2.39f, 0.96f); curveToRelative(-0.5f, -0.38f, -1.03f, -0.7f, -1.62f, -0.94f)
                lineTo(14.4f, 2.81f); curveToRelative(-0.04f, -0.24f, -0.24f, -0.41f, -0.48f, -0.41f)
                horizontalLineToRelative(-3.84f); curveToRelative(-0.24f, 0f, -0.43f, 0.17f, -0.47f, 0.41f)
                lineTo(9.25f, 5.35f); curveTo(8.66f, 5.59f, 8.12f, 5.92f, 7.63f, 6.29f)
                lineTo(5.24f, 5.33f); curveToRelative(-0.22f, -0.08f, -0.47f, 0f, -0.59f, 0.22f)
                lineTo(2.74f, 8.87f); curveTo(2.62f, 9.08f, 2.66f, 9.34f, 2.86f, 9.48f)
                lineToRelative(2.03f, 1.58f); curveTo(4.84f, 11.36f, 4.8f, 11.69f, 4.8f, 12f)
                reflectiveCurveToRelative(0.02f, 0.64f, 0.07f, 0.94f)
                lineToRelative(-2.03f, 1.58f); curveToRelative(-0.18f, 0.14f, -0.23f, 0.41f, -0.12f, 0.61f)
                lineToRelative(1.92f, 3.32f); curveToRelative(0.12f, 0.22f, 0.37f, 0.29f, 0.59f, 0.22f)
                lineToRelative(2.39f, -0.96f); curveToRelative(0.5f, 0.38f, 1.03f, 0.7f, 1.62f, 0.94f)
                lineToRelative(0.36f, 2.54f); curveToRelative(0.05f, 0.24f, 0.24f, 0.41f, 0.48f, 0.41f)
                horizontalLineToRelative(3.84f); curveToRelative(0.24f, 0f, 0.44f, -0.17f, 0.47f, -0.41f)
                lineToRelative(0.36f, -2.54f); curveToRelative(0.59f, -0.24f, 1.13f, -0.56f, 1.62f, -0.94f)
                lineToRelative(2.39f, 0.96f); curveToRelative(0.22f, 0.08f, 0.47f, 0f, 0.59f, -0.22f)
                lineToRelative(1.92f, -3.32f); curveToRelative(0.12f, -0.22f, 0.07f, -0.47f, -0.12f, -0.61f)
                lineTo(19.14f, 12.94f); close()
                moveTo(12f, 15.6f); curveToRelative(-1.98f, 0f, -3.6f, -1.62f, -3.6f, -3.6f)
                reflectiveCurveToRelative(1.62f, -3.6f, 3.6f, -3.6f)
                reflectiveCurveToRelative(3.6f, 1.62f, 3.6f, 3.6f)
                reflectiveCurveToRelative(-1.62f, 3.6f, -3.6f, 3.6f); close()
            }
        }
    }

    val Mic: ImageVector by lazy {
        vec("Mic") {
            path(fill = black) {
                moveTo(12f, 14f); curveToRelative(1.66f, 0f, 3f, -1.34f, 3f, -3f)
                verticalLineTo(5f); curveToRelative(0f, -1.66f, -1.34f, -3f, -3f, -3f)
                reflectiveCurveTo(9f, 3.34f, 9f, 5f); verticalLineTo(11f)
                curveToRelative(0f, 1.66f, 1.34f, 3f, 3f, 3f); close()
                moveTo(17.3f, 11f); curveToRelative(0f, 3f, -2.54f, 5.1f, -5.3f, 5.1f)
                reflectiveCurveTo(6.7f, 14f, 6.7f, 11f); horizontalLineTo(5f)
                curveToRelative(0f, 3.41f, 2.72f, 6.23f, 6f, 6.72f)
                verticalLineTo(21f); horizontalLineTo(13f); verticalLineTo(17.72f)
                curveToRelative(3.28f, -0.48f, 6f, -3.3f, 6f, -6.72f); horizontalLineTo(17.3f); close()
            }
        }
    }
}
