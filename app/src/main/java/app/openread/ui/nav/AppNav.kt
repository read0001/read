package app.openread.ui.nav

import android.app.Activity
import android.net.Uri
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.openread.ui.detail.DetailScreen
import app.openread.ui.explore.ExploreScreen
import app.openread.ui.reader.ReaderScreen
import app.openread.ui.search.SearchScreen
import app.openread.ui.settings.SettingsScreen
import app.openread.ui.shelf.ShelfScreen
import app.openread.ui.sources.SourcesScreen

private object Route {
    const val MAIN = "main"
    const val SEARCH = "search?q={q}"
    const val DETAIL = "detail/{url}"
    const val READER = "reader/{url}?index={index}"
    fun detail(url: String) = "detail/${Uri.encode(url)}"
    fun reader(url: String, index: Int? = null) = "reader/${Uri.encode(url)}" + (index?.let { "?index=$it" } ?: "")
    fun search(q: String? = null) = "search" + (q?.let { "?q=${Uri.encode(it)}" } ?: "")
}

@Composable
fun AppNav(pendingSearch: String? = null, onSearchHandled: () -> Unit = {}) {
    val nav = rememberNavController()
    androidx.compose.runtime.LaunchedEffect(pendingSearch) {
        if (!pendingSearch.isNullOrBlank()) { nav.navigate(Route.search(pendingSearch)); onSearchHandled() }
    }
    NavHost(nav, startDestination = Route.MAIN) {
        composable(Route.MAIN) {
            MainTabs(
                onOpen = { nav.navigate(Route.reader(it.bookUrl)) },
                onDetail = { nav.navigate(Route.detail(it.bookUrl)) },
                onSearch = { nav.navigate(Route.search()) },
                onOpenHit = { Handoff.search = it; nav.navigate(Route.detail(it.bookUrl)) },
            )
        }
        composable(Route.SEARCH, arguments = listOf(navArgument("q") { type = NavType.StringType; nullable = true; defaultValue = null })) { e ->
            SearchScreen(e.arguments?.getString("q"), onBack = { nav.popBackStack() }, onOpen = { Handoff.search = it; nav.navigate(Route.detail(it.bookUrl)) })
        }
        composable(Route.DETAIL, arguments = listOf(navArgument("url") { type = NavType.StringType })) { e ->
            val url = e.arguments?.getString("url").orEmpty()
            DetailScreen(url, onBack = { nav.popBackStack() }, onRead = { u, idx -> nav.navigate(Route.reader(u, idx)) })
        }
        composable(
            Route.READER,
            arguments = listOf(
                navArgument("url") { type = NavType.StringType },
                navArgument("index") { type = NavType.IntType; defaultValue = -1 },
            ),
        ) { e ->
            val url = e.arguments?.getString("url").orEmpty()
            val index = e.arguments?.getInt("index")?.takeIf { it >= 0 }
            ReaderScreen(url, index, onBack = { nav.popBackStack() })
        }
    }
}

private data class Tab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val tabs = listOf(
    Tab("书架", Icons.Outlined.AutoStories),
    Tab("发现", Icons.Outlined.Explore),
    Tab("书源", Icons.Outlined.Storage),
    Tab("设置", Icons.Outlined.Settings),
)

@Composable
private fun MainTabs(
    onOpen: (app.openread.data.BookRow) -> Unit,
    onDetail: (app.openread.data.BookRow) -> Unit,
    onSearch: () -> Unit,
    onOpenHit: (app.openread.engine.SearchBook) -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val context = LocalContext.current
    var lastBack by remember { mutableLongStateOf(0L) }
    // Back on another tab returns to the shelf; on the shelf it takes two presses within 2 seconds to leave.
    BackHandler {
        if (tab != 0) tab = 0
        else {
            val now = SystemClock.elapsedRealtime()
            if (now - lastBack < 2000) (context as? Activity)?.finish()
            else { lastBack = now; Toast.makeText(context, "再按一次退出", Toast.LENGTH_SHORT).show() }
        }
    }
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(t.icon, t.label) }, label = { Text(t.label) })
                }
            }
        },
    ) { pad ->
        androidx.compose.foundation.layout.Box(Modifier.padding(pad)) {
            when (tab) {
                0 -> ShelfScreen(onOpen = onOpen, onDetail = onDetail, onSearch = onSearch, onExplore = { tab = 1 })
                1 -> ExploreScreen(onOpen = onOpenHit)
                2 -> SourcesScreen()
                else -> SettingsScreen()
            }
        }
    }
}
