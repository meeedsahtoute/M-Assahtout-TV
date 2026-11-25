package com.streamtube.tv.ui

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.streamtube.tv.core.ProgressEntryDto
import com.streamtube.tv.core.VideoItemDto

fun formatDurationSecs(secs: Long): String {
    val s = secs.coerceAtLeast(0L)
    val h = s / 3600
    val m = (s % 3600) / 60
    val rem = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, rem) else "%d:%02d".format(m, rem)
}

fun formatCompactCount(n: Long): String = when {
    n >= 1_000_000_000L -> "%.1fB".format(n / 1e9).replace(".0B", "B")
    n >= 1_000_000L -> "%.1fM".format(n / 1e6).replace(".0M", "M")
    n >= 1_000L -> "%.1fK".format(n / 1e3).replace(".0K", "K")
    else -> n.toString()
}

@Composable
fun TvButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    accent: Color = MaterialTheme.colorScheme.primary
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) accent else StColors.SurfaceElevated,
            focusedContainerColor = if (selected) accent else StColors.SurfaceFocus,
            contentColor = StColors.TextPrimary,
            focusedContentColor = Color.White
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, if (selected) accent else StColors.Border)),
            focusedBorder = Border(BorderStroke(2.dp, Color.White))
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f)
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

/**
 * TV-friendly single-line text input that lets D-Pad directional keys and Enter
 * escape focus cleanly instead of trapping remote navigation inside EditText.
 */
@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    imeAction: ImeAction = ImeAction.Done,
    onSubmit: (() -> Unit)? = null
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, color = StColors.TextMuted) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = imeAction),
        keyboardActions = KeyboardActions(
            onSearch = {
                onSubmit?.invoke()
                keyboardController?.hide()
                focusManager.moveFocus(FocusDirection.Down)
            },
            onDone = {
                onSubmit?.invoke()
                keyboardController?.hide()
                focusManager.moveFocus(FocusDirection.Right)
            }
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = StColors.TextPrimary,
            focusedBorderColor = Color.White,
            unfocusedBorderColor = StColors.Border,
            focusedContainerColor = StColors.SurfaceElevated,
            unfocusedContainerColor = StColors.Surface
        ),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.onPreviewKeyEvent { ev ->
            if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (ev.key) {
                Key.DirectionDown -> {
                    keyboardController?.hide()
                    focusManager.moveFocus(FocusDirection.Down)
                    true
                }
                Key.DirectionUp -> {
                    keyboardController?.hide()
                    focusManager.moveFocus(FocusDirection.Up)
                    true
                }
                Key.DirectionRight -> {
                    keyboardController?.hide()
                    focusManager.moveFocus(FocusDirection.Right)
                    true
                }
                Key.DirectionLeft -> {
                    keyboardController?.hide()
                    focusManager.moveFocus(FocusDirection.Left)
                    true
                }
                Key.Enter, Key.NumPadEnter -> {
                    if (onSubmit != null) {
                        onSubmit()
                        keyboardController?.hide()
                        focusManager.moveFocus(FocusDirection.Down)
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
    )
}

@Composable
fun VideoCard(
    video: VideoItemDto,
    progress: ProgressEntryDto? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = MaterialTheme.colorScheme.primary
    val resumeFraction = remember(progress) {
        if (progress != null && progress.duration > 0.0) {
            (progress.progress / progress.duration).toFloat().coerceIn(0f, 1f)
        } else 0f
    }

    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = StColors.Surface,
            focusedContainerColor = StColors.SurfaceElevated,
            contentColor = StColors.TextPrimary,
            focusedContentColor = Color.White
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, Color.Transparent)),
            focusedBorder = Border(BorderStroke(2.5.dp, Color.White))
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                    .background(Color(0xFF1A1A22))
            ) {
                AsyncImage(
                    model = video.thumbnail,
                    contentDescription = video.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                if (video.isLive) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                            .background(Color(0xFFDC2626), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text("LIVE", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                } else if (!video.durationText.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                            .background(Color.Black.copy(alpha = 0.82f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(video.durationText, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }
                }
                if (resumeFraction > 0.02f) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(3.5.dp)
                            .background(Color.White.copy(alpha = 0.25f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(resumeFraction)
                                .height(3.5.dp)
                                .background(accent)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top
            ) {
                if (!video.channelAvatar.isNullOrBlank()) {
                    AsyncImage(
                        model = video.channelAvatar,
                        contentDescription = video.channelName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(StColors.SurfaceElevated)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = video.title.ifBlank { video.id },
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 18.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = StColors.TextPrimary
                    )
                    Spacer(Modifier.height(4.dp))
                    val meta = listOfNotNull(
                        video.channelName.takeIf { it.isNotBlank() },
                        video.viewsText,
                        video.publishedText
                    ).joinToString(" • ")
                    if (meta.isNotBlank()) {
                        Text(
                            text = meta,
                            fontSize = 12.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = StColors.TextSecondary
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LoadingPane(message: String = "Loading…") {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Text(message, color = StColors.TextSecondary, fontSize = 14.sp)
        }
    }
}

@Composable
fun ErrorPane(message: String, onRetry: (() -> Unit)? = null) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(24.dp)
        ) {
            Text(message, color = StColors.Error, fontSize = 14.sp)
            if (onRetry != null) {
                TvButton(label = "Retry", onClick = onRetry, selected = true)
            }
        }
    }
}

@Composable
fun QrCodeImage(content: String, size: Dp = 180.dp, modifier: Modifier = Modifier) {
    val bitmap = remember(content) { generateQrBitmap(content, 512) }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "QR Code",
            modifier = modifier
                .size(size)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White)
                .padding(6.dp)
        )
    }
}

private fun generateQrBitmap(content: String, sizePx: Int): Bitmap? {
    return runCatching {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bmp.setPixel(x, y, if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        bmp
    }.getOrNull()
}
