package com.hearit.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.hearit.app.data.AudioSourceType
import com.hearit.app.data.HearItDatabase
import com.hearit.app.data.HearItPrefs
import com.hearit.app.data.TrackEntity
import com.hearit.app.service.HearItService
import com.hearit.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HearItTheme {
                HearItHomeScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HearItHomeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { HearItDatabase.get(context) }

    val tracks by db.trackDao().observeAll().collectAsState(initial = emptyList())
    val pendingCount by db.trackDao().observePendingCount().collectAsState(initial = 0)

    var searchQuery by remember { mutableStateOf("") }
    var favoritesOnly by remember { mutableStateOf(false) }
    var isArmed by remember { mutableStateOf(HearItPrefs.isServiceArmed(context)) }
    var screenOnEnabled by remember { mutableStateOf(HearItPrefs.isScreenOnEnabled(context)) }
    var pocketSkipEnabled by remember { mutableStateOf(HearItPrefs.isPocketSkipEnabled(context)) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val micOk = grants[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (micOk) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, HearItService::class.java).apply { action = HearItService.ACTION_START }
            )
            isArmed = true
        }
    }

    fun requestPermissionsAndArm() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, HearItService::class.java).apply { action = HearItService.ACTION_START }
            )
            isArmed = true
        } else {
            permLauncher.launch(missing.toTypedArray())
        }
    }

    val filtered = remember(tracks, searchQuery, favoritesOnly) {
        tracks.filter { t ->
            val matchesFav = !favoritesOnly || t.isFavorite
            val q = searchQuery.trim()
            val matchesQ = q.isBlank() ||
                t.title.contains(q, ignoreCase = true) ||
                t.artist.contains(q, ignoreCase = true) ||
                t.album.contains(q, ignoreCase = true)
            matchesFav && matchesQ
        }
    }

    val grouped = remember(filtered) {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val yesterday = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            .format(Date(System.currentTimeMillis() - 86_400_000L))
        filtered.groupBy { t ->
            when (t.dateGroup) {
                today -> "Today"
                yesterday -> "Yesterday"
                else -> t.dateGroup
            }
        }
    }

    Scaffold(
        containerColor = ObsidianBg,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("HearIt", fontWeight = FontWeight.Bold, color = TextPrimary)
                        if (pendingCount > 0) {
                            Spacer(Modifier.width(8.dp))
                            Surface(
                                color = CyanAccent.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    "$pendingCount queued",
                                    color = CyanAccent,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ObsidianBg),
                actions = {
                    IconButton(onClick = {
                        scope.launch { exportLibrary(context, tracks, asJson = false) }
                    }) {
                        Icon(Icons.Default.Download, contentDescription = "Export CSV", tint = CyanAccent)
                    }
                    IconButton(onClick = {
                        scope.launch { exportLibrary(context, tracks, asJson = true) }
                    }) {
                        Icon(Icons.Default.DataObject, contentDescription = "Export JSON", tint = EmeraldAccent)
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    if (!isArmed) {
                        requestPermissionsAndArm()
                    } else {
                        ContextCompat.startForegroundService(
                            context,
                            Intent(context, HearItService::class.java).apply { action = HearItService.ACTION_HEAR_NOW }
                        )
                    }
                },
                containerColor = EmeraldAccent,
                contentColor = Color.Black,
                icon = { Icon(Icons.Default.GraphicEq, contentDescription = null) },
                text = { Text(if (isArmed) "Hear Now" else "Start HearIt", fontWeight = FontWeight.SemiBold) }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                ControlPanelCard(
                    isArmed = isArmed,
                    screenOnEnabled = screenOnEnabled,
                    pocketSkipEnabled = pocketSkipEnabled,
                    onToggleArm = {
                        if (isArmed) {
                            context.startService(
                                Intent(context, HearItService::class.java).apply { action = HearItService.ACTION_STOP }
                            )
                            isArmed = false
                        } else {
                            requestPermissionsAndArm()
                        }
                    },
                    onToggleScreenOn = {
                        screenOnEnabled = it
                        HearItPrefs.setScreenOnEnabled(context, it)
                    },
                    onTogglePocketSkip = {
                        pocketSkipEnabled = it
                        HearItPrefs.setPocketSkipEnabled(context, it)
                    },
                    onOpenNotifAccess = {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    },
                    onOpenBatterySettings = {
                        val pm = context.getSystemService(PowerManager::class.java)
                        if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:${context.packageName}")
                                )
                            )
                        }
                    }
                )
            }

            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search your Audio Library…", color = TextMuted) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextMuted) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !favoritesOnly,
                        onClick = { favoritesOnly = false },
                        label = { Text("All (${tracks.size})") }
                    )
                    FilterChip(
                        selected = favoritesOnly,
                        onClick = { favoritesOnly = true },
                        label = { Text("Favorites") },
                        leadingIcon = { Icon(Icons.Default.Favorite, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                }
            }

            if (filtered.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No songs logged yet. Tap Hear Now or wake your screen near music!",
                            color = TextMuted
                        )
                    }
                }
            } else {
                grouped.forEach { (dateHeader, dayTracks) ->
                    item(key = "hdr_$dateHeader") {
                        Text(
                            text = dateHeader,
                            color = CyanAccent,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                        )
                    }
                    items(dayTracks, key = { it.id }) { track ->
                        TrackCard(
                            track = track,
                            onToggleFav = {
                                scope.launch { db.trackDao().setFavorite(track.id, !track.isFavorite) }
                            },
                            onOpenSpotify = {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(track.spotifySearchUri)))
                            },
                            onOpenYtMusic = {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(track.ytMusicSearchUrl)))
                            }
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

@Composable
private fun ControlPanelCard(
    isArmed: Boolean,
    screenOnEnabled: Boolean,
    pocketSkipEnabled: Boolean,
    onToggleArm: () -> Unit,
    onToggleScreenOn: (Boolean) -> Unit,
    onTogglePocketSkip: (Boolean) -> Unit,
    onOpenNotifAccess: () -> Unit,
    onOpenBatterySettings: () -> Unit
) {
    Surface(
        color = ObsidianSurface,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (isArmed) EmeraldAccent else TextMuted)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (isArmed) "Armed (0% CPU while screen off)" else "Paused",
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                TextButton(onClick = onToggleArm) {
                    Text(if (isArmed) "Pause" else "Arm", color = EmeraldAccent)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Screen-On Instant ID", color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                Switch(checked = screenOnEnabled, onCheckedChange = onToggleScreenOn)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Skip When In Pocket", color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                Switch(checked = pocketSkipEnabled, onCheckedChange = onTogglePocketSkip)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = onOpenNotifAccess,
                    label = { Text("Spotify/YTM Sync", style = MaterialTheme.typography.labelSmall) }
                )
                AssistChip(
                    onClick = onOpenBatterySettings,
                    label = { Text("Unrestricted Battery", style = MaterialTheme.typography.labelSmall) }
                )
            }
        }
    }
}

@Composable
private fun TrackCard(
    track: TrackEntity,
    onToggleFav: () -> Unit,
    onOpenSpotify: () -> Unit,
    onOpenYtMusic: () -> Unit
) {
    val timeText = remember(track.lastHeardAt) {
        SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(track.lastHeardAt))
    }
    val sourceLabel = when (track.source) {
        AudioSourceType.AMBIENT_MIC -> "Mic"
        AudioSourceType.HEADPHONE_CAPTURE -> "Earbuds"
        AudioSourceType.SPOTIFY_MEDIA -> "Spotify"
        AudioSourceType.YT_MUSIC_MEDIA -> "YT Music"
    }

    Surface(
        color = ObsidianCard,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = track.coverUrl.ifBlank { R.mipmap.ic_launcher },
                contentDescription = track.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(ObsidianSurface)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    track.title,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    track.artist,
                    color = TextMuted,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "$timeText · $sourceLabel" + if (track.playCount > 1) " · ${track.playCount}x" else "",
                    color = CyanAccent,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            IconButton(onClick = onToggleFav) {
                Icon(
                    if (track.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "Favorite",
                    tint = if (track.isFavorite) EmeraldAccent else TextMuted
                )
            }
            Text(
                "Spotify",
                color = EmeraldAccent,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onOpenSpotify)
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            )
            Text(
                "YTM",
                color = CyanAccent,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onOpenYtMusic)
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            )
        }
    }
}

private suspend fun exportLibrary(context: android.content.Context, tracks: List<TrackEntity>, asJson: Boolean) {
    val file = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val ext = if (asJson) "json" else "csv"
        val outFile = File(dir, "HearIt_Library.$ext")
        if (asJson) {
            val arr = JSONArray()
            tracks.forEach { t ->
                arr.put(JSONObject().apply {
                    put("title", t.title)
                    put("artist", t.artist)
                    put("album", t.album)
                    put("source", t.source.name)
                    put("playCount", t.playCount)
                    put("favorite", t.isFavorite)
                    put("date", t.dateGroup)
                    put("spotifyUrl", t.spotifySearchUri)
                    put("ytMusicUrl", t.ytMusicSearchUrl)
                })
            }
            outFile.writeText(arr.toString(2))
        } else {
            val sb = StringBuilder("Title,Artist,Album,Source,Plays,Favorite,Date,Spotify,YTMusic\n")
            tracks.forEach { t ->
                fun esc(s: String) = "\"${s.replace("\"", "\"\"")}\""
                sb.append(
                    listOf(
                        esc(t.title), esc(t.artist), esc(t.album), t.source.name,
                        t.playCount.toString(), t.isFavorite.toString(), t.dateGroup,
                        esc(t.spotifySearchUri), esc(t.ytMusicSearchUrl)
                    ).joinToString(",")
                ).append('\n')
            }
            outFile.writeText(sb.toString())
        }
        outFile
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val share = Intent(Intent.ACTION_SEND).apply {
        type = if (asJson) "application/json" else "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(share, "Export HearIt Library"))
}
