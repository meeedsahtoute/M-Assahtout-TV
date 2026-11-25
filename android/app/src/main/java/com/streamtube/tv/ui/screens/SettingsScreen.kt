package com.streamtube.tv.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.streamtube.tv.BuildConfig
import com.streamtube.tv.StreamTubeApp
import com.streamtube.tv.core.AuthStatusDto
import com.streamtube.tv.core.DeviceCodeDto
import com.streamtube.tv.core.NativeBridge
import com.streamtube.tv.core.SettingsDto
import com.streamtube.tv.core.UpdateCheckDto
import com.streamtube.tv.ui.QrCodeImage
import com.streamtube.tv.ui.StColors
import com.streamtube.tv.ui.TvButton
import com.streamtube.tv.ui.TvTextField
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun SettingsScreen(
    onSettingsChanged: (SettingsDto) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(SettingsDto()) }
    var authStatus by remember { mutableStateOf(AuthStatusDto()) }
    var deviceCode by remember { mutableStateOf<DeviceCodeDto?>(null) }
    var authMessage by remember { mutableStateOf<String?>(null) }
    var proxyInput by remember { mutableStateOf("") }
    var updateInfo by remember { mutableStateOf<UpdateCheckDto?>(null) }
    var updateStatus by remember { mutableStateOf<String?>(null) }
    var downloadingUpdate by remember { mutableStateOf(false) }

    val companionUrl = remember { StreamTubeApp.instance.companionServer.getLanUrl() }

    fun save(transform: (SettingsDto) -> SettingsDto) {
        val next = transform(settings)
        settings = next
        onSettingsChanged(next)
        scope.launch {
            NativeBridge.updateSettings(next).onSuccess { saved ->
                settings = saved
                onSettingsChanged(saved)
            }
        }
    }

    LaunchedEffect(Unit) {
        NativeBridge.getSettings().onSuccess {
            settings = it
            proxyInput = it.proxy
        }
        NativeBridge.getAuthStatus().onSuccess { authStatus = it }
    }

    // Poll Google TV Device login while deviceCode is active
    LaunchedEffect(deviceCode) {
        val dc = deviceCode ?: return@LaunchedEffect
        var intervalMs = (dc.interval.coerceAtLeast(5)) * 1000L
        while (true) {
            delay(intervalMs)
            val res = NativeBridge.pollTvLogin(dc)
            val poll = res.getOrNull()
            if (res.isFailure) {
                authMessage = res.exceptionOrNull()?.message ?: "Sign-in failed"
                deviceCode = null
                break
            }
            when (poll?.status) {
                "done" -> {
                    deviceCode = null
                    authMessage = "Signed in as ${poll.account?.name ?: "YouTube account"}"
                    NativeBridge.getAuthStatus().onSuccess { authStatus = it }
                    break
                }
                "slow_down" -> intervalMs += 2000L
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { deviceCode = null }
    }

    LazyColumn(
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Text("Settings", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = StColors.TextPrimary)
        }

        // 1. Phone-to-TV Companion Web Server (QR Code)
        item {
            SettingsCard("Phone-to-TV Companion Server (LAN Port 8787)") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    QrCodeImage(content = companionUrl, size = 156.dp)
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(companionUrl, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Scan this QR code on your phone or open the URL in any browser on the same Wi-Fi/LAN to:\n• Paste YouTube links directly to your TV\n• Upload cookies.txt for Google account sync\n• Import NewPipe / OPML / YouTube Takeout subscriptions",
                            color = StColors.TextSecondary,
                            fontSize = 13.sp,
                            lineHeight = 19.sp
                        )
                    }
                }
            }
        }

        // 2. Account Sign-In & Sync
        item {
            SettingsCard("YouTube Account & Sync") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = if (authStatus.tvSignedIn) {
                            "TV Account: Signed in as ${authStatus.tvAccount?.name ?: "YouTube"}"
                        } else {
                            "TV Account: Not signed in (personal feeds & playlists)"
                        },
                        color = StColors.TextPrimary,
                        fontSize = 14.sp
                    )
                    Text(
                        text = if (authStatus.webConnected) {
                            "Browser Cookies (cookies.txt): Connected (${authStatus.webAccountName ?: "Active"})"
                        } else {
                            "Browser Cookies (cookies.txt): Not connected (upload via Companion QR above)"
                        },
                        color = StColors.TextSecondary,
                        fontSize = 13.sp
                    )
                    authMessage?.let { Text(it, color = StColors.Success, fontSize = 13.sp) }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (!authStatus.tvSignedIn) {
                            TvButton(
                                label = "Sign in with Google TV Code",
                                selected = true,
                                onClick = {
                                    scope.launch {
                                        authMessage = "Requesting code…"
                                        NativeBridge.startTvLogin().fold(
                                            onSuccess = {
                                                deviceCode = it
                                                authMessage = null
                                            },
                                            onFailure = { e -> authMessage = e.message }
                                        )
                                    }
                                }
                            )
                        } else {
                            TvButton(
                                label = "Sync Account Subs & History",
                                selected = true,
                                onClick = {
                                    scope.launch {
                                        authMessage = "Syncing…"
                                        NativeBridge.syncTvAccount().fold(
                                            onSuccess = { r ->
                                                authMessage = "Synced +${r.subscriptionsAdded} subs, +${r.historyAdded} history, +${r.searchesAdded} searches"
                                            },
                                            onFailure = { e -> authMessage = e.message }
                                        )
                                    }
                                }
                            )
                            TvButton(
                                label = "Sign Out TV",
                                onClick = {
                                    scope.launch {
                                        NativeBridge.signOutTv()
                                        NativeBridge.getAuthStatus().onSuccess { authStatus = it }
                                    }
                                }
                            )
                        }
                        if (authStatus.webConnected) {
                            TvButton(
                                label = "Disconnect Cookies",
                                onClick = {
                                    scope.launch {
                                        NativeBridge.disconnectWebSession()
                                        NativeBridge.getAuthStatus().onSuccess { authStatus = it }
                                    }
                                }
                            )
                        }
                    }

                    deviceCode?.let { dc ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(StColors.Background, RoundedCornerShape(10.dp))
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            QrCodeImage(content = dc.verificationUrl, size = 120.dp)
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Go to ${dc.verificationUrl}", color = StColors.TextSecondary, fontSize = 13.sp)
                                Text(dc.userCode, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                                TvButton(label = "Cancel Sign-In", onClick = { deviceCode = null })
                            }
                        }
                    }
                }
            }
        }

        // 3. Playback & Quality
        item {
            SettingsCard("Playback & Stream Extraction") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Default Quality", color = StColors.TextSecondary, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (q in listOf(360, 480, 720, 1080, 1440, 2160, 4320)) {
                            TvButton(
                                label = if (q >= 2160) "${q}p 4K+" else "${q}p",
                                selected = settings.defaultQuality == q,
                                onClick = { save { it.copy(defaultQuality = q) } }
                            )
                        }
                    }
                    Text("Preferred Codec", color = StColors.TextSecondary, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((k, label) in listOf("av1" to "AV1", "vp9" to "VP9", "h264" to "H.264")) {
                            TvButton(
                                label = label,
                                selected = settings.codecPref == k,
                                onClick = { save { it.copy(codecPref = k) } }
                            )
                        }
                    }
                    Text("Stream Backend", color = StColors.TextSecondary, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((k, label) in listOf(
                            "auto" to "Auto (visionOS HLS)",
                            "android_vr" to "Android VR (Adaptive)",
                            "visionos" to "visionOS",
                            "ios" to "iOS"
                        )) {
                            TvButton(
                                label = label,
                                selected = settings.streamBackend == k,
                                onClick = { save { it.copy(streamBackend = k) } }
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TvButton(
                            label = "Resume Playback: ${onOff(settings.resumePlayback)}",
                            selected = settings.resumePlayback,
                            onClick = { save { it.copy(resumePlayback = !it.resumePlayback) } }
                        )
                        TvButton(
                            label = "Autoplay Next: ${onOff(settings.autoplayNext)}",
                            selected = settings.autoplayNext,
                            onClick = { save { it.copy(autoplayNext = !it.autoplayNext) } }
                        )
                        TvButton(
                            label = "Background Audio: ${onOff(settings.backgroundPlay)}",
                            selected = settings.backgroundPlay,
                            onClick = { save { it.copy(backgroundPlay = !it.backgroundPlay) } }
                        )
                        TvButton(
                            label = "Original Audio: ${onOff(settings.originalAudio)}",
                            selected = settings.originalAudio,
                            onClick = { save { it.copy(originalAudio = !it.originalAudio) } }
                        )
                    }
                }
            }
        }

        // 4. SponsorBlock, DeArrow & Return YouTube Dislike
        item {
            SettingsCard("SponsorBlock, DeArrow & Return YouTube Dislike") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TvButton(
                            label = "SponsorBlock: ${onOff(settings.sponsorblock)}",
                            selected = settings.sponsorblock,
                            onClick = { save { it.copy(sponsorblock = !it.sponsorblock) } }
                        )
                        TvButton(
                            label = "SB Toast: ${onOff(settings.sbNotify)}",
                            selected = settings.sbNotify,
                            onClick = { save { it.copy(sbNotify = !it.sbNotify) } }
                        )
                        TvButton(
                            label = "DeArrow Titles: ${onOff(settings.dearrowTitles)}",
                            selected = settings.dearrowTitles,
                            onClick = { save { it.copy(dearrowTitles = !it.dearrowTitles) } }
                        )
                        TvButton(
                            label = "DeArrow Thumbs: ${onOff(settings.dearrowThumbnails)}",
                            selected = settings.dearrowThumbnails,
                            onClick = { save { it.copy(dearrowThumbnails = !it.dearrowThumbnails) } }
                        )
                        TvButton(
                            label = "RYD Dislikes: ${onOff(settings.ryd)}",
                            selected = settings.ryd,
                            onClick = { save { it.copy(ryd = !it.ryd) } }
                        )
                    }
                    Text("SponsorBlock Category Actions (click to cycle skip -> mute -> show -> off)", color = StColors.TextSecondary, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val cats = listOf("sponsor" to "Sponsor", "selfpromo" to "Self-Promo", "interaction" to "Interaction", "intro" to "Intro", "outro" to "Outro")
                        val cycle = listOf("skip", "mute", "show", "off")
                        for ((cat, label) in cats) {
                            val cur = settings.sbActions[cat] ?: "show"
                            TvButton(
                                label = "$label: $cur",
                                selected = cur == "skip",
                                onClick = {
                                    val next = cycle[(cycle.indexOf(cur) + 1) % cycle.size]
                                    save { it.copy(sbActions = it.sbActions + (cat to next)) }
                                }
                            )
                        }
                    }
                }
            }
        }

        // 5. Content Filters & Network
        item {
            SettingsCard("Filters & Network") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TvButton(
                            label = "Hide Shorts: ${onOff(settings.hideShorts)}",
                            selected = settings.hideShorts,
                            onClick = { save { it.copy(hideShorts = !it.hideShorts) } }
                        )
                        TvButton(
                            label = "Hide Live: ${onOff(settings.hideLive)}",
                            selected = settings.hideLive,
                            onClick = { save { it.copy(hideLive = !it.hideLive) } }
                        )
                        TvButton(
                            label = "Hide Comments: ${onOff(settings.hideComments)}",
                            selected = settings.hideComments,
                            onClick = { save { it.copy(hideComments = !it.hideComments) } }
                        )
                        TvButton(
                            label = "IPv4 Only: ${onOff(settings.ipv4Only)}",
                            selected = settings.ipv4Only,
                            onClick = { save { it.copy(ipv4Only = !it.ipv4Only) } }
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TvTextField(
                            value = proxyInput,
                            onValueChange = { proxyInput = it },
                            placeholder = "Proxy URL (e.g. socks5://127.0.0.1:1080 or http://...)",
                            onSubmit = { save { it.copy(proxy = proxyInput.trim()) } },
                            modifier = Modifier.weight(1f)
                        )
                        TvButton(
                            label = "Save Proxy",
                            onClick = { save { it.copy(proxy = proxyInput.trim()) } }
                        )
                    }
                }
            }
        }

        // 6. GitHub Releases In-App Updater
        item {
            SettingsCard("Updates (M. ASSAHTOUT TV)") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Installed version: ${BuildConfig.VERSION_NAME}",
                        color = StColors.TextSecondary,
                        fontSize = 13.sp
                    )
                    updateStatus?.let { Text(it, color = StColors.TextPrimary, fontSize = 13.sp) }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TvButton(
                            label = "Check GitHub Releases",
                            selected = true,
                            onClick = {
                                scope.launch {
                                    updateStatus = "Checking GitHub Releases…"
                                    NativeBridge.checkGitHubUpdate(BuildConfig.VERSION_NAME).fold(
                                        onSuccess = { rel ->
                                            updateInfo = rel
                                            updateStatus = if (rel.hasUpdate && rel.apkUrl != null) {
                                                "Update available: ${rel.name} (${rel.tag})"
                                            } else if (rel.apkUrl != null) {
                                                "Up to date (latest release: ${rel.tag})"
                                            } else {
                                                "No APK release published yet (${rel.page})"
                                            }
                                        },
                                        onFailure = { e -> updateStatus = "Check failed: ${e.message}" }
                                    )
                                }
                            }
                        )
                        val apkUrl = updateInfo?.apkUrl
                        if (updateInfo?.hasUpdate == true && apkUrl != null) {
                            TvButton(
                                label = if (downloadingUpdate) "Downloading APK…" else "Download & Install APK",
                                selected = true,
                                onClick = {
                                    if (!downloadingUpdate) {
                                        scope.launch {
                                            downloadingUpdate = true
                                            updateStatus = "Downloading ${updateInfo?.apkName ?: "update.apk"}…"
                                            val dest = File(context.cacheDir, "M-Assahtout-TV-Update.apk").absolutePath
                                            NativeBridge.downloadApk(apkUrl, dest, updateInfo?.sha256).fold(
                                                onSuccess = { path ->
                                                    downloadingUpdate = false
                                                    updateStatus = "Downloaded! Launching Android installer…"
                                                    launchApkInstaller(context, File(path))
                                                },
                                                onFailure = { e ->
                                                    downloadingUpdate = false
                                                    updateStatus = "Download failed: ${e.message}"
                                                }
                                            )
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        // 7. About
        item {
            Surface(
                onClick = {},
                modifier = Modifier.fillMaxWidth(),
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = StColors.Surface,
                    focusedContainerColor = StColors.SurfaceElevated,
                    contentColor = Color.White,
                    focusedContentColor = Color.White
                ),
                border = ClickableSurfaceDefaults.border(
                    border = Border(BorderStroke(1.dp, Color.Transparent)),
                    focusedBorder = Border(BorderStroke(2.dp, Color.White))
                )
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("About", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    Text(
                        "M. ASSAHTOUT TV",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Developed by Mohammed Assahtout",
                        color = StColors.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "Version ${BuildConfig.VERSION_NAME}",
                        color = StColors.TextSecondary,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

private fun onOff(v: Boolean): String = if (v) "ON" else "OFF"

private fun launchApkInstaller(context: android.content.Context, apkFile: File) {
    val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
    } else {
        Uri.fromFile(apkFile)
    }
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(intent)
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(StColors.Surface, RoundedCornerShape(14.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        content()
    }
}
