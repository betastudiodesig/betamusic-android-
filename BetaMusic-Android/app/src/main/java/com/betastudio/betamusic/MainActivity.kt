package com.betastudio.betamusic

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.pm.PackageManager
import android.net.Uri
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.compose.AsyncImage
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

private data class LibraryGroup(
    val id: String,
    val title: String,
    val subtitle: String,
    val songs: List<Song>
) {
    val coverSong: Song? get() = songs.firstOrNull()
}

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
            currentIdState.value = mediaItem?.mediaId?.toLongOrNull()
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

        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            permissionState.value = true
            loadLibrary()
        } else {
            loadingState.value = false
            permissionLauncher.launch(permission)
        }

        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync().also { future ->
            future.addListener({
                try {
                    controller = future.get()
                    controller?.addListener(playerListener)
                    syncPlayerState()
                } catch (_: Exception) {
                    // The screen remains usable; playback controls become active when the service connects.
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
                    onRequestPermission = { permissionLauncher.launch(permission) },
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
                runCatching { MusicRepository(this@MainActivity).loadSongs() }
                    .getOrDefault(emptyList())
            }
            songsState.value = list
            loadingState.value = false
        }
    }

    private fun playSong(song: Song) {
        val player = controller ?: return
        val list = songsState.value
        if (list.isEmpty()) return

        val items = list.map { item ->
            MediaItem.Builder()
                .setMediaId(item.id.toString())
                .setUri(item.uri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(item.title)
                        .setArtist(item.artist)
                        .setAlbumTitle(item.album)
                        .setExtras(Bundle().apply { putLong("song_id", item.id) })
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
        durationState.longValue = player.duration.takeIf { it > 0 } ?: 0L
        positionState.longValue = player.currentPosition.coerceAtLeast(0L)
        currentIdState.value = player.currentMediaItem?.mediaId?.toLongOrNull()
    }

    private fun toggleFavorite(song: Song) {
        val next = favoritesState.value.toMutableSet().apply {
            if (!add(song.id)) remove(song.id)
        }.toSet()
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
    var selectedGroupId by remember { mutableStateOf<String?>(null) }
    var showPlayer by remember { mutableStateOf(false) }

    val groups = remember(songs, tab) {
        val grouped: List<Pair<String, List<Song>>> = when (tab) {
            "Artistas" -> songs.groupBy { it.artist }
                .map { (name, tracks) -> "artist:$name" to tracks.sortedBy { it.title.lowercase(Locale.getDefault()) } }
            "Álbumes" -> songs.groupBy { "${it.albumId}:${it.album}" }
                .map { (key, tracks) -> "album:$key" to tracks.sortedBy { it.title.lowercase(Locale.getDefault()) } }
            "Carpetas" -> songs.groupBy { it.folder }
                .map { (folder, tracks) -> "folder:$folder" to tracks.sortedBy { it.title.lowercase(Locale.getDefault()) } }
            else -> emptyList()
        }
        grouped.map { (id, tracks) ->
            val first = tracks.first()
            when (tab) {
                "Artistas" -> LibraryGroup(id, first.artist, "${tracks.size} canciones", tracks)
                "Álbumes" -> LibraryGroup(id, first.album, "${first.artist} · ${tracks.size} canciones", tracks)
                else -> LibraryGroup(id, first.folder.substringAfterLast('/').ifBlank { "Almacenamiento" }, "${tracks.size} canciones", tracks)
            }
        }.sortedBy { it.title.lowercase(Locale.getDefault()) }
    }

    val selectedGroup = groups.firstOrNull { it.id == selectedGroupId }
    val current = songs.firstOrNull { it.id == currentId }

    val filteredGroups = remember(groups, query, showFavorites, favorites) {
        groups.filter { group ->
            val matchesQuery = query.isBlank() ||
                group.title.contains(query, true) || group.subtitle.contains(query, true) ||
                group.songs.any { it.title.contains(query, true) || it.artist.contains(query, true) || it.album.contains(query, true) }
            val matchesFavorites = !showFavorites || group.songs.any { it.id in favorites }
            matchesQuery && matchesFavorites
        }
    }

    val baseSongs = if (selectedGroup != null) selectedGroup.songs else songs
    val filteredSongs = remember(baseSongs, query, showFavorites, favorites, tab, selectedGroupId) {
        baseSongs.filter { song ->
            val matchesQuery = query.isBlank() ||
                listOf(song.title, song.artist, song.album, song.folder).any { it.contains(query, true) }
            val matchesFavorites = !showFavorites || song.id in favorites
            matchesQuery && matchesFavorites
        }
    }

    fun playAndOpen(song: Song) {
        onPlay(song)
        showPlayer = true
    }

    Column(
        Modifier.fillMaxSize()
            .background(Bg)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        if (showPlayer && current != null) {
            NowPlayingScreen(
                song = current,
                isPlaying = isPlaying,
                isFavorite = current.id in favorites,
                position = position,
                duration = duration,
                onBack = { showPlayer = false },
                onTogglePlay = onTogglePlay,
                onPrevious = onPrevious,
                onNext = onNext,
                onSeek = onSeek,
                onToggleFavorite = { onToggleFavorite(current) }
            )
        } else {
            Row(
                Modifier.fillMaxWidth().padding(start = 22.dp, end = 18.dp, top = 14.dp, bottom = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("ẞetaMusic", color = TextMain, fontSize = 27.sp, fontWeight = FontWeight.Black, letterSpacing = (-1).sp)
                    Text("TU MÚSICA. TU ESPACIO.", color = Lime, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                }
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(SurfaceLight),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.GraphicEq, contentDescription = "Audio", tint = Lime, modifier = Modifier.size(25.dp))
                }
            }

            Row(
                Modifier.padding(horizontal = 18.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp)).background(SurfaceDark).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Search, null, tint = TextMuted)
                Spacer(Modifier.width(10.dp))
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Buscar canciones, artistas, álbumes…", color = TextMuted) },
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
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                listOf("Canciones", "Artistas", "Álbumes", "Carpetas").forEach { item ->
                    val selected = tab == item
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(50))
                            .background(if (selected) Lime else SurfaceDark)
                            .clickable {
                                tab = item
                                selectedGroupId = null
                                showFavorites = false
                            }
                            .padding(horizontal = 3.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            item,
                            color = if (selected) Color(0xFF10140D) else TextMuted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            if (selectedGroup != null) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { selectedGroupId = null }) {
                        Icon(Icons.Default.ArrowBack, "Volver a $tab", tint = Lime)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(selectedGroup.title, color = TextMain, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(selectedGroup.subtitle, color = TextMuted, fontSize = 11.sp)
                    }
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 15.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(if (showFavorites) "TUS FAVORITOS" else when (tab) {
                            "Artistas" -> "TU COLECCIÓN DE ARTISTAS"
                            "Álbumes" -> "TUS ÁLBUMES"
                            "Carpetas" -> "TUS CARPETAS"
                            else -> "TU BIBLIOTECA"
                        }, color = TextMuted, fontSize = 9.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold)
                        val countText = when {
                            loading -> "Leyendo música…"
                            tab == "Canciones" -> "${filteredSongs.size} pistas"
                            else -> "${filteredGroups.size} elementos"
                        }
                        Text(countText, color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = { showFavorites = !showFavorites }) {
                        Icon(if (showFavorites) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, tint = Lime, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(if (showFavorites) "Todo" else "Favoritos", color = Lime)
                    }
                }
            }

            when {
                !hasPermission -> EmptyState(
                    title = "Permiso necesario",
                    message = "Permite el acceso al audio para mostrar tu biblioteca.",
                    action = "Conceder permiso",
                    onAction = onRequestPermission
                )
                loading -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Lime)
                }
                tab != "Canciones" && selectedGroup == null && filteredGroups.isEmpty() -> EmptyState(
                    title = "No hay elementos",
                    message = "No se encontraron ${tab.lowercase(Locale.getDefault())} con ese filtro.",
                    action = "Limpiar búsqueda",
                    onAction = { query = ""; showFavorites = false }
                )
                tab == "Canciones" && filteredSongs.isEmpty() -> EmptyState(
                    title = if (songs.isEmpty()) "No hay canciones" else "Sin resultados",
                    message = if (songs.isEmpty()) "Añade archivos de música al teléfono y vuelve a intentarlo." else "Prueba otra búsqueda o cambia el filtro.",
                    action = if (songs.isEmpty()) "Actualizar" else "Limpiar búsqueda",
                    onAction = { if (songs.isEmpty()) onRequestPermission() else { query = ""; showFavorites = false } }
                )
                selectedGroup != null && filteredSongs.isEmpty() -> EmptyState(
                    title = "No hay canciones aquí",
                    message = "Prueba otra búsqueda o vuelve a la lista anterior.",
                    action = "Volver",
                    onAction = { selectedGroupId = null; query = "" }
                )
                tab != "Canciones" && selectedGroup == null -> LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    items(filteredGroups, key = { it.id }) { group ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp))
                                .background(SurfaceDark).clickable { selectedGroupId = group.id }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AlbumArtwork(
                                song = group.coverSong,
                                modifier = Modifier.size(58.dp).clip(RoundedCornerShape(12.dp)),
                                iconSize = 25.dp
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(group.title, color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(4.dp))
                                Text(group.subtitle, color = TextMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Icon(Icons.Default.ChevronRight, null, tint = Lime)
                        }
                    }
                }
                else -> LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(filteredSongs, key = { it.id }) { song ->
                        val active = song.id == currentId
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                                .background(if (active) SurfaceLight else Color.Transparent)
                                .clickable { playAndOpen(song) }
                                .padding(horizontal = 10.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AlbumArtwork(
                                song = song,
                                modifier = Modifier.size(54.dp).clip(RoundedCornerShape(12.dp)),
                                iconSize = 23.dp
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(song.title, color = if (active) Lime else TextMain, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                                Spacer(Modifier.height(3.dp))
                                Text("${song.artist} · ${song.album}", color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp)
                            }
                            IconButton(onClick = { onToggleFavorite(song) }) {
                                Icon(if (song.id in favorites) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Favorito", tint = if (song.id in favorites) Lime else TextMuted, modifier = Modifier.size(19.dp))
                            }
                        }
                    }
                }
            }

            if (current != null) {
                MiniPlayer(
                    song = current,
                    isPlaying = isPlaying,
                    position = position,
                    duration = duration,
                    onOpen = { showPlayer = true },
                    onPrevious = onPrevious,
                    onTogglePlay = onTogglePlay,
                    onNext = onNext,
                    onSeek = onSeek
                )
            }
        }
    }
}

@Composable
private fun AlbumArtwork(song: Song?, modifier: Modifier = Modifier, iconSize: Dp = 28.dp) {
    val artUri = remember(song?.albumId) {
        song?.albumId?.takeIf { it > 0 }?.let {
            ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), it)
        }
    }
    Box(modifier.background(SurfaceDark), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.Album, contentDescription = null, tint = Lime, modifier = Modifier.size(iconSize))
        if (artUri != null) {
            AsyncImage(
                model = artUri,
                contentDescription = "Portada de ${song?.album ?: song?.title.orEmpty()}",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun MiniPlayer(
    song: Song,
    isPlaying: Boolean,
    position: Long,
    duration: Long,
    onOpen: () -> Unit,
    onPrevious: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit
) {
    Column(Modifier.fillMaxWidth().background(SurfaceDark).padding(horizontal = 18.dp, vertical = 9.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onOpen),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AlbumArtwork(song, Modifier.size(43.dp).clip(RoundedCornerShape(9.dp)), 20.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(song.title, color = TextMain, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                Text(song.artist, color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp)
            }
            IconButton(onClick = onPrevious) { Icon(Icons.Default.SkipPrevious, "Anterior", tint = TextMain) }
            IconButton(onClick = onTogglePlay) {
                Icon(if (isPlaying) Icons.Default.PauseCircleFilled else Icons.Default.PlayCircleFilled, "Reproducir o pausar", tint = Lime, modifier = Modifier.size(32.dp))
            }
            IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, "Siguiente", tint = TextMain) }
        }
        Slider(
            value = if (duration > 0) position.coerceAtMost(duration).toFloat() else 0f,
            onValueChange = { onSeek(it.toLong()) },
            valueRange = 0f..duration.coerceAtLeast(1L).toFloat(),
            modifier = Modifier.height(24.dp),
            colors = SliderDefaults.colors(thumbColor = Lime, activeTrackColor = Lime, inactiveTrackColor = SurfaceLight)
        )
    }
}

@Composable
private fun NowPlayingScreen(
    song: Song,
    isPlaying: Boolean,
    isFavorite: Boolean,
    position: Long,
    duration: Long,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleFavorite: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Volver a la biblioteca", tint = TextMain) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("REPRODUCIENDO", color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Text("ẞetaMusic", color = Lime, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            IconButton(onClick = onToggleFavorite) {
                Icon(if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Favorito", tint = if (isFavorite) Lime else TextMuted)
            }
        }

        Spacer(Modifier.height(22.dp))
        AlbumArtwork(
            song = song,
            modifier = Modifier.fillMaxWidth().heightIn(max = 330.dp).aspectRatio(1f).clip(RoundedCornerShape(26.dp)),
            iconSize = 72.dp
        )
        Spacer(Modifier.height(25.dp))
        Text(song.title, color = TextMain, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(7.dp))
        Text(song.artist, color = Lime, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(song.album, color = TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(23.dp))

        Slider(
            value = if (duration > 0) position.coerceAtMost(duration).toFloat() else 0f,
            onValueChange = { onSeek(it.toLong()) },
            valueRange = 0f..duration.coerceAtLeast(1L).toFloat(),
            colors = SliderDefaults.colors(thumbColor = Lime, activeTrackColor = Lime, inactiveTrackColor = SurfaceLight)
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(position), color = TextMuted, fontSize = 11.sp)
            Text(formatTime(duration), color = TextMuted, fontSize = 11.sp)
        }
        Spacer(Modifier.height(18.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrevious, modifier = Modifier.size(54.dp)) {
                Icon(Icons.Default.SkipPrevious, "Anterior", tint = TextMain, modifier = Modifier.size(35.dp))
            }
            IconButton(onClick = onTogglePlay, modifier = Modifier.size(76.dp)) {
                Icon(if (isPlaying) Icons.Default.PauseCircleFilled else Icons.Default.PlayCircleFilled, "Reproducir o pausar", tint = Lime, modifier = Modifier.size(72.dp))
            }
            IconButton(onClick = onNext, modifier = Modifier.size(54.dp)) {
                Icon(Icons.Default.SkipNext, "Siguiente", tint = TextMain, modifier = Modifier.size(35.dp))
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.MusicNote, null, tint = TextMuted, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(7.dp))
            Text("Tu música, sin conexión", color = TextMuted, fontSize = 12.sp)
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun EmptyState(title: String, message: String, action: String, onAction: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(30.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.LibraryMusic, null, tint = Lime, modifier = Modifier.size(52.dp))
        Spacer(Modifier.height(15.dp))
        Text(title, color = TextMain, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(7.dp))
        Text(message, color = TextMuted, fontSize = 13.sp, lineHeight = 19.sp)
        Spacer(Modifier.height(15.dp))
        Button(
            onClick = onAction,
            colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Color(0xFF11150D))
        ) { Text(action, fontWeight = FontWeight.Bold) }
    }
}

private fun formatTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0L) / 1000L).toInt()
    return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
}
