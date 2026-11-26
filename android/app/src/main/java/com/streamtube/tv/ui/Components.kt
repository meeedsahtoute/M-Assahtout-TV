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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import coil.request.ImageRequest
import coil.size.Precision
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.streamtube.tv.core.ProgressEntryDto
import com.streamtube.tv.core.VideoItemDto

private val CardShape = RoundedCornerShape(12.dp)
private val ThumbShape = RoundedCornerShape(10.dp)
private val BadgeShape = RoundedCornerShape(4.dp)
private val ButtonShape = RoundedCornerShape(8.dp)
private val CardFocusBorder = Border(BorderStroke(2.dp, Color.White))
private val CardUnfocusedBorder = Border(BorderStroke(2.dp, Color.Transparent))
private val CardScale = 1.04f

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

/**
 * YouTube desktop-style horizontal category / filter chip (#272727 / #F1F1F1 active).
 */
@Composable
fun TvChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(ButtonShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) StColors.ChipActiveBg else StColors.Chip,
            focusedContainerColor = if (selected) Color.White else StColors.Hover,
            contentColor = if (selected) StColors.ChipActiveFg else StColors.TextPrimary,
            focusedContentColor = if (selected) Color.Black else Color.White
        ),
        border = ClickableSurfaceDefaults.border(
            border = CardUnfocusedBorder,
            focusedBorder = CardFocusBorder
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = CardScale)
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
        )
    }
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
        shape = ClickableSurfaceDefaults.shape(ButtonShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) accent else StColors.Chip,
            focusedContainerColor = if (selected) accent else StColors.Hover,
            contentColor = StColors.TextPrimary,
            focusedContentColor = Color.White
        ),
        border = ClickableSurfaceDefaults.border(
            border = CardUnfocusedBorder,
            focusedBorder = CardFocusBorder
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = CardScale)
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
        shape = ButtonShape,
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

/**
 * Flat, zero-overdraw 16:9 YouTube desktop/TV card with strictly downsampled
 * HARDWARE / RGB_565 bitmap decoding and GPU-only 1.04x focus scale.
 */
@Composable
fun VideoCard(
    video: VideoItemDto,
    progress: ProgressEntryDto? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val accent = MaterialTheme.colorScheme.primary
    val resumeFraction = remember(progress) {
        if (progress != null && progress.duration > 0.0) {
            (progress.progress / progress.duration).toFloat().coerceIn(0f, 1f)
        } else 0f
    }
    val thumbUrl = video.compactThumbnail?.takeIf { it.isNotBlank() } ?: video.thumbnail
    val thumbReq = remember(thumbUrl) {
        ImageRequest.Builder(context)
            .data(thumbUrl)
            .size(480, 270)
            .precision(Precision.INEXACT)
            .allowHardware(true)
            .allowRgb565(true)
            .crossfade(false)
            .build()
    }
    val avatarUrl = video.channelAvatar?.takeIf { it.isNotBlank() }
    val avatarReq = remember(avatarUrl) {
        avatarUrl?.let {
            ImageRequest.Builder(context)
                .data(it)
                .size(96, 96)
                .precision(Precision.INEXACT)
                .allowHardware(true)
                .allowRgb565(true)
                .crossfade(false)
                .build()
        }
    }
    val metaText = remember(video.channelName, video.viewsText, video.publishedText) {
        listOfNotNull(
            video.channelName.takeIf { it.isNotBlank() },
            video.viewsText?.takeIf { it.isNotBlank() },
            video.publishedText?.takeIf { it.isNotBlank() }
        ).joinToString(" • ")
    }

    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(CardShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = StColors.Surface,
            contentColor = StColors.TextPrimary,
            focusedContentColor = Color.White
        ),
        border = ClickableSurfaceDefaults.border(
            border = CardUnfocusedBorder,
            focusedBorder = CardFocusBorder
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = CardScale)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(5.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(ThumbShape)
                    .background(StColors.Surface)
            ) {
                AsyncImage(
                    model = thumbReq,
                    contentDescription = video.title,
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.Low,
                    modifier = Modifier.fillMaxSize()
                )
                if (video.isLive) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .background(StColors.LiveRed, BadgeShape)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text("LIVE", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                } else if (!video.durationText.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .background(Color(0xCC000000), BadgeShape)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(video.durationText, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (resumeFraction > 0.01f) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(Color(0x99999999))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(resumeFraction)
                                .height(4.dp)
                                .background(accent)
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top
            ) {
                if (avatarReq != null) {
                    AsyncImage(
                        model = avatarReq,
                        contentDescription = video.channelName,
                        contentScale = ContentScale.Crop,
                        filterQuality = FilterQuality.Low,
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
                        lineHeight = 19.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = StColors.TextPrimary
                    )
                    if (metaText.isNotBlank()) {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = metaText,
                            fontSize = 12.sp,
                            maxLines = 1,
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
            if (message.isNotEmpty()) {
                Text(message, color = StColors.TextSecondary, fontSize = 14.sp)
            }
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
    val bitmap = remember(content) { generateQrBitmap(content, 384) }
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
