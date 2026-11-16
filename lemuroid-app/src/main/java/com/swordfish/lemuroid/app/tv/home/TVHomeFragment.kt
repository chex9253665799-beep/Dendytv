package com.swordfish.lemuroid.app.tv.home

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import coil.compose.AsyncImage
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.shared.GameInteractor
import com.swordfish.lemuroid.app.shared.library.LibraryIndexScheduler
import com.swordfish.lemuroid.app.shared.settings.StorageFrameworkPickerLauncher
import com.swordfish.lemuroid.app.tv.folderpicker.TVFolderPickerLauncher
import com.swordfish.lemuroid.app.tv.settings.TVSettingsActivity
import com.swordfish.lemuroid.app.tv.shared.TVHelper
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.MetaSystemID
import com.swordfish.lemuroid.lib.library.db.RetrogradeDatabase
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.library.metaSystemID
import dagger.android.support.AndroidSupportInjection
import javax.inject.Inject

/**
 * Главный экран Dendy TV в стиле Apple TV: меню-капсула сверху, большой баннер «Продолжить»,
 * ряды плиток. Выбранная плитка плавно увеличивается. Цвета — как у «Моста».
 */
class TVHomeFragment : Fragment() {
    @Inject
    lateinit var retrogradeDb: RetrogradeDatabase

    @Inject
    lateinit var gameInteractor: GameInteractor

    override fun onAttach(context: Context) {
        AndroidSupportInjection.inject(this)
        super.onAttach(context)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val factory = TVHomeViewModel.Factory(retrogradeDb, requireContext().applicationContext)
        val homeViewModel = ViewModelProvider(this, factory)[TVHomeViewModel::class.java]

        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    val state by homeViewModel.getViewStates()
                        .collectAsState(TVHomeViewModel.HomeViewState())
                    HomeScreen(state)
                }
            }
        }
    }

    // ---------- действия ----------

    private fun openSystem(meta: MetaSystemID, count: Int) {
        if (count == 0) {
            Toast.makeText(requireContext(), "Игр пока нет — нажмите «Добавить игры»", Toast.LENGTH_LONG).show()
            return
        }
        findNavController().navigate(
            TVHomeFragmentDirections.actionNavigationSystemsToNavigationGames(meta.name),
        )
    }

    private fun play(game: Game) = gameInteractor.onGamePlay(game)

    private fun addGames() {
        if (TVHelper.isSAFSupported(requireContext())) {
            StorageFrameworkPickerLauncher.pickFolder(requireContext())
        } else {
            TVFolderPickerLauncher.pickFolder(requireContext())
        }
    }

    private fun settings() = startActivity(Intent(requireContext(), TVSettingsActivity::class.java))

    private fun search() = findNavController().navigate(R.id.navigation_search)

    private fun favorites() = findNavController().navigate(R.id.navigation_favorites)

    private fun rescan(stop: Boolean) {
        val ctx = requireContext().applicationContext
        if (stop) LibraryIndexScheduler.cancelLibrarySync(ctx) else LibraryIndexScheduler.scheduleLibrarySync(ctx)
    }

    // ---------- экран ----------

    private data class Console(
        val meta: MetaSystemID,
        val title: String,
        val sub: String,
        val icon: ImageVector,
        val brush: Brush,
    )

    private val consoles =
        listOf(
            Console(MetaSystemID.NES, "Dendy", "8 БИТ · NES", Icons.Rounded.Gamepad, Brush.linearGradient(listOf(Violet, Color(0xFF4C1D95)))),
            Console(MetaSystemID.GENESIS, "Sega", "16 БИТ · MEGA DRIVE", Icons.Rounded.SportsEsports, Brush.linearGradient(listOf(Pink, Color(0xFF9D174D)))),
            Console(MetaSystemID.PSX, "PlayStation", "PS1 · ДИСКИ", Icons.Rounded.Album, Brush.linearGradient(listOf(Color(0xFF3B3363), Panel2))),
        )

    @Composable
    private fun HomeScreen(state: TVHomeViewModel.HomeViewState) {
        val counts = state.metaSystems.associate { it.metaSystem to it.count }
        val recent = (state.recentGames + state.favoritesGames).sortedByDescending { it.lastPlayedAt ?: 0 }
        val hero = recent.firstOrNull()
        val firstFocus = remember { FocusRequester() }

        LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

        Box(
            Modifier
                .fillMaxSize()
                .background(Bg)
                .background(Brush.radialGradient(listOf(Violet.copy(alpha = .45f), Color.Transparent), center = androidx.compose.ui.geometry.Offset(0f, 0f), radius = 900f))
                .background(Brush.radialGradient(listOf(Pink.copy(alpha = .22f), Color.Transparent), center = androidx.compose.ui.geometry.Offset(2400f, 1400f), radius = 1100f)),
        ) {
            LazyColumn(
                contentPadding = PaddingValues(top = 96.dp, bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(26.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item {
                    Hero(hero, firstFocus)
                }
                item {
                    Section("Приставки") {
                        items(consoles) { c ->
                            val n = counts[c.meta] ?: 0
                            Tile(
                                title = c.title,
                                sub = if (n > 0) "${c.sub} · $n" else c.sub,
                                icon = c.icon,
                                brush = c.brush,
                                focusRequester = if (hero == null && c.meta == MetaSystemID.NES) firstFocus else null,
                            ) { openSystem(c.meta, n) }
                        }
                        item {
                            Tile("Добавить игры", "ПАПКА С ИГРАМИ", Icons.Rounded.Add, Brush.linearGradient(listOf(Panel2, Panel))) { addGames() }
                        }
                    }
                }
                if (state.recentGames.isNotEmpty()) {
                    item {
                        Section("Недавние") {
                            items(state.recentGames, key = { it.id }) { g -> GameTile(g) { play(g) } }
                        }
                    }
                }
                if (state.favoritesGames.isNotEmpty()) {
                    item {
                        Section("Избранное") {
                            items(state.favoritesGames, key = { it.id }) { g -> GameTile(g) { play(g) } }
                            item { Tile("Все избранные", "ПОКАЗАТЬ", Icons.Rounded.Favorite, Brush.linearGradient(listOf(Panel2, Panel))) { favorites() } }
                        }
                    }
                }
                item {
                    Section("Ещё") {
                        item { Tile("Поиск", "ПО НАЗВАНИЮ", Icons.Rounded.Search, Brush.linearGradient(listOf(Panel2, Panel))) { search() } }
                        item {
                            if (state.scanInProgress) {
                                Tile("Остановить", "ИДЁТ ПОИСК ИГР", Icons.Rounded.Stop, Brush.linearGradient(listOf(Panel2, Panel))) { rescan(true) }
                            } else {
                                Tile("Обновить", "НАЙТИ НОВЫЕ ИГРЫ", Icons.Rounded.Refresh, Brush.linearGradient(listOf(Panel2, Panel))) { rescan(false) }
                            }
                        }
                        item { Tile("Настройки", "УПРАВЛЕНИЕ, ЭКРАН", Icons.Rounded.Settings, Brush.linearGradient(listOf(Panel2, Panel))) { settings() } }
                    }
                }
            }
            TopBar(state)
        }
    }

    @Composable
    private fun TopBar(state: TVHomeViewModel.HomeViewState) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Bg, Bg.copy(alpha = .85f), Color.Transparent)))
                .padding(start = 48.dp, end = 48.dp, top = 22.dp, bottom = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Grad),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.SportsEsports, null, tint = Color.White, modifier = Modifier.size(22.dp)) }
            Spacer(Modifier.width(10.dp))
            Text("Dendy TV", color = Txt, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (state.indexInProgress) {
                Text("Ищем игры…", color = Mut, fontSize = 14.sp)
            }
        }
    }

    @Composable
    private fun Hero(
        game: Game?,
        focusRequester: FocusRequester,
    ) {
        Box(
            Modifier
                .padding(horizontal = 48.dp)
                .fillMaxWidth()
                .height(270.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF2A1257), Color(0xFF5B1F7A), Color(0xFFB0306F)))),
        ) {
            if (game != null) {
                AsyncImage(
                    model = game.displayCoverUrl(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.align(Alignment.CenterEnd).width(520.dp).fillMaxHeight().graphicsLayer { alpha = .55f },
                )
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xFF2A1257), Color(0xFF5B1F7A).copy(alpha = .85f), Color.Transparent))))
            }
            Column(Modifier.align(Alignment.BottomStart).padding(start = 40.dp, bottom = 32.dp, end = 40.dp)) {
                val system = game?.let { runCatching { consoleName(GameSystem.findById(it.systemId).metaSystemID()) }.getOrNull() }
                Text(
                    if (game != null) "ПРОДОЛЖИТЬ · ${system ?: ""}" else "ДОБРО ПОЖАЛОВАТЬ",
                    color = Color(0xFFF9C6E3), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp,
                )
                Text(
                    game?.displayName() ?: "Dendy · Sega · PlayStation",
                    color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.ExtraBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
                )
                Text(
                    if (game != null) "Нажмите «Играть», чтобы продолжить" else "Добавьте свои игры — они появятся здесь",
                    color = Color(0xFFE6DCFF), fontSize = 16.sp,
                )
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (game != null) {
                        Pill("Играть", Icons.Rounded.PlayArrow, focusRequester) { play(game) }
                    } else {
                        Pill("Добавить игры", Icons.Rounded.Add, focusRequester) { addGames() }
                    }
                }
            }
        }
    }

    private fun consoleName(m: MetaSystemID) = consoles.firstOrNull { it.meta == m }?.title

    @Composable
    private fun Section(
        title: String,
        content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
    ) {
        Column {
            Text(title, color = Color(0xFFE9E4FF), fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 48.dp, bottom = 12.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 48.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                content = content,
            )
        }
    }

    /** Фокус с плавным увеличением, как в Apple TV. Нажатие — центр пульта / Enter / A на геймпаде. */
    @Composable
    private fun Modifier.tvFocus(
        shape: RoundedCornerShape,
        focusRequester: FocusRequester?,
        onFocus: (Boolean) -> Unit,
        onClick: () -> Unit,
    ): Modifier {
        var focused by remember { mutableStateOf(false) }
        val scale by animateFloatAsState(if (focused) 1.1f else 1f, tween(260), label = "scale")
        return this
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) }
            .onPreviewKeyEvent {
                val code = it.key.nativeKeyCode
                val ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
                    code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BUTTON_A
                if (ok && it.type == KeyEventType.KeyUp) onClick()
                ok
            }
            .focusable()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(if (focused) 24.dp else 0.dp, shape)
            .then(if (focused) Modifier.border(2.dp, Color.White.copy(alpha = .9f), shape) else Modifier)
    }

    @Composable
    private fun Tile(
        title: String,
        sub: String,
        icon: ImageVector,
        brush: Brush,
        focusRequester: FocusRequester? = null,
        onClick: () -> Unit,
    ) {
        val shape = RoundedCornerShape(20.dp)
        Box(
            Modifier
                .size(width = 260.dp, height = 146.dp)
                .tvFocus(shape, focusRequester, {}, onClick)
                .clip(shape)
                .background(brush),
        ) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).size(44.dp))
            Column(Modifier.align(Alignment.BottomStart).padding(18.dp)) {
                Text(sub, color = Color.White.copy(alpha = .7f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)
                Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }

    @Composable
    private fun GameTile(
        game: Game,
        onClick: () -> Unit,
    ) {
        val shape = RoundedCornerShape(18.dp)
        Column(Modifier.width(150.dp)) {
            Box(
                Modifier
                    .size(150.dp)
                    .tvFocus(shape, null, {}, onClick)
                    .clip(shape)
                    .background(Panel2),
            ) {
                AsyncImage(
                    model = game.displayCoverUrl(),
                    contentDescription = game.displayName(),
                    contentScale = ContentScale.Crop,
                    fallback = androidx.compose.ui.graphics.painter.ColorPainter(Panel2),
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Text(
                game.displayName(), color = Txt, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp),
            )
        }
    }

    @Composable
    private fun Pill(
        text: String,
        icon: ImageVector,
        focusRequester: FocusRequester?,
        onClick: () -> Unit,
    ) {
        val shape = RoundedCornerShape(14.dp)
        var focused by remember { mutableStateOf(false) }
        Row(
            Modifier
                .tvFocus(shape, focusRequester, { focused = it }, onClick)
                .clip(shape)
                .background(if (focused) Color.White else Color.White.copy(alpha = .16f))
                .padding(horizontal = 22.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val c = if (focused) Color(0xFF1A1033) else Color.White
            Icon(icon, null, tint = c, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, color = c, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }

    @dagger.Module
    class Module

    companion object {
        val Bg = Color(0xFF0C0A16)
        val Panel = Color(0xFF151227)
        val Panel2 = Color(0xFF1D1934)
        val Violet = Color(0xFF7C3AED)
        val Pink = Color(0xFFEC4899)
        val Txt = Color(0xFFF4F1FF)
        val Mut = Color(0xFFA59FC4)
        val Grad = Brush.linearGradient(listOf(Violet, Pink))
    }
}
