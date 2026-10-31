@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.openread.ui.reader

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import app.openread.container
import app.openread.tts.ReadAloud
import app.openread.data.PageMode
import app.openread.data.ReaderPalette
import app.openread.data.Settings
import app.openread.data.PageAnim
import app.openread.data.isLocal
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.SlowMotionVideo
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import app.openread.engine.SearchBook
import app.openread.ui.components.DataAskDialog
import app.openread.ui.components.PinSourceRow
import app.openread.ui.components.SourceNameFilter
import app.openread.ui.components.SwitchOfferDialog
import app.openread.ui.components.AltSourceItem
import app.openread.ui.components.arrange
import app.openread.ui.components.rememberAltSourceActions
import app.openread.ui.components.rememberPinnedSources
import app.openread.ui.components.factory
import app.openread.ui.detail.TocSheet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(bookUrl: String, startIndex: Int?, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val vm: ReaderViewModel = viewModel(key = "reader:$bookUrl", factory = factory { ReaderViewModel(ctx.container, bookUrl, startIndex) })
    val s by vm.state.collectAsState()
    val settings by vm.settings.collectAsState()
    var menu by rememberSaveable { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showSwitch by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var showCache by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf<String?>(null) }
    var autoPage by rememberSaveable { mutableStateOf(false) }
    val turner = remember { PageTurner() }
    val palette = settings.palette
    val bg = Color(palette.bg)
    val fg = Color(palette.fg)
    val look = rememberReaderLook(settings, fg, bg)
    val isLocal = s.book?.isLocal == true
    val snack = remember { androidx.compose.material3.SnackbarHostState() }

    ReaderWindowEffects(settings, immersive = !menu)
    DisposableEffect(Unit) { onDispose { vm.flush() } }
    BackHandler(enabled = menu) { menu = false }
    LaunchedEffect(s.message) { s.message?.let { snack.showSnackbar(it); vm.consumeMessage() } }
    LaunchedEffect(s.position.index, s.chapters.size) { if (s.chapters.isNotEmpty()) vm.prefetch(s.position.index) }
    val ra by ReadAloud.state.collectAsState()
    val listening = ra.active && ra.bookUrl == s.book?.bookUrl
    // Reading aloud moved to another chapter, or this reader was opened while it runs: the page follows it. The other way
    // round (the reader turns a page or jumps to a chapter) is reported by the readers below, and reading follows the page.
    LaunchedEffect(ra.chapterIndex, listening, s.loaded) {
        if (s.loaded && listening && ra.chapterIndex >= 0 && ra.chapterIndex != s.position.index) vm.go(ra.chapterIndex, 0)
    }
    LaunchedEffect(settings.readAloudRate) { ReadAloud.setRate(settings.readAloudRate) }
    LaunchedEffect(ra.message) { ra.message?.let { snack.showSnackbar(it); ReadAloud.consumeMessage() } }

    // 自动翻页：菜单或任何面板打开时暂停，关上后接着翻
    val sheetOpen = showToc || showSettings || showSwitch || showBookmarks || showCache || selection != null
    val autoRun = autoPage && !menu && !sheetOpen

    // 音量键翻页；听书时音量键照常调音量
    val volumeOn = settings.volumeKeys && !listening && s.loaded && s.error == null
    DisposableEffect(volumeOn) {
        ReaderKeys.handler = if (volumeOn) { next -> (if (next) turner.next else turner.prev)?.invoke(); true } else null
        onDispose { ReaderKeys.handler = null }
    }

    // 阅读时长：页面在前台时计
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> vm.onResume()
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> vm.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs); vm.onPause() }
    }

    Box(Modifier.fillMaxSize().background(bg)) {
        when {
            !s.loaded -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = fg)
            s.error != null -> Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(s.error ?: "", color = fg)
                TextButton(onClick = onBack) { Text("返回") }
            }
            else -> {
                if (settings.pageMode == PageMode.Slide) SlideReader(vm, s, look, fg, ra, turner, autoRun, settings.autoPageSeconds, onToggleMenu = { menu = !menu }, onLongPress = { selection = it })
                else ScrollReader(vm, s, look, fg, ra, turner, autoRun, settings.autoPageSeconds, onToggleMenu = { menu = !menu }, onLongPress = { selection = it })
            }
        }

        AnimatedVisibility(menu, Modifier.align(Alignment.TopCenter), enter = fadeIn() + slideInVertically { -it }, exit = fadeOut() + slideOutVertically { -it }) {
            TopAppBar(
                title = { Text(s.book?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } },
                actions = {
                    IconButton(onClick = { showBookmarks = true }) { Icon(Icons.Outlined.BookmarkBorder, "书签") }
                    IconButton(onClick = { vm.reload(s.position.index) }) { Icon(Icons.Outlined.Refresh, "重新加载本章") }
                    if (!isLocal) IconButton(onClick = { showSwitch = true }) { Icon(Icons.Outlined.SwapHoriz, "换源") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
        }
        AnimatedVisibility(menu, Modifier.align(Alignment.BottomCenter), enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it }) {
            BottomMenu(
                s, vm, listening = ra.playing, session = listening, rate = settings.readAloudRate,
                onRate = { r -> vm.updateSettings { it.copy(readAloudRate = (r.coerceIn(ReadAloud.MIN_RATE, ReadAloud.MAX_RATE) / ReadAloud.RATE_STEP).roundToInt() * ReadAloud.RATE_STEP) } },
                onToc = { showToc = true }, onListen = { vm.readAloud(ctx) }, onSettings = { showSettings = true },
                onCache = if (isLocal) null else ({ showCache = true }),
                autoPage = autoPage, onAuto = { autoPage = !autoPage; if (autoPage) menu = false },
            )
        }
        androidx.compose.material3.SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 72.dp))
        if (s.busy) CircularProgressIndicator(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(16.dp).size(24.dp), color = fg)
    }

    if (showToc) TocSheet(s.chapters.map { app.openread.engine.Chapter(it.idx, vm.title(it.title), it.url, it.isVolume) }, current = s.position.index, onDismiss = { showToc = false }) { idx ->
        showToc = false; menu = false; vm.go(idx, 0, jump = true)
    }
    if (showBookmarks) BookmarkSheet(vm, onDismiss = { showBookmarks = false }, onOpen = { showBookmarks = false; menu = false })
    if (showCache) CacheSheet(vm, s) { showCache = false }
    selection?.let { SelectSheet(it) { selection = null } }
    if (showSettings) SettingsSheet(settings, vm::updateSettings) { showSettings = false }
    if (showSwitch) SwitchSheet(vm) { showSwitch = false }
    s.offer?.let { SwitchOfferDialog(it, onSwitch = vm::acceptOffer, onDismiss = vm::dismissOffer) }
    DataAskDialog(vm.dataAsk)
}

/** Immersive reading, brightness and keep-awake, undone when the reader closes. */
@Composable
private fun ReaderWindowEffects(settings: Settings, immersive: Boolean) {
    val view = LocalView.current
    val window = (view.context as? Activity)?.window
    LaunchedEffect(immersive, window) {
        if (window == null) return@LaunchedEffect
        val c = WindowCompat.getInsetsController(window, view)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (immersive) c.hide(WindowInsetsCompat.Type.systemBars()) else c.show(WindowInsetsCompat.Type.systemBars())
    }
    val initialLightBars = remember(window) {
        window?.let { WindowCompat.getInsetsController(it, view).let { c -> c.isAppearanceLightStatusBars to c.isAppearanceLightNavigationBars } }
    }
    LaunchedEffect(settings.palette, window) {
        if (window == null) return@LaunchedEffect
        val light = settings.palette != ReaderPalette.Grey && settings.palette != ReaderPalette.Night
        WindowCompat.getInsetsController(window, view).apply { isAppearanceLightStatusBars = light; isAppearanceLightNavigationBars = light }
    }
    DisposableEffect(window) {
        onDispose {
            if (window != null && initialLightBars != null) {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = initialLightBars.first
                    isAppearanceLightNavigationBars = initialLightBars.second
                }
            }
        }
    }
    DisposableEffect(settings.keepScreenOn) {
        view.keepScreenOn = settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }
    DisposableEffect(settings.brightness, window) {
        val lp = window?.attributes
        val old = lp?.screenBrightness
        if (window != null && lp != null) { lp.screenBrightness = settings.brightness; window.attributes = lp }
        onDispose {
            if (window != null && lp != null && old != null) { lp.screenBrightness = -1f; window.attributes = lp }
        }
    }
    DisposableEffect(window) {
        onDispose {
            if (window != null) WindowCompat.getInsetsController(window, view).show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

// ---- paging mode ---------------------------------------------------------------------------------------------------

/**
 * Space for the system bars and cutout whether or not they are showing. Toggling the menu shows and hides the bars; if the
 * page area followed them, every toggle would change the page height and repaginate the chapter.
 */
private val StableInsets: WindowInsets @Composable get() = WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout)

private sealed interface Slot {
    class Content(val chapter: Int, val pages: ChapterPages, val page: Int) : Slot
    class Status(val chapter: Int, val text: ChapterText) : Slot
}

@Composable
private fun SlideReader(
    vm: ReaderViewModel, s: ReaderState, look: ReaderLook, fg: Color, listen: ReadAloud.State, turner: PageTurner,
    autoRun: Boolean, autoSeconds: Int, onToggleMenu: () -> Unit, onLongPress: (String) -> Unit,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val bookUrl = rememberUpdatedState(s.book?.bookUrl)
    val listenNow = rememberUpdatedState(listen)
    val listening = listen.active && listen.bookUrl == s.book?.bookUrl
    // Page turns move reading aloud along, but not before the page has caught up with a session that was already running
    // when the reader opened (otherwise opening the reader would drag the audio back to the saved page).
    var aligned by remember { mutableStateOf(!listening) }
    LaunchedEffect(listening) { if (!listening) aligned = true }
    val alignedNow = rememberUpdatedState(aligned)
    val highlight = fg.copy(alpha = 0.16f)
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(StableInsets)) {
        val sidePad = look.margin
        val topPad = 18.dp
        val footerH = 28.dp
        val widthPx = with(density) { (maxWidth - sidePad * 2).roundToPx() }
        val heightPx = with(density) { (maxHeight - topPad - footerH).roundToPx() }
        val style = look.style
        val cache = remember(widthPx, heightPx, look) { HashMap<Pair<Int, Int>, ChapterPages>() }

        fun slotsFor(idx: Int): List<Slot> {
            val ch = s.chapters.getOrNull(idx) ?: return emptyList()
            return when (val t = vm.texts[idx]) {
                is ChapterText.Ready -> {
                    val title = vm.title(ch.title)
                    val pages = cache.getOrPut(idx to (title + t.text).hashCode()) {
                        val paragraphs = Paginator.paragraphs(title, t.text)
                        Paginator.paginate(measurer, Paginator.display(title, t.text, look.titleSize, look.typeset), style, widthPx, heightPx, title)
                            .copyWith(paragraphs, Paginator.paragraphStarts(paragraphs))
                    }
                    pages.pages.indices.map { Slot.Content(idx, pages, it) }
                }
                null -> listOf(Slot.Status(idx, ChapterText.Loading))
                else -> listOf(Slot.Status(idx, t))
            }
        }

        val cur = s.position.index
        val curSlots = slotsFor(cur)
        val prevEdge = if (cur > 0) slotsFor(cur - 1).lastOrNull() else null
        val nextEdge = if (cur < s.chapters.lastIndex) slotsFor(cur + 1).firstOrNull() else null
        val slots = listOfNotNull(prevEdge) + curSlots + listOfNotNull(nextEdge)
        val lead = if (prevEdge != null) 1 else 0
        val curReady = curSlots.firstOrNull() is Slot.Content

        // Recreate the pager when the chapter, its readiness or the layout changes; the neighbouring page shown at an
        // edge is identical to the page we land on, so the switch is not visible.
        key(cur, curReady, widthPx, heightPx, look, prevEdge != null, nextEdge != null) {
            val start = remember {
                val pages = (curSlots.firstOrNull() as? Slot.Content)?.pages
                lead + (pages?.pageAt(s.position.offset) ?: 0)
            }
            val pager = rememberPagerState(initialPage = start.coerceIn(0, (slots.size - 1).coerceAtLeast(0))) { slots.size }

            /** 翻一页（[delta] = 1 下一页，-1 上一页）；翻页动画选“无”时直接换页。 */
            suspend fun turn(delta: Int) {
                val target = pager.currentPage + delta
                if (target !in 0 until pager.pageCount || pager.isScrollInProgress) return
                if (look.anim == PageAnim.None) pager.scrollToPage(target) else pager.animateScrollToPage(target)
            }
            DisposableEffect(pager) {
                turner.next = { scope.launch { turn(1) } }
                turner.prev = { scope.launch { turn(-1) } }
                onDispose { }
            }

            // 主动跳转（书签、目录）到本章的另一个位置：翻到那一页
            val seenJump = remember { s.jump }
            LaunchedEffect(s.jump) {
                if (s.jump == seenJump) return@LaunchedEffect
                val pages = (curSlots.firstOrNull() as? Slot.Content)?.pages ?: return@LaunchedEffect
                val target = lead + pages.pageAt(s.position.offset)
                if (target in 0 until pager.pageCount && pager.currentPage != target) pager.scrollToPage(target)
            }

            // 自动翻页：每页停留 autoSeconds 秒，翻到书的最后一页为止
            LaunchedEffect(pager, autoRun, autoSeconds) {
                if (!autoRun) return@LaunchedEffect
                while (true) {
                    kotlinx.coroutines.delay(autoSeconds * 1000L)
                    if (pager.currentPage >= pager.pageCount - 1) break
                    turn(1)
                }
            }

            LaunchedEffect(pager) {
                snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { page ->
                    when (val slot = slots.getOrNull(page)) {
                        is Slot.Content -> {
                            if (slot.chapter != cur) {
                                vm.go(slot.chapter, if (slot.chapter < cur) Int.MAX_VALUE else 0)
                            } else {
                                val start = slot.pages.pages[slot.page].start
                                vm.record(cur, start)
                                // The first text on this page is where reading aloud goes on from, unless it is already on this page.
                                val (paragraph, char) = Paginator.locate(slot.pages.paragraphs, slot.pages.starts, start)
                                vm.noteVisible(cur, paragraph, char)
                                val ra = listenNow.value
                                if (alignedNow.value && ra.active && ra.bookUrl == bookUrl.value) {
                                    val span = ra.span
                                    val spokenPage = if (ra.chapterIndex == cur && span != null) slot.pages.pageAt(slot.pages.offsetOf(span.paragraph, span.from)) else -1
                                    if (spokenPage != slot.page) ReadAloud.seek(cur, paragraph, char)
                                }
                            }
                        }
                        is Slot.Status -> if (slot.chapter != cur) vm.go(slot.chapter, if (slot.chapter < cur) Int.MAX_VALUE else 0)
                        null -> {}
                    }
                }
            }

            // Reading aloud moves on: keep the sentence being read on the page that is showing.
            val curPages = (curSlots.firstOrNull() as? Slot.Content)?.pages
            LaunchedEffect(pager, listen.span, listen.chapterIndex, listening) {
                val span = listen.span
                if (!listening || span == null || curPages == null || listen.chapterIndex != cur) return@LaunchedEffect
                val target = lead + curPages.pageAt(curPages.offsetOf(span.paragraph, span.from))
                if (pager.settledPage != target && !pager.isScrollInProgress) pager.animateScrollToPage(target)
                aligned = true
            }

            Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    detectTapGestures(
                        // 长按：把这一页的文字拿出来，可以选择复制或分享
                        onLongPress = {
                            (slots.getOrNull(pager.currentPage) as? Slot.Content)?.let { sl -> onLongPress(Paginator.plain(sl.pages.pages[sl.page].text)) }
                        },
                    ) { pos ->
                        val w = size.width
                        when {
                            pos.x < w * 0.3f -> scope.launch { turn(-1) }
                            pos.x > w * 0.7f -> scope.launch { turn(1) }
                            else -> onToggleMenu()
                        }
                    }
                },
            ) {
                HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { i ->
                    val pageMod = if (look.anim == PageAnim.Cover) Modifier.coverPage(pager, i, look.bg) else Modifier
                    when (val slot = slots[i]) {
                        is Slot.Content -> {
                            val span = listen.span?.takeIf { listening && listen.chapterIndex == slot.chapter && it.to > it.from }
                            val spoken = span?.let { slot.pages.offsetOf(it.paragraph, it.from) until slot.pages.offsetOf(it.paragraph, it.to) }
                            PageView(slot, style, fg, sidePad, topPad, footerH, spoken, highlight, pageMod)
                        }
                        is Slot.Status -> Box(pageMod) { StatusView(slot, s, vm, fg) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PageView(
    slot: Slot.Content, style: TextStyle, fg: Color, sidePad: androidx.compose.ui.unit.Dp, topPad: androidx.compose.ui.unit.Dp, footerH: androidx.compose.ui.unit.Dp,
    spoken: IntRange?, highlight: Color, modifier: Modifier = Modifier,
) {
    val page = slot.pages.pages[slot.page]
    val text = remember(page, spoken, highlight) { highlighted(page, spoken, highlight) }
    Column(modifier.fillMaxSize().padding(horizontal = sidePad)) {
        Text(text, style = style, modifier = Modifier.padding(top = topPad).weight(1f))
        Row(Modifier.fillMaxWidth().height(footerH), verticalAlignment = Alignment.CenterVertically) {
            Text(slot.pages.title, color = fg.copy(alpha = 0.5f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("${slot.page + 1}/${slot.pages.pages.size}", color = fg.copy(alpha = 0.5f), fontSize = 11.sp)
        }
    }
}

/** [page]'s text with the part of it that is being read aloud ([spoken], offsets in the chapter's display text) marked. */
private fun highlighted(page: Page, spoken: IntRange?, color: Color): AnnotatedString {
    if (spoken == null) return page.text
    val from = maxOf(spoken.first, page.start) - page.start
    val to = minOf(spoken.last + 1, page.end) - page.start
    if (to <= from) return page.text
    return buildAnnotatedString {
        append(page.text)
        addStyle(SpanStyle(background = color), from, to)
    }
}

@Composable
private fun StatusView(slot: Slot.Status, s: ReaderState, vm: ReaderViewModel, fg: Color) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Text(s.chapters.getOrNull(slot.chapter)?.title.orEmpty(), color = fg, style = MaterialTheme.typography.titleMedium)
            when (val t = slot.text) {
                ChapterText.Loading -> CircularProgressIndicator(Modifier.padding(top = 16.dp), color = fg)
                is ChapterText.Failed -> {
                    Text(t.message, color = fg.copy(alpha = 0.7f), modifier = Modifier.padding(top = 12.dp))
                    TextButton(onClick = { vm.reload(slot.chapter) }) { Text("重试") }
                }
                is ChapterText.Ready -> {}
            }
        }
    }
}

// ---- scrolling mode ------------------------------------------------------------------------------------------------

@Composable
private fun ScrollReader(
    vm: ReaderViewModel, s: ReaderState, look: ReaderLook, fg: Color, listen: ReadAloud.State, turner: PageTurner,
    autoRun: Boolean, autoSeconds: Int, onToggleMenu: () -> Unit, onLongPress: (String) -> Unit,
) {
    // The list is anchored at the chapter the reader entered with; it only grows downwards. Re-anchoring happens when
    // the position jumps outside it (table of contents, slider, "previous chapter").
    var first by remember { mutableIntStateOf(s.position.index) }
    var last by remember { mutableIntStateOf(s.position.index) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val style = look.style
    val vmRef = rememberUpdatedState(vm)
    val highlight = fg.copy(alpha = 0.16f)

    val bookUrl = rememberUpdatedState(s.book?.bookUrl)
    val listenNow = rememberUpdatedState(listen)
    val listening = listen.active && listen.bookUrl == s.book?.bookUrl
    // As in the paging reader: scrolling moves reading aloud along, but only once the list has caught up with a session
    // that was already running when the reader opened.
    var aligned by remember { mutableStateOf(!listening) }
    LaunchedEffect(listening) { if (!listening) aligned = true }
    val alignedNow = rememberUpdatedState(aligned)

    // How each chapter's body was last laid out, and where it starts inside its list item: what turns a character of the
    // chapter into a place on screen and back.
    val layouts = remember { HashMap<Int, TextLayoutResult>() }
    val bodyTops = remember { HashMap<Int, Float>() }
    val paragraphCache = remember { HashMap<Pair<Int, Int>, Pair<List<String>, IntArray>>() }
    fun titleOf(ch: Int) = vm.title(s.chapters.getOrNull(ch)?.title.orEmpty())
    fun paragraphsOf(ch: Int): Pair<List<String>, IntArray>? {
        val text = (vm.texts[ch] as? ChapterText.Ready)?.text ?: return null
        val title = titleOf(ch)
        return paragraphCache.getOrPut(ch to (title + text).hashCode()) {
            val paragraphs = Paginator.paragraphs(title, text)
            paragraphs to Paginator.paragraphStarts(paragraphs)
        }
    }
    /** The top of the spoken text inside chapter [ch]'s list item, or null while that is not laid out yet. */
    fun spokenTop(ch: Int, span: ReadAloud.Span): Float? {
        val (paragraphs, starts) = paragraphsOf(ch) ?: return null
        if (span.paragraph == 0) return 0f
        val layout = layouts[ch] ?: return null
        val inBody = starts[span.paragraph.coerceIn(0, starts.lastIndex)] + span.from - (paragraphs[0].length + 2)
        val box = layout.getBoundingBox(inBody.coerceIn(0, (layout.layoutInput.text.length - 1).coerceAtLeast(0)))
        return (bodyTops[ch] ?: return null) + box.top
    }
    /** Is the sentence being read inside the visible part of the list? */
    fun spokenVisible(ra: ReadAloud.State): Boolean {
        val span = ra.span ?: return true
        val info = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == ra.chapterIndex - first + 1 } ?: return false
        val y = spokenTop(ra.chapterIndex, span) ?: return false
        val top = info.offset + y
        return top >= 0f && top < list.layoutInfo.viewportSize.height
    }

    /** 屏幕顶上的文字：记成阅读进度、听书接着读的地方；[seek] 时如果正在读的句子不在屏幕上，听书也跳过来。 */
    fun report(seek: Boolean) {
        val top = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index >= 1 && it.offset + it.size > 0 } ?: return
        val ch = first + top.index - 1
        val (paragraphs, starts) = paragraphsOf(ch) ?: return
        val layout = layouts[ch] ?: return
        val bodyTop = bodyTops[ch] ?: return
        val y = -top.offset - bodyTop
        val (paragraph, char) = if (y <= 0f) 0 to 0
        else Paginator.locate(paragraphs, starts, layout.getOffsetForPosition(Offset(0f, y)) + paragraphs[0].length + 2)
        vmRef.value.noteVisible(ch, paragraph, char)
        vmRef.value.record(ch, starts[paragraph] + char)
        val ra = listenNow.value
        if (seek && alignedNow.value && ra.active && ra.bookUrl == bookUrl.value && !spokenVisible(ra)) ReadAloud.seek(ch, paragraph, char)
    }

    /** 把第 [ch] 章的显示位置 [offset] 滚到屏幕顶上；正文还没加载、还没排好时等一会儿（最多约 10 秒）。 */
    suspend fun scrollToOffset(ch: Int, offset: Int) {
        if (offset <= 0 || offset == Int.MAX_VALUE) return
        repeat(600) {
            val layout = layouts[ch]
            val top = bodyTops[ch]
            val p = paragraphsOf(ch)
            if (layout != null && top != null && p != null) {
                val inBody = offset - (p.first[0].length + 2)
                val y = if (inBody <= 0) 0f else top + layout.getLineTop(layout.getLineForOffset(inBody.coerceIn(0, layout.layoutInput.text.length)))
                if (ch in first..last) {
                    list.scrollToItem(ch - first + 1, y.roundToInt())
                    // 滚到位后，听书起点、书签都要从这里算，不能还停在章首
                    withFrameNanos { }
                    report(seek = false)
                }
                return
            }
            withFrameNanos { }
        }
    }

    /** 屏幕上能看到的文字（长按时拿出来复制）。 */
    fun visibleText(): String {
        val vp = list.layoutInfo.viewportSize.height
        val sb = StringBuilder()
        for (info in list.layoutInfo.visibleItemsInfo) {
            if (info.index < 1) continue
            val ch = first + info.index - 1
            val layout = layouts[ch] ?: continue
            val top = bodyTops[ch] ?: continue
            val y0 = (-info.offset - top).coerceAtLeast(0f)
            val y1 = (vp - info.offset - top).coerceAtMost(layout.size.height.toFloat())
            if (y1 <= y0) continue
            val a = layout.getOffsetForPosition(Offset(0f, y0))
            val b = layout.getOffsetForPosition(Offset(layout.size.width.toFloat(), y1))
            if (b > a) sb.append(layout.layoutInput.text.text, a, b).append('\n')
        }
        return Paginator.plain(sb).trim()
    }

    // The list opens at the top of the chapter (or where the reader left it), and that is where listening starts.
    LaunchedEffect(Unit) {
        vmRef.value.noteVisible(s.position.index, 0, 0)
        scrollToOffset(s.position.index, s.position.offset)
    }

    LaunchedEffect(s.position.index) {
        val idx = s.position.index
        if (idx < first || idx > last) {
            first = idx; last = idx; list.scrollToItem(0)
            vmRef.value.noteVisible(idx, 0, 0)
            // A chapter the reader jumped to: reading aloud goes on from its start (unless that is where it already is).
            val ra = listenNow.value
            if (alignedNow.value && ra.active && ra.bookUrl == bookUrl.value && ra.chapterIndex != idx) ReadAloud.seek(idx, 0, 0)
        }
    }

    // 主动跳转（书签、目录、进度条）：从那一章重新排，滚到那个位置
    val seenJump = remember { s.jump }
    LaunchedEffect(s.jump) {
        if (s.jump == seenJump) return@LaunchedEffect
        val idx = s.position.index
        first = idx; last = idx; list.scrollToItem(0)
        vmRef.value.noteVisible(idx, 0, 0)
        scrollToOffset(idx, s.position.offset)
    }

    // 音量键：滚动约一屏
    DisposableEffect(list, look.anim) {
        fun by(sign: Float) = scope.launch {
            val d = list.layoutInfo.viewportSize.height * 0.9f * sign
            if (look.anim == PageAnim.None) list.scrollBy(d) else list.animateScrollBy(d)
            report(seek = true)
        }
        turner.next = { by(1f) }
        turner.prev = { by(-1f) }
        onDispose { }
    }

    // 自动滚动：autoSeconds 秒滚过一屏，每两秒记一次进度
    LaunchedEffect(autoRun, autoSeconds) {
        if (!autoRun) return@LaunchedEffect
        var prev = withFrameNanos { it }
        var lastReport = prev
        var carry = 0f
        while (true) {
            val now = withFrameNanos { it }
            val vp = list.layoutInfo.viewportSize.height
            carry += vp * ((now - prev) / 1e9f) / autoSeconds
            prev = now
            val px = carry.toInt()
            if (px > 0 && !list.isScrollInProgress) { carry -= px; list.dispatchRawDelta(px.toFloat()) }
            if (now - lastReport > 2_000_000_000L) { lastReport = now; report(seek = false) }
            if (!list.canScrollForward && last >= s.chapters.lastIndex) break
        }
    }

    // Reading aloud moves on: scroll so that the sentence being read stays in view.
    LaunchedEffect(listen.span, listen.chapterIndex, listening) {
        val span = listen.span
        val ch = listen.chapterIndex
        if (!listening || span == null || ch < first || ch > last || list.isScrollInProgress) return@LaunchedEffect
        val item = ch - first + 1
        var y = spokenTop(ch, span)
        var info = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == item }
        if (y == null || info == null) { // not on screen (or not measured) yet: bring the chapter in, then look again
            list.scrollToItem(item)
            withFrameNanos { }
            y = spokenTop(ch, span)
            info = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == item }
        }
        if (y != null) {
            val viewport = list.layoutInfo.viewportSize.height
            val top = (info?.offset ?: 0) + y
            if (info == null || top < viewport * 0.05f || top > viewport * 0.65f) {
                list.animateScrollToItem(item, scrollOffset = (y - viewport * 0.2f).roundToInt().coerceAtLeast(0))
            }
        }
        aligned = true
    }

    // Grow one chapter at a time as the reader nears the end. List item 0 is the "previous chapter" button.
    val lastVisible by remember { derivedStateOf { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 } }
    LaunchedEffect(lastVisible, last, first) {
        if (lastVisible >= last - first + 1 && last < s.chapters.lastIndex) { last++; vmRef.value.ensure(last + 1) }
    }

    // When a scroll comes to rest, the first text on screen is the reading position (recorded only once the reader has
    // actually scrolled, so entering scroll mode never overwrites a saved offset), and where reading aloud goes on from,
    // unless the sentence being read is already in view.
    LaunchedEffect(Unit) {
        var scrolling = false
        snapshotFlow { list.isScrollInProgress }.collect { now ->
            if (scrolling && !now) report(seek = true)
            scrolling = now
        }
    }

    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onLongPress = { onLongPress(visibleText()) }) { onToggleMenu() } }) {
        LazyColumn(Modifier.fillMaxSize().windowInsetsPadding(StableInsets), state = list, contentPadding = PaddingValues(horizontal = look.margin, vertical = 16.dp)) {
            item(key = "top") {
                if (first > 0) TextButton(onClick = { first -= 1; vm.go(first, 0) }) { Text("上一章：${titleOf(first - 1)}", color = fg.copy(alpha = 0.7f)) }
            }
            items((first..last).toList(), key = { it }) { ch ->
                val title = titleOf(ch)
                val span = listen.span?.takeIf { listening && listen.chapterIndex == ch && it.to > it.from }
                val titleStyle = style.copy(fontSize = look.titleSize, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Column(Modifier.padding(bottom = 32.dp)) {
                    Text(
                        remember(title, span) {
                            if (span?.paragraph != 0) AnnotatedString(title) else buildAnnotatedString {
                                append(title)
                                addStyle(SpanStyle(background = highlight), span.from.coerceAtMost(title.length), span.to.coerceAtMost(title.length))
                            }
                        },
                        style = titleStyle, modifier = Modifier.padding(bottom = 12.dp),
                    )
                    when (val t = vm.texts[ch]) {
                        is ChapterText.Ready -> {
                            val starts = paragraphsOf(ch)?.second
                            val body = remember(t.text, look.typeset) { Paginator.body(t.text, look.typeset) }
                            // The sentence being read is painted behind the text from its layout, rather than built into the text:
                            // the text then stays the same object, so a new sentence does not make a long chapter measure again.
                            val marked = if (span == null || span.paragraph == 0 || starts == null) null else {
                                val base = starts[span.paragraph.coerceIn(0, starts.lastIndex)] - (title.length + 2)
                                val from = (base + span.from).coerceIn(0, body.length)
                                from to (base + span.to).coerceIn(from, body.length)
                            }
                            Text(
                                body, style = style,
                                onTextLayout = { layouts[ch] = it },
                                modifier = Modifier
                                    .onGloballyPositioned { bodyTops[ch] = it.positionInParent().y }
                                    .drawBehind {
                                        val layout = layouts[ch]
                                        if (marked != null && layout != null && marked.second > marked.first) {
                                            drawPath(layout.getPathForRange(marked.first, marked.second), highlight)
                                        }
                                    },
                            )
                        }
                        is ChapterText.Failed -> Row(verticalAlignment = Alignment.CenterVertically) { Text(t.message, color = fg.copy(alpha = 0.7f), modifier = Modifier.weight(1f)); TextButton(onClick = { vm.reload(ch) }) { Text("重试") } }
                        else -> CircularProgressIndicator(color = fg)
                    }
                }
            }
        }
    }
}

// ---- menus ---------------------------------------------------------------------------------------------------------

@Composable
private fun BottomMenu(
    s: ReaderState, vm: ReaderViewModel, listening: Boolean, session: Boolean, rate: Float, onRate: (Float) -> Unit,
    onToc: () -> Unit, onListen: () -> Unit, onSettings: () -> Unit, onCache: (() -> Unit)?, autoPage: Boolean, onAuto: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
        Column(Modifier.navigationBarsPadding().padding(vertical = 8.dp)) {
            val last = s.chapters.lastIndex.coerceAtLeast(1)
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { vm.go(s.position.index - 1, jump = true) }, enabled = s.position.index > 0) { Text("上一章") }
                var dragging by remember { mutableStateOf<Float?>(null) }
                Slider(
                    value = dragging ?: s.position.index.toFloat(),
                    onValueChange = { dragging = it },
                    onValueChangeFinished = { dragging?.let { vm.go(it.roundToInt(), jump = true) }; dragging = null },
                    valueRange = 0f..last.toFloat(), modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { vm.go(s.position.index + 1, jump = true) }, enabled = s.position.index < s.chapters.lastIndex) { Text("下一章") }
            }
            Text(
                "${vm.title(s.chapters.getOrNull(s.position.index)?.title.orEmpty())}（${s.position.index + 1}/${s.chapters.size}）",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 20.dp),
            )
            // The speed is within reach while a read-aloud session exists; the settings sheet has the same control.
            if (session) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("语速", Modifier.padding(end = 16.dp), style = MaterialTheme.typography.labelLarge)
                    FilledTonalIconButton(onClick = { onRate(rate - ReadAloud.RATE_STEP) }, enabled = rate > ReadAloud.MIN_RATE) { Text("慢") }
                    Text("%.2f×".format(rate), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    FilledTonalIconButton(onClick = { onRate(rate + ReadAloud.RATE_STEP) }, enabled = rate < ReadAloud.MAX_RATE) { Text("快") }
                    SleepButton()
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                MenuButton(Icons.AutoMirrored.Outlined.FormatListBulleted, "目录", onToc)
                if (onCache != null) MenuButton(Icons.Outlined.Download, "缓存", onCache)
                if (listening) MenuButton(Icons.Outlined.Pause, "暂停", onListen) else MenuButton(Icons.Outlined.PlayArrow, "听书", onListen)
                MenuButton(if (autoPage) Icons.Outlined.StopCircle else Icons.Outlined.SlowMotionVideo, if (autoPage) "停止自动" else "自动翻页", onAuto)
                MenuButton(Icons.Outlined.Settings, "设置", onSettings)
            }
        }
    }
}

/** 听书定时关闭：15 / 30 / 60 / 90 分钟，或读完本章；按钮上显示还剩多久。 */
@Composable
private fun SleepButton() {
    val ra by ReadAloud.state.collectAsState()
    var open by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(ra.sleepAt) { while (ra.sleepAt > 0) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(15_000) } }
    val label = when {
        ra.sleepAfterChapter -> "本章后停"
        ra.sleepAt > 0 -> "${((ra.sleepAt - now) / 60_000 + 1).coerceAtLeast(1)} 分钟"
        else -> "定时"
    }
    Box {
        TextButton(onClick = { open = true }) { Text(label) }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(15, 30, 60, 90).forEach { m ->
                androidx.compose.material3.DropdownMenuItem(text = { Text("$m 分钟后停止") }, onClick = { open = false; ReadAloud.sleepIn(m) })
            }
            androidx.compose.material3.DropdownMenuItem(text = { Text("读完本章停止") }, onClick = { open = false; ReadAloud.sleepAfterChapter() })
            if (ra.sleepAt > 0 || ra.sleepAfterChapter) androidx.compose.material3.DropdownMenuItem(text = { Text("取消定时") }, onClick = { open = false; ReadAloud.sleepIn(null) })
        }
    }
}

@Composable
private fun MenuButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, label)
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwitchSheet(vm: ReaderViewModel, onDismiss: () -> Unit) {
    // Sources answer one by one; each is listed as soon as it has, instead of after the slowest one.
    // 搜索在面板外面跑：误关了再打开，已经找到的立即显示，没搜完的接着搜
    var search by remember { mutableStateOf(vm.pickerSearch()) }
    val list by remember(search) { search?.list ?: MutableStateFlow(emptyList<SearchBook>()) }.collectAsState()
    val searching by remember(search) { search?.running ?: MutableStateFlow(false) }.collectAsState()
    var query by remember { mutableStateOf("") }
    val pinned by remember(vm) { vm.state.map { it.book?.sourcePinned == true }.distinctUntilChanged() }.collectAsState(false)
    val pinnedSources = rememberPinnedSources()
    val actions = rememberAltSourceActions()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("换源", style = MaterialTheme.typography.titleMedium)
            if (searching && list.isNotEmpty()) {
                CircularProgressIndicator(Modifier.padding(start = 12.dp).size(16.dp), strokeWidth = 2.dp)
                Text("继续搜索，已找到 ${list.size} 个", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!searching) {
                Spacer(Modifier.weight(1f))
                TextButton({ search = vm.pickerSearch(refresh = true) }) { Text("重新搜索") }
            }
        }
        PinSourceRow(pinned, vm::togglePin)
        if (list.isNotEmpty()) SourceNameFilter(query) { query = it }
        val shown = list.arrange(query, pinnedSources, actions.hidden)
        if (list.isEmpty() && searching) Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else if (list.isEmpty()) Text("其他书源里没有找到这本书", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        else if (shown.isEmpty()) Text(
            when {
                query.isBlank() -> if (searching) "还在继续搜索…" else "找到的书源都停用了"
                searching -> "已找到的书源里没有名称包含「${query.trim()}」的，还在继续搜索…"
                else -> "没有名称包含「${query.trim()}」的书源"
            },
            Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else Column(Modifier.verticalScroll(rememberScrollState())) {
            shown.forEach { a ->
                // 按书源绑定：搜索途中列表会重排，长按菜单要跟着它的书源走，不能停在原来的位置上
                androidx.compose.runtime.key(a.origin) {
                    AltSourceItem(a, a.origin in pinnedSources, onClick = { vm.switchTo(a, others = list); onDismiss() }, actions = actions)
                }
            }
        }
    }
}

// ---- 外观、翻页动画 -------------------------------------------------------------------------------------------------------

/** 正文怎么排：文字样式、标题字号、缩进和段间距、左右边距、翻页动画、背景色。 */
data class ReaderLook(val style: TextStyle, val titleSize: androidx.compose.ui.unit.TextUnit, val typeset: Typeset, val margin: androidx.compose.ui.unit.Dp, val anim: PageAnim, val bg: Color)

@Composable
private fun rememberReaderLook(settings: Settings, fg: Color, bg: Color): ReaderLook {
    val ctx = LocalContext.current
    val family = remember(settings.readerFont) { readerFontFamily(ctx, settings.readerFont) }
    return remember(settings.fontSize, settings.lineSpacing, settings.paragraphGap, settings.indent, settings.sideMargin, settings.pageAnim, family, fg, bg) {
        ReaderLook(
            TextStyle(color = fg, fontSize = settings.fontSize.sp, lineHeight = (settings.fontSize * settings.lineSpacing).sp, fontFamily = family, lineHeightStyle = Paginator.LineHeights),
            (settings.fontSize * 1.3f).sp, Typeset(settings.indent, settings.fontSize * settings.paragraphGap), settings.sideMargin.dp, settings.pageAnim, bg,
        )
    }
}

/** 设置里的字体名对应的字体：系统默认、衬线、等宽，或者导入到 filesDir/fonts/ 的字体文件（读不了就用默认）。 */
fun readerFontFamily(ctx: android.content.Context, name: String): androidx.compose.ui.text.font.FontFamily = when (name) {
    "" -> androidx.compose.ui.text.font.FontFamily.Default
    "serif" -> androidx.compose.ui.text.font.FontFamily.Serif
    "mono" -> androidx.compose.ui.text.font.FontFamily.Monospace
    else -> java.io.File(ctx.filesDir, "fonts/$name").takeIf { it.isFile }
        ?.let { f -> runCatching { androidx.compose.ui.text.font.FontFamily(androidx.compose.ui.text.font.Font(f)) }.getOrNull() }
        ?: androidx.compose.ui.text.font.FontFamily.Default
}

/**
 * “覆盖”翻页：右边的页不动，左边的页盖在上面滑开（往回翻时从左边滑进来盖住）。右边那页只画没被盖住的部分，
 * 在被盖住的边上画一道阴影。每页都要有不透明的背景，才盖得住。
 */
private fun Modifier.coverPage(pager: androidx.compose.foundation.pager.PagerState, index: Int, bg: Color): Modifier = this
    .graphicsLayer {
        val o = (pager.currentPage - index) + pager.currentPageOffsetFraction
        translationX = if (o < 0f) o * size.width else 0f
    }
    .drawWithContent {
        val o = (pager.currentPage - index) + pager.currentPageOffsetFraction
        if (o >= 0f) { drawContent(); return@drawWithContent }
        val edge = (-o * size.width).coerceAtMost(size.width)
        clipRect(left = edge) { this@drawWithContent.drawContent() }
        if (edge < size.width) {
            val w = 14.dp.toPx()
            drawRect(
                androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.18f), Color.Transparent), startX = edge, endX = edge + w),
                topLeft = Offset(edge, 0f), size = androidx.compose.ui.geometry.Size(w, size.height),
            )
        }
    }
    .background(bg)

// ---- 书签、缓存、选择文字 ----------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookmarkSheet(vm: ReaderViewModel, onDismiss: () -> Unit, onOpen: () -> Unit) {
    val marks by vm.bookmarks.collectAsState()
    val fmt = remember { java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("书签 · ${marks.size}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.addBookmark() }) { Text("添加当前位置") }
        }
        if (marks.isEmpty()) Text("还没有书签。点“添加当前位置”，把正在看的地方记下来。", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.padding(bottom = 16.dp)) {
            items(marks, key = { it.id }) { m ->
                ListItem(
                    headlineContent = { Text(m.excerpt, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text("${vm.title(m.chapterTitle)} · ${fmt.format(java.util.Date(m.createdAt))}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingContent = { IconButton(onClick = { vm.deleteBookmark(m.id) }) { Icon(Icons.Outlined.Delete, "删除书签") } },
                    modifier = Modifier.clickable { vm.openBookmark(m); onOpen() },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CacheSheet(vm: ReaderViewModel, s: ReaderState, onDismiss: () -> Unit) {
    LaunchedEffect(Unit) { vm.countCached() }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("缓存", style = MaterialTheme.typography.titleMedium)
            Text(
                if (s.cached < 0) "正在统计…" else "已缓存 ${s.cached} / ${s.chapters.size} 章，缓存过的章节没网也能读",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val c = s.caching
            if (c != null) {
                androidx.compose.material3.LinearProgressIndicator(progress = { if (c.second == 0) 0f else c.first.toFloat() / c.second }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("正在缓存 ${c.first} / ${c.second} 章", Modifier.weight(1f))
                    TextButton(onClick = vm::cancelCache) { Text("停止") }
                }
            } else {
                val here = s.position.index
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.FilledTonalButton(onClick = { vm.cache(here, 50) }) { Text("后面 50 章") }
                    androidx.compose.material3.FilledTonalButton(onClick = { vm.cache(here, null) }) { Text("后面全部") }
                    androidx.compose.material3.FilledTonalButton(onClick = { vm.cache(0, null) }) { Text("整本") }
                }
                TextButton(onClick = vm::clearCache, enabled = s.cached > 0) { Text("删除本书缓存") }
            }
        }
    }
}

/** 长按拿出来的文字：可以选一段复制，也可以整段复制或分享。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectSheet(text: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("长按文字可选择一部分", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            TextButton(onClick = { clipboard.setText(AnnotatedString(text)); android.widget.Toast.makeText(ctx, "已复制", android.widget.Toast.LENGTH_SHORT).show(); onDismiss() }) { Text("全部复制") }
            TextButton(onClick = {
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, text)
                ctx.startActivity(android.content.Intent.createChooser(send, "分享"))
            }) { Text("分享") }
        }
        androidx.compose.foundation.text.selection.SelectionContainer {
            Text(text, Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodyLarge)
        }
    }
}
