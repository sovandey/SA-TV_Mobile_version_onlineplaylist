package com.bengaliptv

import android.Manifest
import android.app.Activity
import android.app.PictureInPictureParams
import android.app.UiModeManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.util.Rational
import android.view.KeyEvent
import android.widget.Toast
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.media.AudioManager
import android.provider.Settings
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.work.*
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.bengaliptv.data.Channel
import com.bengaliptv.data.M3uParser
import com.bengaliptv.data.PlaylistSource
import com.bengaliptv.data.RemoteSources
import com.bengaliptv.work.PlaylistMonitorWorker
import com.bengaliptv.data.RemoteConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val Bg = Color(0xFF101827)
private val Panel = Color(0xFF192436)
private val PanelAlt = Color(0xFF202D42)
private val Accent = Color(0xFF13C5D4)
private val Muted = Color(0xFF9AA8BC)
private val LiveRed = Color(0xFFE84F70)

private val categories = listOf(
    "Indian Bengali" to "🇮🇳",
    "Bangladesh" to "🇧🇩",
    "Hindi" to "🎬",
    "Sports" to "🏆",
    "FM Radio" to "📻",
    "Kids" to "🧸"
)
private val remoteGroups = setOf("Hindi", "Sports", "FM Radio", "Kids")

// Shared with MainActivity (pop-up video window + TV remote keys)
private var inPipMode by mutableStateOf(false)
private var pipEligible = false
private var pipRatio = 16f / 9f
private var playerKeyHandler: ((Int) -> Boolean)? = null

private data class QualityOption(val height: Int, val bitrate: Int, val group: TrackGroup, val index: Int)

private fun qLabel(h: Int): String = when {
    h >= 2160 -> "4K"
    h >= 1440 -> "1440p"
    else -> "${h}p"
}

private fun isTvDevice(context: Context): Boolean {
    val um = context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
    return um.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }

        val request = PeriodicWorkRequestBuilder<PlaylistMonitorWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "satv_playlist_monitor", ExistingPeriodicWorkPolicy.KEEP, request
        )
        val base = loadBundled()
        setContent { SATVApp(base) }
    }

    /** Small pop-up video window so you can use other apps. */
    fun enterPip() {
        if (Build.VERSION.SDK_INT < 26) return
        try {
            val r = pipRatio.coerceIn(0.45f, 2.3f)
            enterPictureInPictureMode(
                PictureInPictureParams.Builder()
                    .setAspectRatio(Rational((r * 1000).toInt(), 1000))
                    .build()
            )
        } catch (e: Exception) {
            // not supported on this device
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (pipEligible) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPipMode = isInPictureInPictureMode
    }

    /** TV remote: up/down = change channel (handled before the focus system sees the key). */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && playerKeyHandler?.invoke(event.keyCode) == true) return true
        return super.dispatchKeyEvent(event)
    }

    private fun loadBundled(): List<Channel> {
        val all = mutableListOf<Channel>()
        for (file in listOf("curated.m3u", "bengali-51-channels.m3u", "master.m3u")) {
            try {
                val text = assets.open(file).bufferedReader().use { it.readText() }
                all += M3uParser.parse(text, PlaylistSource(file, "local://$file", 0))
            } catch (e: Exception) {
                // skip missing asset
            }
        }
        return all.distinctBy { it.url }
            .distinctBy { it.name.lowercase().trim() }
            .filter { channelCategory(it) != null }
    }
}

private fun channelCategory(channel: Channel): String? {
    val group = channel.group.orEmpty()
    if (categories.any { it.first == group }) return group
    val id = channel.id.orEmpty().lowercase()
    val country = channel.country.orEmpty().uppercase()
    val name = channel.name.lowercase()
    val banglaTerms = listOf("bangla", "bengali", "ananda", "kolkata", "jalsha", "rupasi",
        "ruposhi", "aakaash", "sangeet", "sananda", "tara tv", "mon bangla", "rongeen",
        "raatdin", "zillarbarta", "amar bangla")
    return when {
        group.equals("Bengali Kids", true) || name.contains("cartoon") -> "Kids"
        group.equals("Bangladesh", true) || country == "BD" || id.endsWith(".bd") || id.contains(".bd@") -> "Bangladesh"
        id.startsWith("ddnational") || name == "dd national hd" -> "Hindi"
        country == "CA" -> null
        channel.language.equals("ben", ignoreCase = true) -> "Indian Bengali"
        banglaTerms.any { name.contains(it) } -> "Indian Bengali"
        group.startsWith("Bengali", true) -> "Indian Bengali"
        id.contains(".in") && name != "news live" -> "Indian Bengali"
        else -> null
    }
}

private fun categoryEmoji(cat: String?): String = when (cat) {
    "Sports" -> "🏆"
    "FM Radio" -> "📻"
    "Kids" -> "🧸"
    else -> "📺"
}

// ───────────────────────── App shell + splash ─────────────────────────

@Composable
private fun SATVApp(bundled: List<Channel>) {
    val context = LocalContext.current
    var splash by remember { mutableStateOf(true) }
    var curated by remember { mutableStateOf<List<Channel>?>(null) }
    var config by remember { mutableStateOf<RemoteConfig?>(null) }
    var remote by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var reloadKey by remember { mutableStateOf(0) }
    var updateDismissed by remember { mutableStateOf(false) }

    LaunchedEffect(reloadKey) {
        loading = true
        val app = context.applicationContext
        coroutineScope {
            // 1) your playlist + settings from GitHub
            launch {
                val cfgJob = async(Dispatchers.IO) { RemoteSources.loadConfig(app) }
                val curatedJob = async(Dispatchers.IO) { RemoteSources.loadCurated(app) }
                config = cfgJob.await()
                curated = curatedJob.await()
            }
            // 2) extra channels from public lists (Sports / Kids / Hindi / FM)
            launch {
                val acc = mutableListOf<Channel>()
                RemoteSources.loadAll(app) { r ->
                    acc += r
                    remote = acc.toList()
                }
            }
        }
        loading = false
    }
    LaunchedEffect(Unit) {
        delay(2300)
        splash = false
    }

    val channels = remember(bundled, curated, remote, config) {
        val blocked = config?.blocked ?: emptySet()
        val extra = if (config?.autoSources == false) emptyList() else remote
        ((curated ?: bundled) + extra)
            .distinctBy { it.url }
            .distinctBy { it.name.lowercase().trim() }
            .filter { it.name.lowercase().trim() !in blocked }
    }

    val scope = rememberCoroutineScope()
    val checkUpdate: () -> Unit = {
        scope.launch {
            val cfg = withContext(Dispatchers.IO) {
                RemoteSources.loadConfig(context.applicationContext, useCache = false)
            }
            if (cfg == null) {
                Toast.makeText(context, "Could not check for updates. Please check your internet.",
                    Toast.LENGTH_LONG).show()
            } else {
                config = cfg
                if (cfg.latestVersionCode > BuildConfig.VERSION_CODE) updateDismissed = false
                else Toast.makeText(context, "You have the latest version (v${BuildConfig.VERSION_NAME}).",
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Accent, background = Bg, surface = Panel,
        onBackground = Color.White, onSurface = Color.White
    )) {
        Crossfade(targetState = splash, animationSpec = tween(600), label = "splash") { isSplash ->
            if (isSplash) SplashScreen()
            else SATVHome(channels, loading, onReload = { reloadKey++ }, onCheckUpdate = checkUpdate)
        }
        val cfg = config
        if (!splash && cfg != null && cfg.latestVersionCode > BuildConfig.VERSION_CODE) {
            val force = cfg.minVersionCode > BuildConfig.VERSION_CODE
            if (force || !updateDismissed) {
                UpdateDialog(cfg, force) { updateDismissed = true }
            }
        }
    }
}

@Composable
private fun UpdateDialog(cfg: RemoteConfig, force: Boolean, onLater: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = { if (!force) onLater() },
        properties = DialogProperties(dismissOnBackPress = !force, dismissOnClickOutside = !force),
        title = { Text("Update available") },
        text = {
            Text(
                buildString {
                    append("A new version of SA-TV")
                    if (cfg.latestVersionName.isNotBlank()) append(" (v${cfg.latestVersionName})")
                    append(" is ready.")
                    if (cfg.notes.isNotBlank()) append("\n\n${cfg.notes}")
                    if (cfg.apkUrl.isNotBlank()) append("\n\nDownload link:\n${cfg.apkUrl}")
                    if (force) append("\n\nPlease update to keep watching.")
                }
            )
        },
        confirmButton = {
            TextButton(onClick = {
                if (cfg.apkUrl.isNotBlank()) {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(cfg.apkUrl)))
                    } catch (e: Exception) {
                        Toast.makeText(
                            context,
                            "No browser found. On Android TV open the Downloader app and type:\n${cfg.apkUrl}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }) { Text("Update now") }
        },
        dismissButton = {
            if (!force) TextButton(onClick = onLater) { Text("Later") }
        }
    )
}

private fun reportBroken(context: Context, channel: Channel) {
    val body = "Channel: ${channel.name}\nCategory: ${channelCategory(channel)}\n" +
        "Link: ${channel.url}\nApp version: ${BuildConfig.VERSION_NAME}\n"
    val url = "https://github.com/${BuildConfig.REPO}/issues/new?title=" +
        Uri.encode("Broken: ${channel.name}") + "&body=" + Uri.encode(body)
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: Exception) {
        // no browser available
    }
}

@Composable
private fun SplashScreen() {
    val scale = remember { Animatable(0.9f) }
    LaunchedEffect(Unit) { scale.animateTo(1f, tween(1500)) }
    Box(Modifier.fillMaxSize().background(Color(0xFF0B0F18)), contentAlignment = Alignment.Center) {
        Image(
            painter = painterResource(id = R.drawable.satv_logo),
            contentDescription = "SA-TV",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
        )
    }
}

// ───────────────────────── Home ─────────────────────────

@Composable
private fun SATVHome(
    channels: List<Channel>,
    loading: Boolean,
    onReload: () -> Unit,
    onCheckUpdate: () -> Unit
) {
    val context = LocalContext.current
    val isTv = remember { isTvDevice(context) }
    var selectedSport by remember { mutableStateOf("Indian Bengali") }
    var searchMode by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }
    var playingChannel by remember { mutableStateOf<Channel?>(null) }

    val counts = remember(channels) {
        categories.associate { (n, _) -> n to channels.count { channelCategory(it) == n } }
    }
    val categoryList = remember(channels, selectedSport) {
        channels.filter { channelCategory(it) == selectedSport }
    }

    val current = playingChannel
    if (current != null) {
        LivePlayerScreen(
            channels = categoryList,
            initialChannel = current,
            onBack = { playingChannel = null },
            onChannelChanged = { playingChannel = it }
        )
        return
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        Column(
            Modifier.fillMaxWidth().background(Color(0xFF192337)).statusBarsPadding()
                .padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (searchMode) {
                    OutlinedTextField(
                        value = searchText, onValueChange = { searchText = it },
                        placeholder = { Text("Search channels", color = Muted) },
                        singleLine = true, modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Accent, unfocusedBorderColor = Muted,
                            focusedTextColor = Color.White, unfocusedTextColor = Color.White
                        )
                    )
                } else {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(id = R.drawable.satv_logo),
                            contentDescription = "SA-TV official logo",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp))
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(verticalArrangement = Arrangement.Center) {
                            Text("SA-TV", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Text("IDEAS. STORIES. IMPACT.", color = Color(0xFFE5B85C),
                                fontSize = 8.sp, fontWeight = FontWeight.SemiBold)
                            Text("v${BuildConfig.VERSION_NAME}", color = Muted, fontSize = 10.sp)
                        }
                    }
                }
                HeaderAction("⟳") { onReload() }
                Spacer(Modifier.width(10.dp))
                HeaderAction(if (searchMode) "×" else "⌕") {
                    searchMode = !searchMode
                    if (!searchMode) searchText = ""
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(start = if (isTv) 28.dp else 12.dp, end = 4.dp, top = 12.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            categories.forEach { (name, icon) ->
                CategoryChip(icon, name, counts[name] ?: 0, selectedSport == name) { selectedSport = name }
            }
        }

        val shown = remember(categoryList, searchText) {
            categoryList.filter { searchText.isBlank() || it.name.contains(searchText, true) }
        }
        if (shown.isEmpty()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (loading && selectedSport in remoteGroups && searchText.isBlank()) {
                    CircularProgressIndicator(color = Accent)
                    Spacer(Modifier.height(12.dp))
                    Text("Loading channels…", color = Muted, fontSize = 14.sp)
                } else {
                    Text(
                        if (searchText.isNotBlank()) "No channel matches your search."
                        else "No channels loaded. Check your internet and tap ⟳.",
                        color = Muted, fontSize = 14.sp
                    )
                }
                Spacer(Modifier.height(16.dp))
                VersionFooter(onCheckUpdate)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 340.dp),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = if (isTv) 32.dp else 10.dp, vertical = 4.dp)
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "${shown.size} ${if (selectedSport == "FM Radio") "STATIONS" else "CHANNELS"}",
                        color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 8.dp, top = 2.dp, bottom = 4.dp)
                    )
                }
                gridItems(shown, key = { it.url }, contentType = { "channel" }) { channel ->
                    ChannelCard(channel, false) { playingChannel = channel }
                }
                item(span = { GridItemSpan(maxLineSpan) }) { VersionFooter(onCheckUpdate) }
                item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.navigationBarsPadding().height(12.dp)) }
            }
        }
    }
}

@Composable
private fun VersionFooter(onCheck: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("SA-TV  v${BuildConfig.VERSION_NAME}  (build ${BuildConfig.VERSION_CODE})", color = Muted, fontSize = 11.sp)
        TextButton(onClick = onCheck) { Text("Check for updates", color = Accent, fontSize = 13.sp) }
    }
}

@Composable
private fun HeaderAction(symbol: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(38.dp)) {
        Text(symbol, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Light)
    }
}

@Composable
private fun CategoryChip(icon: String, label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected || focused) Color(0xFF172D3D) else Panel,
        border = androidx.compose.foundation.BorderStroke(
            if (focused) 3.dp else 1.dp, if (selected || focused) Accent else PanelAlt
        ),
        modifier = Modifier.padding(end = 8.dp).onFocusChanged { focused = it.hasFocus }
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(icon, fontSize = 16.sp)
            Spacer(Modifier.width(6.dp))
            Text(
                label, color = Color.White, fontSize = 13.sp, maxLines = 1,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
            )
            Spacer(Modifier.width(6.dp))
            Text("$count", color = if (selected) Accent else Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ChannelCard(channel: Channel, highlight: Boolean, onClick: () -> Unit) {
    val emoji = categoryEmoji(channelCategory(channel))
    var focused by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 3.dp)
            .onFocusChanged { focused = it.hasFocus }
            .clickable { onClick() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (focused) Color(0xFF223652) else if (highlight) Color(0xFF173043) else Panel
        ),
        border = androidx.compose.foundation.BorderStroke(
            if (focused) 3.dp else 1.dp, if (highlight || focused) Accent else Color(0xFF293A52)
        )
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(Color(0xFFEEF2F7)),
                contentAlignment = Alignment.Center
            ) {
                if (channel.logo.isNullOrBlank()) {
                    Text(emoji, fontSize = 22.sp)
                } else {
                    SubcomposeAsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(channel.logo)
                            .size(96)
                            .build(),
                        contentDescription = channel.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(6.dp),
                        loading = { Text(emoji, fontSize = 22.sp) },
                        error = { Text(emoji, fontSize = 22.sp) }
                    )
                }
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(channel.name, color = Color.White, fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (highlight) "Now playing"
                    else channel.group?.takeIf { it.isNotBlank() } ?: (channelCategory(channel) ?: ""),
                    color = if (highlight) Accent else Muted, fontSize = 11.sp, maxLines = 1
                )
            }
            Text(if (highlight) "●" else "›", color = Accent, fontSize = if (highlight) 14.sp else 24.sp)
        }
    }
}

// ───────────────────────── Player ─────────────────────────

private val Glass = Color(0x33FFFFFF)

@Composable
private fun LivePlayerScreen(
    channels: List<Channel>,
    initialChannel: Channel,
    onBack: () -> Unit,
    onChannelChanged: (Channel) -> Unit
) {
    val context = LocalContext.current
    val activity = context as Activity
    val view = LocalView.current
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    // One shared player: it also lives in PlaybackService so audio keeps playing with the screen locked
    val exoPlayer = remember { Playback.get(context) }
    val isTv = remember { isTvDevice(context) }

    var current by remember { mutableStateOf(initialChannel) }
    var attempt by remember { mutableStateOf(0) }
    val latest = rememberUpdatedState(current)
    var controlsVisible by remember { mutableStateOf(true) }
    var controlsTick by remember { mutableStateOf(0) }
    var muted by remember { mutableStateOf(false) }
    var fitMode by remember { mutableStateOf(0) } // 0 = FIT, 1 = FILL, 2 = ZOOM
    var playing by remember { mutableStateOf(true) }
    var buffering by remember { mutableStateOf(true) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var videoRatio by remember { mutableStateOf(0f) }
    var locked by remember { mutableStateOf(false) }
    var lockHint by remember { mutableStateOf(false) }
    var lockTick by remember { mutableStateOf(0) }
    var gKind by remember { mutableStateOf(0) } // 0 none, 1 volume, 2 brightness
    var gValue by remember { mutableStateOf(0f) }
    var gTick by remember { mutableStateOf(0) }
    var qualities by remember { mutableStateOf<List<QualityOption>>(emptyList()) }
    var quality by remember { mutableStateOf(0) } // 0 = Auto
    var qualityOpen by remember { mutableStateOf(false) }
    var playingHeight by remember { mutableStateOf(0) }
    val playFocus = remember { FocusRequester() }

    val isRadio = current.group == "FM Radio"
    val index = channels.indexOfFirst { it.url == current.url }.coerceAtLeast(0)

    fun switchTo(ch: Channel) {
        attempt = 0
        current = ch
        onChannelChanged(ch)
        controlsVisible = true
        controlsTick++
    }
    fun step(delta: Int) {
        if (channels.isEmpty()) return
        switchTo(channels[(index + delta + channels.size) % channels.size])
    }
    fun toggleOrientation() {
        activity.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }
    fun togglePlay() {
        if (playing) exoPlayer.pause() else {
            if (exoPlayer.playbackState == Player.STATE_IDLE) exoPlayer.prepare()
            exoPlayer.play()
        }
        controlsTick++
    }
    fun currentBrightness(): Float {
        val b = activity.window.attributes.screenBrightness
        if (b >= 0f) return b
        return try {
            Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        } catch (e: Exception) {
            0.5f
        }
    }
    fun applyQuality(opt: QualityOption?) {
        val b = exoPlayer.trackSelectionParameters.buildUpon()
        if (opt == null) b.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        else b.setOverrideForType(TrackSelectionOverride(opt.group, opt.index))
        exoPlayer.trackSelectionParameters = b.build()
        quality = opt?.height ?: 0
    }
    fun lockScreen(on: Boolean) {
        locked = on
        controlsVisible = false
        lockHint = on
        lockTick++
    }

    // stream loading
    LaunchedEffect(current.url, attempt) {
        errorText = null
        buffering = true
        videoRatio = 0f
        val links = listOf(current.url) + current.backupUrls
        quality = 0
        qualities = emptyList()
        playingHeight = 0
        try {
            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_VIDEO).build()
            val meta = MediaMetadata.Builder()
                .setTitle(current.name)
                .setArtist(if (current.group == "FM Radio") "SA-TV Radio" else "SA-TV Live")
                .apply {
                    current.logo?.takeIf { it.isNotBlank() }?.let { setArtworkUri(Uri.parse(it)) }
                }
                .build()
            exoPlayer.setMediaItem(
                MediaItem.Builder()
                    .setUri(links.getOrElse(attempt) { current.url })
                    .setMediaMetadata(meta)
                    .build()
            )
            exoPlayer.volume = if (muted) 0f else 1f
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        } catch (e: Exception) {
            errorText = "Stream could not be played. Try another channel."
        }
    }

    // listeners + cleanup
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                val ch = latest.value
                if (attempt + 1 < 1 + ch.backupUrls.size) {
                    attempt++ // try the backup link automatically
                    return
                }
                errorText = "This stream is not available right now."
                buffering = false
                controlsVisible = true
            }
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) errorText = null
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                playing = playWhenReady
            }
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoRatio = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                    playingHeight = videoSize.height
                }
            }
            override fun onTracksChanged(tracks: Tracks) {
                val found = LinkedHashMap<Int, QualityOption>()
                for (g in tracks.groups) {
                    if (g.type != C.TRACK_TYPE_VIDEO) continue
                    for (i in 0 until g.length) {
                        if (!g.isTrackSupported(i)) continue
                        val f = g.getTrackFormat(i)
                        if (f.height <= 0) continue
                        val old = found[f.height]
                        if (old == null || f.bitrate > old.bitrate) {
                            found[f.height] = QualityOption(f.height, f.bitrate, g.mediaTrackGroup, i)
                        }
                    }
                }
                qualities = found.values.sortedByDescending { it.height }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            context.stopService(Intent(context, PlaybackService::class.java))
        }
    }

    // connect to the media session: lock-screen / notification controls + background audio
    DisposableEffect(Unit) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        onDispose { MediaController.releaseFuture(future) }
    }
    LaunchedEffect(isRadio) {
        if (isRadio && !isTv && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
        }
    }

    // pop-up window + TV remote
    SideEffect {
        pipEligible = !isRadio && !isTv && errorText == null
        if (videoRatio > 0f) pipRatio = videoRatio
    }
    DisposableEffect(Unit) { onDispose { pipEligible = false } }

    val onKey = rememberUpdatedState<(Int) -> Boolean> { code ->
        val free = !locked && !qualityOpen && !inPipMode
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_NEXT ->
                if (free) { step(1); true } else false
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS ->
                if (free) { step(-1); true } else false
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE ->
                if (free) { togglePlay(); true } else false
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT ->
                if (free && !controlsVisible) { controlsVisible = true; controlsTick++; true } else false
            else -> false
        }
    }
    DisposableEffect(Unit) {
        playerKeyHandler = { code -> onKey.value(code) }
        onDispose { playerKeyHandler = null }
    }
    LaunchedEffect(controlsVisible) {
        if (controlsVisible && isTv) {
            delay(150)
            try { playFocus.requestFocus() } catch (e: Exception) { /* controls not on screen yet */ }
        }
    }

    // pause when app goes to background
    DisposableEffect(Unit) {
        var wasPlaying = false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                if (latest.value.group != "FM Radio") { // radio keeps playing with the screen locked
                    wasPlaying = exoPlayer.playWhenReady
                    exoPlayer.pause()
                }
            } else if (event == Lifecycle.Event.ON_START && wasPlaying) {
                exoPlayer.play()
            }
        }
        (context as androidx.activity.ComponentActivity).lifecycle.addObserver(observer)
        onDispose { (context as androidx.activity.ComponentActivity).lifecycle.removeObserver(observer) }
    }

    // screen on + rotation allowed while player is open; restore brightness on exit
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        onDispose {
            view.keepScreenOn = false
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            val lp = activity.window.attributes
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            activity.window.attributes = lp
            WindowCompat.getInsetsController(activity.window, view).show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // immersive fullscreen in landscape
    LaunchedEffect(landscape) {
        val controller = WindowCompat.getInsetsController(activity.window, view)
        if (landscape) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // auto-hide controls / indicators / lock hint
    LaunchedEffect(controlsVisible, controlsTick, playing, qualityOpen) {
        if (controlsVisible && playing && !qualityOpen) {
            delay(4000)
            controlsVisible = false
        }
    }
    LaunchedEffect(gTick) {
        if (gKind != 0) {
            delay(900)
            gKind = 0
        }
    }
    LaunchedEffect(lockTick) {
        if (lockHint) {
            delay(3000)
            lockHint = false
        }
    }

    BackHandler {
        if (locked) {
            lockHint = true
            lockTick++
        } else if (landscape) toggleOrientation() else onBack()
    }

    val stage: @Composable (Modifier) -> Unit = { mod ->
        Box(
            mod.background(Color.Black)
                .pointerInput(locked) {
                    detectTapGestures(
                        onTap = {
                            if (locked) {
                                lockHint = !lockHint
                                lockTick++
                            } else {
                                controlsVisible = !controlsVisible
                                controlsTick++
                            }
                        },
                        onDoubleTap = { if (!locked) togglePlay() }
                    )
                }
                .pointerInput(locked) {
                    if (!locked) {
                        var side = 0
                        var level = 0f
                        detectVerticalDragGestures(
                            onDragStart = { offset ->
                                if (offset.x < size.width / 2f) {
                                    side = 2
                                    level = currentBrightness()
                                } else {
                                    side = 1
                                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                                    level = if (muted) 0f
                                    else audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
                                }
                            },
                            onDragEnd = { },
                            onDragCancel = { },
                            onVerticalDrag = { change, dragAmount ->
                                change.consume()
                                level = (level - dragAmount / size.height * 1.4f).coerceIn(0f, 1f)
                                if (side == 2) {
                                    val lp = activity.window.attributes
                                    lp.screenBrightness = level.coerceAtLeast(0.02f)
                                    activity.window.attributes = lp
                                } else {
                                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, (level * max).roundToInt(), 0)
                                    if (muted && level > 0f) {
                                        muted = false
                                        exoPlayer.volume = 1f
                                    }
                                }
                                gKind = side
                                gValue = level
                                gTick++
                            }
                        )
                    }
                }
        ) {
            if (isRadio) RadioArt(current.name, playing)
            else VideoSurface(exoPlayer, fitMode, videoRatio, Modifier.fillMaxSize())

            if (buffering && errorText == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center).size(46.dp), color = Accent)
            }
            if (errorText != null) {
                Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(errorText!!, color = Color.White, fontSize = 14.sp)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = {
                            errorText = null
                            buffering = true
                            exoPlayer.prepare()
                            exoPlayer.playWhenReady = true
                        }) { Text("Retry") }
                        OutlinedButton(onClick = { reportBroken(context, current) }) { Text("Report") }
                    }
                }
            }

            // volume / brightness indicator
            if (gKind != 0) {
                LevelIndicator(
                    kind = gKind,
                    value = gValue,
                    barHeight = if (landscape) 130.dp else 64.dp,
                    modifier = Modifier
                        .align(if (gKind == 2) Alignment.CenterStart else Alignment.CenterEnd)
                        .padding(horizontal = 26.dp)
                )
            }

            // controls
            AnimatedVisibility(
                visible = controlsVisible && !locked,
                modifier = Modifier.matchParentSize(),
                enter = fadeIn(tween(180)),
                exit = fadeOut(tween(260))
            ) {
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(listOf(Color(0xCC000000), Color(0x00000000), Color(0xCC000000)))
                    )
                ) {
                    Box(
                        Modifier.fillMaxSize()
                            .then(if (landscape) Modifier.displayCutoutPadding() else Modifier)
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        // top bar
                        Row(
                            Modifier.align(Alignment.TopStart).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircleBtn(40.dp, Glass, onClick = {
                                if (landscape) toggleOrientation() else onBack()
                            }) { GlyphIcon("back", Modifier.size(22.dp)) }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(current.name, color = Color.White, fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (isRadio) "FM Radio" else (channelCategory(current) ?: ""),
                                    color = Color(0xFFCBD5E1), fontSize = 11.sp, maxLines = 1)
                            }
                            CircleBtn(40.dp, Glass, onClick = { lockScreen(true) }) {
                                GlyphIcon("unlock", Modifier.size(22.dp))
                            }
                            Spacer(Modifier.width(8.dp))
                            if (!isRadio && !isTv && Build.VERSION.SDK_INT >= 26) {
                                CircleBtn(40.dp, Glass, onClick = { (activity as MainActivity).enterPip() }) {
                                    GlyphIcon("pip", Modifier.size(22.dp))
                                }
                                Spacer(Modifier.width(8.dp))
                            }
                            CircleBtn(40.dp, Glass, onClick = {
                                muted = !muted
                                exoPlayer.volume = if (muted) 0f else 1f
                                controlsTick++
                            }) { GlyphIcon(if (muted) "mute" else "volume", Modifier.size(22.dp)) }
                        }
                        // centre controls
                        Row(
                            Modifier.align(Alignment.Center),
                            horizontalArrangement = Arrangement.spacedBy(26.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircleBtn(48.dp, Glass, onClick = { step(-1) }) {
                                GlyphIcon("prev", Modifier.size(26.dp))
                            }
                            CircleBtn(70.dp, Accent, onClick = { togglePlay() }, modifier = Modifier.focusRequester(playFocus)) {
                                GlyphIcon(if (playing) "pause" else "play", Modifier.size(38.dp))
                            }
                            CircleBtn(48.dp, Glass, onClick = { step(1) }) {
                                GlyphIcon("next", Modifier.size(26.dp))
                            }
                        }
                        // bottom bar
                        Row(
                            Modifier.align(Alignment.BottomStart).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                Modifier.clip(RoundedCornerShape(50)).background(Color(0x55E84F70))
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(Modifier.size(8.dp).clip(CircleShape).background(LiveRed))
                                Spacer(Modifier.width(6.dp))
                                Text("LIVE", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.width(10.dp))
                            Text("${index + 1} / ${channels.size}", color = Color(0xFFCBD5E1), fontSize = 12.sp)
                            Spacer(Modifier.weight(1f))
                            if (!isRadio) {
                                Box {
                                    Surface(
                                        onClick = { qualityOpen = true },
                                        shape = RoundedCornerShape(50),
                                        color = Glass
                                    ) {
                                        Text(
                                            if (quality == 0) {
                                                if (playingHeight > 0) "AUTO · ${qLabel(playingHeight)}" else "AUTO"
                                            } else qLabel(quality),
                                            color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
                                        )
                                    }
                                    DropdownMenu(expanded = qualityOpen, onDismissRequest = { qualityOpen = false }) {
                                        DropdownMenuItem(
                                            text = { Text(if (quality == 0) "✓  Auto" else "Auto") },
                                            onClick = { applyQuality(null); qualityOpen = false }
                                        )
                                        qualities.forEach { q ->
                                            DropdownMenuItem(
                                                text = { Text((if (quality == q.height) "✓  " else "") + qLabel(q.height)) },
                                                onClick = { applyQuality(q); qualityOpen = false }
                                            )
                                        }
                                        if (qualities.size < 2) {
                                            DropdownMenuItem(
                                                text = { Text("This stream has only one quality", color = Muted, fontSize = 12.sp) },
                                                onClick = { qualityOpen = false }
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.width(10.dp))
                                Surface(
                                    onClick = { fitMode = (fitMode + 1) % 3; controlsTick++ },
                                    shape = RoundedCornerShape(50),
                                    color = Glass
                                ) {
                                    Text(
                                        when (fitMode) { 0 -> "FIT"; 1 -> "FILL"; else -> "ZOOM" },
                                        color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
                                    )
                                }
                                Spacer(Modifier.width(10.dp))
                            }
                            CircleBtn(40.dp, Glass, onClick = { toggleOrientation() }) {
                                GlyphIcon(if (landscape) "exit" else "full", Modifier.size(22.dp))
                            }
                        }
                    }
                }
            }

            // screen lock button (only visible after a tap while locked)
            AnimatedVisibility(
                visible = locked && lockHint,
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 22.dp),
                enter = fadeIn(tween(150)),
                exit = fadeOut(tween(250))
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircleBtn(52.dp, Color(0x99000000), onClick = { lockScreen(false) }) {
                        GlyphIcon("lock", Modifier.size(26.dp), Accent)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Tap to unlock", color = Color.White, fontSize = 11.sp)
                }
            }
        }
    }

    if (inPipMode) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (isRadio) RadioArt(current.name, playing)
            else VideoSurface(exoPlayer, 0, videoRatio, Modifier.fillMaxSize())
        }
        return
    }

    if (landscape) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            stage(Modifier.fillMaxSize())
        }
    } else {
        Column(Modifier.fillMaxSize().background(Bg)) {
            Box(Modifier.fillMaxWidth().background(Color.Black).statusBarsPadding()) {
                stage(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(current.name, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(LiveRed))
                    Spacer(Modifier.width(6.dp))
                    Text("LIVE  •  ${channelCategory(current) ?: ""}", color = Muted, fontSize = 12.sp)
                }
                Text("⚑  Report broken channel", color = Accent, fontSize = 12.sp,
                    modifier = Modifier.clickable { reportBroken(context, current) }.padding(top = 8.dp, bottom = 2.dp))
                Spacer(Modifier.height(4.dp))
                Text("Swipe up/down: left side = brightness, right side = volume  •  Double-tap = play/pause  •  TV remote: Up/Down = change channel",
                    color = Muted, fontSize = 10.sp)
            }
            Text(
                if (isRadio) "MORE STATIONS" else "MORE CHANNELS", color = Muted, fontSize = 11.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 18.dp, bottom = 4.dp)
            )
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 10.dp)
            ) {
                items(channels, key = { it.url }, contentType = { "channel" }) { ch ->
                    ChannelCard(ch, ch.url == current.url) { switchTo(ch) }
                }
                item { Spacer(Modifier.navigationBarsPadding().height(12.dp)) }
            }
        }
    }
}

@Composable
private fun VideoSurface(player: ExoPlayer, fitMode: Int, ratio: Float, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier.clipToBounds(),
        factory = { ctx ->
            val frame = AspectRatioFrameLayout(ctx)
            val texture = android.view.TextureView(ctx)
            frame.addView(
                texture,
                android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            player.setVideoTextureView(texture)
            val holder = android.widget.FrameLayout(ctx)
            holder.clipChildren = true
            holder.addView(
                frame,
                android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.Gravity.CENTER
                )
            )
            holder
        },
        update = { holder ->
            val frame = holder.getChildAt(0) as AspectRatioFrameLayout
            frame.resizeMode = when (fitMode) {
                0 -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                1 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                else -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            }
            if (ratio > 0f) frame.setAspectRatio(ratio)
        }
    )
}

@Composable
private fun RadioArt(name: String, playing: Boolean) {
    val transition = rememberInfiniteTransition(label = "eq")
    val b0 = transition.animateFloat(0.25f, 1f, infiniteRepeatable(tween(520, easing = LinearEasing), RepeatMode.Reverse), label = "b0")
    val b1 = transition.animateFloat(0.25f, 1f, infiniteRepeatable(tween(650, easing = LinearEasing), RepeatMode.Reverse), label = "b1")
    val b2 = transition.animateFloat(0.25f, 1f, infiniteRepeatable(tween(430, easing = LinearEasing), RepeatMode.Reverse), label = "b2")
    val b3 = transition.animateFloat(0.25f, 1f, infiniteRepeatable(tween(580, easing = LinearEasing), RepeatMode.Reverse), label = "b3")
    val b4 = transition.animateFloat(0.25f, 1f, infiniteRepeatable(tween(470, easing = LinearEasing), RepeatMode.Reverse), label = "b4")
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF16324A), Color(0xFF0B1220)))
        ),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("📻", fontSize = 46.sp)
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.height(34.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                listOf(b0, b1, b2, b3, b4).forEach { v ->
                    Box(
                        Modifier.width(6.dp).fillMaxHeight(if (playing) v.value else 0.25f)
                            .clip(RoundedCornerShape(3.dp)).background(Accent)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("LIVE RADIO", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun LevelIndicator(kind: Int, value: Float, barHeight: androidx.compose.ui.unit.Dp, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(22.dp)).background(Color(0xB30B1220))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        GlyphIcon(
            if (kind == 2) "sun" else if (value <= 0f) "mute" else "volume",
            Modifier.size(24.dp)
        )
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier.width(6.dp).height(barHeight).clip(RoundedCornerShape(3.dp)).background(Color(0x44FFFFFF))
        ) {
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .fillMaxHeight(value.coerceIn(0f, 1f)).background(Accent)
            )
        }
        Spacer(Modifier.height(8.dp))
        Text("${(value * 100).roundToInt()}%", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CircleBtn(
    size: androidx.compose.ui.unit.Dp,
    bg: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier.size(size).clip(CircleShape).background(bg)
            .then(if (focused) Modifier.border(3.dp, Accent, CircleShape) else Modifier)
            .onFocusChanged { focused = it.hasFocus }
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
        content = content
    )
}

@Composable
private fun GlyphIcon(kind: String, modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.09f
        when (kind) {
            "play" -> {
                val p = Path().apply {
                    moveTo(w * .32f, h * .2f); lineTo(w * .32f, h * .8f); lineTo(w * .82f, h * .5f); close()
                }
                drawPath(p, color)
            }
            "pause" -> {
                drawRect(color, Offset(w * .27f, h * .2f), Size(w * .16f, h * .6f))
                drawRect(color, Offset(w * .57f, h * .2f), Size(w * .16f, h * .6f))
            }
            "next" -> {
                val p = Path().apply {
                    moveTo(w * .2f, h * .22f); lineTo(w * .2f, h * .78f); lineTo(w * .68f, h * .5f); close()
                }
                drawPath(p, color)
                drawRect(color, Offset(w * .72f, h * .22f), Size(w * .09f, h * .56f))
            }
            "prev" -> {
                val p = Path().apply {
                    moveTo(w * .8f, h * .22f); lineTo(w * .8f, h * .78f); lineTo(w * .32f, h * .5f); close()
                }
                drawPath(p, color)
                drawRect(color, Offset(w * .19f, h * .22f), Size(w * .09f, h * .56f))
            }
            "back" -> {
                drawLine(color, Offset(w * .62f, h * .2f), Offset(w * .32f, h * .5f), sw, StrokeCap.Round)
                drawLine(color, Offset(w * .32f, h * .5f), Offset(w * .62f, h * .8f), sw, StrokeCap.Round)
            }
            "full" -> {
                drawLine(color, Offset(w * .2f, h * .42f), Offset(w * .2f, h * .2f), sw, StrokeCap.Round)
                drawLine(color, Offset(w * .2f, h * .2f), Offset(w * .42f, h * .2f), sw, StrokeCap.Round)
                drawLine(color, Offset(w * .58f, h * .2f), Offset(w * .8f, h * .2f), sw, StrokeCap.Round)
                drawLine(color, Offset(w * .8f, h * .2f), Offset(w * .8f, h * .42f), sw, StrokeCap.Round)
                drawLine(color, Offset(w * .2f, h * .58f), Offset(w * .2f, h * .8f), sw, StrokeCap.Round)
                drawLine(color, Offset(w * .2f, h * .8f), Offset(w * .42f, h * .8f), sw, StrokeCap.Round)
                drawLine(color, Offset(w * .58f, h * .8f), Offset(w * .8f, h * .8f), sw, StrokeCap.Round)
                drawLine(color, Offset(w * .8f, h * .8f), Offset(w * .8f, h * .58f), sw, StrokeCap.Round)
            }
            "exit" -> {
                drawLine(color, Offset(w * .4f, h * .18f), Offset(w * .4f, h * .4f), sw, StrokeCap.Round)
                drawLine(color, Offset(w * .4f, h * .4f), Offset(w * .18f, h * .4f), sw, StrokeCap.Round)
                drawLine(color, Offset(w * .6f, h * .18f), Offset(w * .6f, h * .4f), sw, StrokeCap.Round)
                drawLine(color, Offset(w * .6f, h * .4f), Offset(w * .82f, h * .4f), sw, StrokeCap.Round)
                drawLine(color, Offset(w * .4f, h * .82f), Offset(w * .4f, h * .6f), sw, StrokeCap.Round)
                drawLine(color, Offset(w * .4f, h * .6f), Offset(w * .18f, h * .6f), sw, StrokeCap.Round)
                drawLine(color, Offset(w * .6f, h * .82f), Offset(w * .6f, h * .6f), sw, StrokeCap.Round)
                drawLine(color, Offset(w * .6f, h * .6f), Offset(w * .82f, h * .6f), sw, StrokeCap.Round)
            }
            "volume", "mute" -> {
                val body = Path().apply {
                    moveTo(w * .10f, h * .38f); lineTo(w * .28f, h * .38f); lineTo(w * .50f, h * .18f)
                    lineTo(w * .50f, h * .82f); lineTo(w * .28f, h * .62f); lineTo(w * .10f, h * .62f); close()
                }
                drawPath(body, color)
                if (kind == "volume") {
                    val r1 = w * .17f
                    val r2 = w * .31f
                    drawArc(color, -50f, 100f, false, Offset(w * .50f - r1, h * .5f - r1), Size(r1 * 2, r1 * 2),
                        style = Stroke(sw, cap = StrokeCap.Round))
                    drawArc(color, -50f, 100f, false, Offset(w * .50f - r2, h * .5f - r2), Size(r2 * 2, r2 * 2),
                        style = Stroke(sw, cap = StrokeCap.Round))
                } else {
                    drawLine(color, Offset(w * .64f, h * .36f), Offset(w * .90f, h * .64f), sw, StrokeCap.Round)
                    drawLine(color, Offset(w * .90f, h * .36f), Offset(w * .64f, h * .64f), sw, StrokeCap.Round)
                }
            }
            "sun" -> {
                val c = Offset(w * .5f, h * .5f)
                drawCircle(color, radius = w * .17f, center = c)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val r1 = w * .28
                    val r2 = w * .42
                    drawLine(
                        color,
                        Offset(c.x + (cos(a) * r1).toFloat(), c.y + (sin(a) * r1).toFloat()),
                        Offset(c.x + (cos(a) * r2).toFloat(), c.y + (sin(a) * r2).toFloat()),
                        sw, StrokeCap.Round
                    )
                }
            }
            "pip" -> {
                drawRoundRect(color, Offset(w * .12f, h * .2f), Size(w * .76f, h * .6f),
                    CornerRadius(w * .06f, w * .06f), style = Stroke(sw))
                drawRoundRect(color, Offset(w * .48f, h * .48f), Size(w * .3f, h * .22f),
                    CornerRadius(w * .03f, w * .03f))
            }
            "lock" -> {
                drawArc(color, 180f, 180f, false, Offset(w * .32f, h * .14f), Size(w * .36f, h * .5f),
                    style = Stroke(sw, cap = StrokeCap.Round))
                drawLine(color, Offset(w * .32f, h * .39f), Offset(w * .32f, h * .48f), sw, StrokeCap.Round)
                drawLine(color, Offset(w * .68f, h * .39f), Offset(w * .68f, h * .48f), sw, StrokeCap.Round)
                drawRoundRect(color, Offset(w * .22f, h * .45f), Size(w * .56f, h * .40f),
                    CornerRadius(w * .07f, w * .07f))
            }
            "unlock" -> {
                drawArc(color, 180f, 180f, false, Offset(w * .32f, h * .04f), Size(w * .36f, h * .5f),
                    style = Stroke(sw, cap = StrokeCap.Round))
                drawLine(color, Offset(w * .32f, h * .29f), Offset(w * .32f, h * .48f), sw, StrokeCap.Round)
                drawRoundRect(color, Offset(w * .22f, h * .45f), Size(w * .56f, h * .40f),
                    CornerRadius(w * .07f, w * .07f))
            }
        }
    }
}
