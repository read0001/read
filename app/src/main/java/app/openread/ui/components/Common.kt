package app.openread.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent

/** A book cover with a title-on-colour fallback, since many sources have no cover or a dead link. */
@Composable
fun BookCover(url: String?, title: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(8.dp)
    val fallback: @Composable () -> Unit = {
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.secondaryContainer).padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer,
                textAlign = TextAlign.Center, maxLines = 4, overflow = TextOverflow.Ellipsis,
            )
        }
    }
    Box(modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (url.isNullOrBlank()) fallback()
        else SubcomposeAsyncImage(
            model = url, contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            loading = { fallback() }, error = { fallback() },
            success = { SubcomposeAsyncImageContent() },
        )
    }
}

@Composable
fun EmptyState(title: String, hint: String? = null, modifier: Modifier = Modifier, action: @Composable (() -> Unit)? = null) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.MenuBook, null, Modifier.padding(bottom = 16.dp), tint = MaterialTheme.colorScheme.outline)
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (hint != null) Text(
            hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp),
        )
        if (action != null) Box(Modifier.padding(top = 16.dp)) { action() }
    }
}

/** Shared by the screens: a Compose-friendly ViewModel factory for the hand-wired container. */
@Suppress("UNCHECKED_CAST")
inline fun <reified T : androidx.lifecycle.ViewModel> factory(crossinline create: () -> T) =
    object : androidx.lifecycle.ViewModelProvider.Factory {
        override fun <V : androidx.lifecycle.ViewModel> create(modelClass: Class<V>): V = create() as V
    }

val Unread = Color(0xFFE5484D)

/** A compact list row for one search or explore hit. */
@Composable
fun BookRowItem(b: app.openread.engine.SearchBook, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        BookCover(b.coverUrl, b.name, Modifier.width(64.dp).height(86.dp))
        Column(Modifier.padding(start = 14.dp).weight(1f)) {
            Text(b.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(b.author.takeIf { it.isNotBlank() }, b.kind).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            (b.intro ?: b.latestChapterTitle?.let { "最新：$it" })?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}
