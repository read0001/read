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
import app.openread.engine.SearchBook
import app.openread.ui.components.SwitchOfferDialog
import app.openread.ui.components.factory
import app.openread.ui.detail.TocSheet
import kotlinx.coroutines.flow.distinctUntilChanged
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
    val palette = settings.palette
    val bg = Color(palette.bg)
    val fg = Color(palette.fg)
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

    Box(Modifier.fillMaxSize().background(bg)) {
        when {
            !s.loaded -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = fg)
            s.error != null -> Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(s.error ?: "", color = fg)
                TextButton(onClick = onBack) { Text("返回") }
            }
            else -> {
                if (settings.pageMode == PageMode.Slide) SlideReader(vm, s, settings, fg, ra, onToggleMenu = { menu = !menu })
                else ScrollReader(vm, s, settings, fg, ra, onToggleMenu = { menu = !menu })
            }
        }

        AnimatedVisibility(menu, Modifier.align(Alignment.TopCenter), enter = fadeIn() + slideInVertically { -it }, exit = fadeOut() + slideOutVertically { -it }) {
            TopAppBar(
                title = { Text(s.book?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } },
                actions = {
                    IconButton(onClick = { vm.reload(s.position.index) }) { Icon(Icons.Outlined.Refresh, "重新加载本章") }
                    IconButton(onClick = { showSwitch = true }) { Icon(Icons.Outlined.SwapHoriz, "换源") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
        }
        AnimatedVisibility(menu, Modifier.align(Alignment.BottomCenter), enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it }) {
            BottomMenu(
                s, vm, listening = ra.playing, session = listening, rate = settings.readAloudRate,
                onRate = { r -> vm.updateSettings { it.copy(readAloudRate = (r.coerceIn(ReadAloud.MIN_RATE, ReadAloud.MAX_RATE) / ReadAloud.RATE_STEP).roundToInt() * ReadAloud.RATE_STEP) } },
                onToc = { showToc = true }, onListen = { vm.readAloud(ctx) }, onSettings = { showSettings = true },
            )
        }
        androidx.compose.material3.SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 72.dp))
        if (s.busy) CircularProgressIndicator(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(16.dp).size(24.dp), color = fg)
    }

    if (showToc) TocSheet(s.chapters.map { app.openread.engine.Chapter(it.idx, it.title, it.url, it.isVolume) }, current = s.position.index, onDismiss = { showToc = false }) { idx ->
        showToc = false; menu = false; vm.go(idx, 0)
    }
    if (showSettings) SettingsSheet(settings, vm::updateSettings) { showSettings = false }
    if (showSwitch) SwitchSheet(vm) { showSwitch = false }
    s.offer?.let { SwitchOfferDialog(it, onSwitch = vm::acceptOffer, onDismiss = vm::dismissOffer) }
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
private fun SlideReader(vm: ReaderViewModel, s: ReaderState, settings: Settings, fg: Color, listen: ReadAloud.State, onToggleMenu: () -> Unit) {
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
        val sidePad = 22.dp
        val topPad = 18.dp
        val footerH = 28.dp
        val widthPx = with(density) { (maxWidth - sidePad * 2).roundToPx() }
        val heightPx = with(density) { (maxHeight - topPad - footerH).roundToPx() }
        val fontSp = settings.fontSize.sp
        val style = remember(settings.fontSize, settings.lineSpacing, fg) {
            TextStyle(color = fg, fontSize = fontSp, lineHeight = (settings.fontSize * settings.lineSpacing).sp)
        }
        val cache = remember(widthPx, heightPx, settings.fontSize, settings.lineSpacing) { HashMap<Pair<Int, Int>, ChapterPages>() }

        fun slotsFor(idx: Int): List<Slot> {
            val ch = s.chapters.getOrNull(idx) ?: return emptyList()
            return when (val t = vm.texts[idx]) {
                is ChapterText.Ready -> {
                    val pages = cache.getOrPut(idx to t.text.hashCode()) {
                        val paragraphs = Paginator.paragraphs(ch.title, t.text)
                        Paginator.paginate(measurer, Paginator.display(ch.title, t.text, (settings.fontSize * 1.3f).sp), style, widthPx, heightPx, ch.title)
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
        key(cur, curReady, widthPx, heightPx, settings.fontSize, settings.lineSpacing, prevEdge != null, nextEdge != null) {
            val start = remember {
                val pages = (curSlots.firstOrNull() as? Slot.Content)?.pages
                lead + (pages?.pageAt(s.position.offset) ?: 0)
            }
            val pager = rememberPagerState(initialPage = start.coerceIn(0, (slots.size - 1).coerceAtLeast(0))) { slots.size }

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
                    detectTapGestures { pos ->
                        val w = size.width
                        when {
                            pos.x < w * 0.3f -> scope.launch { if (pager.currentPage > 0) pager.animateScrollToPage(pager.currentPage - 1) }
                            pos.x > w * 0.7f -> scope.launch { if (pager.currentPage < pager.pageCount - 1) pager.animateScrollToPage(pager.currentPage + 1) }
                            else -> onToggleMenu()
                        }
                    }
                },
            ) {
                HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { i ->
                    when (val slot = slots[i]) {
                        is Slot.Content -> {
                            val span = listen.span?.takeIf { listening && listen.chapterIndex == slot.chapter && it.to > it.from }
                            val spoken = span?.let { slot.pages.offsetOf(it.paragraph, it.from) until slot.pages.offsetOf(it.paragraph, it.to) }
                            PageView(slot, style, fg, sidePad, topPad, footerH, spoken, highlight)
                        }
                        is Slot.Status -> StatusView(slot, s, vm, fg)
                    }
                }
            }
        }
    }
}

@Composable
private fun PageView(
    slot: Slot.Content, style: TextStyle, fg: Color, sidePad: androidx.compose.ui.unit.Dp, topPad: androidx.compose.ui.unit.Dp, footerH: androidx.compose.ui.unit.Dp,
    spoken: IntRange?, highlight: Color,
) {
    val page = slot.pages.pages[slot.page]
    val text = remember(page, spoken, highlight) { highlighted(page, spoken, highlight) }
    Column(Modifier.fillMaxSize().padding(horizontal = sidePad)) {
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
private fun ScrollReader(vm: ReaderViewModel, s: ReaderState, settings: Settings, fg: Color, listen: ReadAloud.State, onToggleMenu: () -> Unit) {
    // The list is anchored at the chapter the reader entered with; it only grows downwards. Re-anchoring happens when
    // the position jumps outside it (table of contents, slider, "previous chapter").
    var first by remember { mutableIntStateOf(s.position.index) }
    var last by remember { mutableIntStateOf(s.position.index) }
    val list = rememberLazyListState()
    val style = TextStyle(color = fg, fontSize = settings.fontSize.sp, lineHeight = (settings.fontSize * settings.lineSpacing).sp)
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
    fun paragraphsOf(ch: Int): Pair<List<String>, IntArray>? {
        val text = (vm.texts[ch] as? ChapterText.Ready)?.text ?: return null
        return paragraphCache.getOrPut(ch to text.hashCode()) {
            val paragraphs = Paginator.paragraphs(s.chapters.getOrNull(ch)?.title.orEmpty(), text)
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

    // The list opens at the top of the chapter, whatever the paging reader had on screen: that is where listening starts.
    LaunchedEffect(Unit) { vmRef.value.noteVisible(s.position.index, 0, 0) }

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

    // Report progress only once the reader has actually scrolled, so entering scroll mode never overwrites a saved offset.
    LaunchedEffect(Unit) {
        snapshotFlow { list.isScrollInProgress }.first { it }
        snapshotFlow { list.firstVisibleItemIndex }.distinctUntilChanged().collect { i ->
            vmRef.value.record((first + i - 1).coerceIn(first, last), 0)
        }
    }

    // When a scroll comes to rest, the first text on screen is where reading aloud goes on from, unless the sentence being
    // read is already in view.
    LaunchedEffect(Unit) {
        var scrolling = false
        snapshotFlow { list.isScrollInProgress }.collect { now ->
            if (scrolling && !now) {
                val top = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index >= 1 && it.offset + it.size > 0 }
                val ch = top?.let { first + it.index - 1 }
                val (paragraphs, starts) = ch?.let { paragraphsOf(it) } ?: (null to null)
                val layout = ch?.let { layouts[it] }
                val bodyTop = ch?.let { bodyTops[it] }
                if (top != null && ch != null && paragraphs != null && starts != null && layout != null && bodyTop != null) {
                    val y = -top.offset - bodyTop
                    val (paragraph, char) = if (y <= 0f) 0 to 0
                    else Paginator.locate(paragraphs, starts, layout.getOffsetForPosition(Offset(0f, y)) + paragraphs[0].length + 2)
                    vmRef.value.noteVisible(ch, paragraph, char)
                    val ra = listenNow.value
                    if (alignedNow.value && ra.active && ra.bookUrl == bookUrl.value && !spokenVisible(ra)) ReadAloud.seek(ch, paragraph, char)
                }
            }
            scrolling = now
        }
    }

    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onToggleMenu() } }) {
        LazyColumn(Modifier.fillMaxSize().windowInsetsPadding(StableInsets), state = list, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
            item(key = "top") {
                if (first > 0) TextButton(onClick = { first -= 1; vm.go(first, 0) }) { Text("上一章：${s.chapters[first - 1].title}", color = fg.copy(alpha = 0.7f)) }
            }
            items((first..last).toList(), key = { it }) { ch ->
                val title = s.chapters.getOrNull(ch)?.title.orEmpty()
                val span = listen.span?.takeIf { listening && listen.chapterIndex == ch && it.to > it.from }
                val titleStyle = style.copy(fontSize = (settings.fontSize * 1.3f).sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
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
                            val body = remember(t.text) { Paginator.body(t.text) }
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
    onToc: () -> Unit, onListen: () -> Unit, onSettings: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
        Column(Modifier.navigationBarsPadding().padding(vertical = 8.dp)) {
            val last = s.chapters.lastIndex.coerceAtLeast(1)
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { vm.go(s.position.index - 1) }, enabled = s.position.index > 0) { Text("上一章") }
                var dragging by remember { mutableStateOf<Float?>(null) }
                Slider(
                    value = dragging ?: s.position.index.toFloat(),
                    onValueChange = { dragging = it },
                    onValueChangeFinished = { dragging?.let { vm.go(it.roundToInt()) }; dragging = null },
                    valueRange = 0f..last.toFloat(), modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { vm.go(s.position.index + 1) }, enabled = s.position.index < s.chapters.lastIndex) { Text("下一章") }
            }
            Text(
                "${s.chapters.getOrNull(s.position.index)?.title.orEmpty()}（${s.position.index + 1}/${s.chapters.size}）",
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
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                MenuButton(Icons.AutoMirrored.Outlined.FormatListBulleted, "目录", onToc)
                MenuButton(Icons.Outlined.Download, "缓存", { vm.cacheAhead() })
                if (listening) MenuButton(Icons.Outlined.Pause, "暂停", onListen) else MenuButton(Icons.Outlined.PlayArrow, "听书", onListen)
                MenuButton(Icons.Outlined.Settings, "设置", onSettings)
            }
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
    var list by remember { mutableStateOf<List<SearchBook>>(emptyList()) }
    var searching by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        runCatching { vm.alternatives().collect { list = it } }
        searching = false
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("换源", style = MaterialTheme.typography.titleMedium)
            if (searching && list.isNotEmpty()) {
                CircularProgressIndicator(Modifier.padding(start = 12.dp).size(16.dp), strokeWidth = 2.dp)
                Text("继续搜索，已找到 ${list.size} 个", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (list.isEmpty() && searching) Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else if (list.isEmpty()) Text("其他书源里没有找到这本书", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        else Column(Modifier.verticalScroll(rememberScrollState())) {
            list.forEach { a ->
                ListItem(
                    headlineContent = { Text(a.originName) },
                    supportingContent = { Text(a.latestChapterTitle?.let { "最新：$it" } ?: a.bookUrl, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.pointerInput(a) { detectTapGestures { vm.switchTo(a, others = list); onDismiss() } },
                )
            }
        }
    }
}
