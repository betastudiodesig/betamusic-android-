package com.betastudio.betamusic

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private val Bg = Color(0xFF090B0A)
private val SurfaceDark = Color(0xFF151916)
private val SurfaceLight = Color(0xFF202620)
private val Lime = Color(0xFFB7F34A)
private val TextMain = Color(0xFFF2F5EF)
private val TextMuted = Color(0xFF9CA69B)

class MainActivity : ComponentActivity() {
    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val songsState = mutableStateOf<List<Song>>(emptyList())
    private val permissionState = mutableStateOf(false)
    private val loadingState = mutableStateOf(true)
    private val favoritesState = mutableStateOf<Set<Long>>(emptySet())
    private val currentIdState = mutableStateOf<Long?>(null)
    private val playingState = mutableStateOf(false)
    private val durationState = mutableLongStateOf(0L)
    private val positionState = mutableLongStateOf(0L)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permissionState.value = granted
            if (granted) loadLibrary() else loadingState.value = false
        }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            playingState.value = isPlaying
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            currentIdState.value =
                mediaItem?.mediaMetadata?.extras?.getLong("song_id")
            syncPlayerState()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            syncPlayerState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        favoritesState.value =
            getSharedPreferences("betamusic", MODE_PRIVATE)
                .getStringSet("favorites", emptySet())
                ?.mapNotNull { it.toLongOrNull() }
                ?.toSet() ?: emptySet()

        val permission =
            if (Build.VERSION.SDK_INT >= 33) {
                Manifest.permission.READ_MEDIA_AUDIO
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }

        if (ContextCompat.checkSelfPermission(this, permission) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            permissionState.value = true
            loadLibrary()
        } else {
            loadingState.value = false
            permissionLauncher.launch(permission)
        }

        val token = SessionToken(
            this,
            ComponentName(this, PlaybackService::class.java)
        )

        controllerFuture =
            MediaController.Builder(this, token).buildAsync().also { future ->
                future.addListener({
                    try {
                        controller = future.get()
                        controller?.addListener(playerListener)
                        syncPlayerState()
                    } catch (_: Exception) {
                    }
                }, ContextCompat.getMainExecutor(this))
            }

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Lime,
                    background = Bg,
                    surface = SurfaceDark,
                    onBackground = TextMain,
                    onSurface = TextMain
                )
            ) {
                BetaMusicScreen(
                    songs = songsState.value,
                    loading = loadingState.value,
                    hasPermission = permissionState.value,
                    favorites = favoritesState.value,
                    currentId = currentIdState.value,
                    isPlaying = playingState.value,
                    position = positionState.longValue,
                    duration = durationState.longValue,
                    onRequestPermission = {
                        permissionLauncher.launch(permission)
                    },
                    onPlay = { playSong(it) },
                    onTogglePlay = { togglePlay() },
                    onNext = { controller?.seekToNextMediaItem() },
                    onPrevious = { controller?.seekToPreviousMediaItem() },
                    onSeek = { controller?.seekTo(it) },
                    onToggleFavorite = { toggleFavorite(it) }
                )
            }
        }

        lifecycleScope.launch {
            while (true) {
                syncPlayerState()
                kotlinx.coroutines.delay(500)
            }
        }
    }

    private fun loadLibrary() {
        loadingState.value = true
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                runCatching {
                    MusicRepository(this@MainActivity).loadSongs()
                }.getOrDefault(emptyList())
            }
            songsState.value = list
            loadingState.value = false
        }
    }

    private fun playSong(song: Song) {
        val player = controller ?: return
        val list = songsState.value

        val items = list.map { item ->
            MediaItem.Builder()
                .setMediaId(item.id.toString())
                .setUri(item.uri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(item.title)
                        .setArtist(item.artist)
                        .setAlbumTitle(item.album)
                        .setExtras(
                            android.os.Bundle().apply {
                                putLong("song_id", item.id)
                            }
                        )
                        .build()
                )
                .build()
        }

        val index = list.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
        player.setMediaItems(items, index, 0L)
        player.prepare()
        player.play()
        currentIdState.value = song.id
        playingState.value = true
    }

    private fun togglePlay() {
        val player = controller ?: return

        if (player.mediaItemCount == 0) {
            songsState.value.firstOrNull()?.let {
                playSong(it)
                return
            }
        }

        if (player.isPlaying) player.pause() else player.play()
    }

    private fun syncPlayerState() {
        val player = controller ?: return
        playingState.value = player.isPlaying
        durationState.longValue =
            player.duration.takeIf { it > 0 } ?: 0L
        positionState.longValue = player.currentPosition.coerceAtLeast(0L)
        currentIdState.value =
            player.currentMediaItem?.mediaId?.toLongOrNull()
    }

    private fun toggleFavorite(song: Song) {
        val next = favoritesState.value.toMutableSet()
            .apply {
                if (!add(song.id)) remove(song.id)
            }
            .toSet()

        favoritesState.value = next

        getSharedPreferences("betamusic", MODE_PRIVATE)
            .edit()
            .putStringSet("favorites", next.map { it.toString() }.toSet())
            .apply()
    }

    override fun onDestroy() {
        controller?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        super.onDestroy()
    }
}

@Composable
private fun BetaMusicScreen(
    songs: List<Song>,
    loading: Boolean,
    hasPermission: Boolean,
    favorites: Set<Long>,
    currentId: Long?,
    isPlaying: Boolean,
    position: Long,
    duration: Long,
    onRequestPermission: () -> Unit,
    onPlay: (Song) -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleFavorite: (Song) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf("Canciones") }
    var showFavorites by remember { mutableStateOf(false) }

    val filtered = remember(songs, query, tab, showFavorites, favorites) {
        songs.filter { song ->
            val matches = query.isBlank() ||
                listOf(song.title, song.artist, song.album, song.folder)
                    .any { it.contains(query, true) }

            val tabMatch = when (tab) {
                "Artistas" -> songs.filter { it.artist == song.artist }.isNotEmpty()
                "Álbumes" -> songs.filter { it.albumId == song.albumId }.isNotEmpty()
                "Carpetas" -> song.folder.isNotBlank()
                else -> true
            }

            matches && tabMatch && (!showFavorites || song.id in favorites)
        }
    }

    val current = songs.firstOrNull { it.id == currentId }

    Column(
        Modifier.fillMaxSize()
            .background(Bg)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            Modifier.fillMaxWidth()
                .padding(start = 22.dp, end = 18.dp, top = 14.dp, bottom = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "ẞetaMusic",
                    color = TextMain,
                    fontSize = 27.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-1).sp
                )
                Text(
                    "TU MÚSICA. TU ESPACIO.",
                    color = Lime,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
            }

            Box(
                Modifier.size(44.dp)
                    .clip(CircleShape)
                    .background(SurfaceLight),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.GraphicEq,
                    contentDescription = "Ecualizador",
                    tint = Lime,
                    modifier = Modifier.size(25.dp)
                )
            }
        }

        Row(
            Modifier.padding(horizontal = 18.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(SurfaceDark)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Search, null, tint = TextMuted)
            Spacer(Modifier.width(10.dp))

            TextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text("Buscar canciones, artistas…", color = TextMuted)
                },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = Lime
                )
            )

            if (query.isNotEmpty()) {
                IconButton(onClick = { query = "" }) {
                    Icon(Icons.Default.Close, "Limpiar", tint = TextMuted)
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("Canciones", "Artistas", "Álbumes", "Carpetas").forEach { item ->
                val selected = tab == item

                Box(
                    Modifier.clip(RoundedCornerShape(50))
                        .background(if (selected) Lime else SurfaceDark)
                        .clickable {
                            tab = item
                            showFavorites = false
                        }
                        .padding(horizontal = 13.dp, vertical = 9.dp)
                ) {
                    Text(
                        item,
                        color = if (selected) Color(0xFF10140D) else TextMuted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 17.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (showFavorites) "TUS FAVORITOS" else "TU BIBLIOTECA",
                    color = TextMuted,
                    fontSize = 10.sp,
                    letterSpacing = 1.7.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    if (loading) "Leyendo música…" else "${filtered.size} pistas",
                    color = TextMain,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            TextButton(onClick = { showFavorites = !showFavorites }) {
                Icon(
                    if (showFavorites) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    null,
                    tint = Lime,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(5.dp))
                Text(if (showFavorites) "Todo" else "Favoritos", color = Lime)
            }
        }

        when {
            !hasPermission -> EmptyState(
                "Permiso necesario",
                "Permite el acceso a audio para mostrar tu biblioteca.",
                "Conceder permiso",
                onRequestPermission
            )

            loading -> Box(
                Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Lime)
            }

            filtered.isEmpty() -> EmptyState(
                "No hay canciones",
                if (songs.isEmpty()) {
                    "Añade archivos de música al teléfono y vuelve a intentarlo."
                } else {
                    "Prueba otra búsqueda o cambia el filtro."
                },
                "Actualizar",
                onRequestPermission
            )

            else -> LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = 12.dp
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(filtered, key = { it.id }) { song ->
                    val active = song.id == currentId

                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (active) SurfaceLight else Color.Transparent)
                            .clickable { onPlay(song) }
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (active) Lime.copy(alpha = .15f) else SurfaceDark
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (active && isPlaying) {
                                    Icons.Default.Equalizer
                                } else {
                                    Icons.Default.MusicNote
                                },
                                null,
                                tint = Lime,
                                modifier = Modifier.size(23.dp)
                            )
                        }

                        Spacer(Modifier.width(12.dp))

                        Column(Modifier.weight(1f)) {
                            Text(
                                song.title,
                                color = if (active) Lime else TextMain,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 14.sp
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                "${song.artist}  ·  ${song.album}",
                                color = TextMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 11.sp
                            )
                        }

                        IconButton(onClick = { onToggleFavorite(song) }) {
                            Icon(
                                if (song.id in favorites) {
                                    Icons.Default.Favorite
                                } else {
                                    Icons.Default.FavoriteBorder
                                },
                                null,
                                tint = if (song.id in favorites) Lime else TextMuted,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }
                }
            }
        }

        if (current != null) {
            Column(
                Modifier.fillMaxWidth()
                    .background(SurfaceDark)
                    .padding(horizontal = 18.dp, vertical = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(SurfaceLight),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Album,
                            null,
                            tint = Lime,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Spacer(Modifier.width(11.dp))

                    Column(Modifier.weight(1f)) {
                        Text(
                            current.title,
                            color = TextMain,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 13.sp
                        )
                        Text(current.artist, color = TextMuted, maxLines = 1, fontSize = 11.sp)
                    }

                    IconButton(onClick = onPrevious) {
                        Icon(Icons.Default.SkipPrevious, "Anterior", tint = TextMain)
                    }
                    IconButton(onClick = onTogglePlay) {
                        Icon(
                            if (isPlaying) Icons.Default.PauseCircleFilled
                            else Icons.Default.PlayCircleFilled,
                            "Reproducir o pausar",
                            tint = Lime,
                            modifier = Modifier.size(34.dp)
                        )
                    }
                    IconButton(onClick = onNext) {
                        Icon(Icons.Default.SkipNext, "Siguiente", tint = TextMain)
                    }
                }

                Slider(
                    value = if (duration > 0) {
                        position.coerceAtMost(duration).toFloat()
                    } else {
                        0f
                    },
                    onValueChange = { onSeek(it.toLong()) },
                    valueRange = 0f..duration.coerceAtLeast(1L).toFloat(),
                    modifier = Modifier.height(26.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = Lime,
                        activeTrackColor = Lime,
                        inactiveTrackColor = SurfaceLight
                    )
                )

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(formatTime(position), color = TextMuted, fontSize = 10.sp)
                    Text(formatTime(duration), color = TextMuted, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun EmptyState(
    title: String,
    message: String,
    action: String,
    onAction: () -> Unit
) {
    // Corrección: weight() se aplica desde el ColumnScope padre,
    // no dentro de este composable independiente.
    Column(
        Modifier.fillMaxWidth().padding(30.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.LibraryMusic,
            null,
            tint = Lime,
            modifier = Modifier.size(52.dp)
        )
        Spacer(Modifier.height(15.dp))
        Text(title, color = TextMain, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(7.dp))
        Text(message, color = TextMuted, fontSize = 13.sp, lineHeight = 19.sp)
        Spacer(Modifier.height(15.dp))
        Button(
            onClick = onAction,
            colors = ButtonDefaults.buttonColors(
                containerColor = Lime,
                contentColor = Color(0xFF11150D)
            )
        ) {
            Text(action, fontWeight = FontWeight.Bold)
        }
    }
}

private fun formatTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0L) / 1000L).toInt()
    return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
}

