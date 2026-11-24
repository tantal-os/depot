package app.tantal.depot

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal enum class DepotTab(@param:StringRes val title: Int, @param:DrawableRes val glyph: Int) {
    Apps(R.string.catalog, R.drawable.ic_apps),
    Models(R.string.models, R.drawable.ic_model),
    Updates(R.string.updates, R.drawable.ic_updates),
    Catalogs(R.string.sources, R.drawable.ic_catalogs),
}

@Composable
internal fun Glyph(@DrawableRes icon: Int, label: String? = null, modifier: Modifier = Modifier) {
    Icon(painterResource(icon), label, modifier.size(24.dp))
}

@Composable
internal fun DepotHeader(
    title: String, detail: Boolean, busy: Boolean, query: String, searching: Boolean,
    canSearch: Boolean, canRefresh: Boolean,
    onQuery: (String) -> Unit, onSearch: () -> Unit, onBack: () -> Unit, onRefresh: () -> Unit,
) {
    Column(Modifier.statusBarsPadding().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
        Row(
            Modifier.fillMaxWidth().padding(start = if (detail) 12.dp else 24.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (detail) {
                FilledTonalIconButton(onBack) { Glyph(R.drawable.ic_back, stringResource(R.string.back)) }
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else if (searching) {
                SearchField(query, onQuery, onSearch, Modifier.weight(1f))
            } else {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.displayMedium.copy(
                    fontWeight = FontWeight.Bold, letterSpacing = (-2).sp,
                ), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (canSearch) FilledTonalIconButton(onSearch) {
                    Glyph(R.drawable.ic_search, stringResource(R.string.search))
                }
                if (canRefresh) FilledTonalIconButton(onRefresh, enabled = !busy) {
                    Glyph(R.drawable.ic_refresh, stringResource(R.string.refresh))
                }
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

@Composable
private fun SearchField(query: String, change: (String) -> Unit, close: () -> Unit, modifier: Modifier) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val colors = MaterialTheme.colorScheme
    val focusManager = LocalFocusManager.current
    val placeholder = stringResource(R.string.search)
    Surface(modifier, shape = CircleShape, color = colors.surfaceContainerHigh) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Glyph(R.drawable.ic_search)
            BasicTextField(
                query, change,
                Modifier.weight(1f).padding(start = 12.dp).focusRequester(focus)
                    .semantics { contentDescription = placeholder },
                singleLine = true,
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                decorationBox = { field ->
                    Box {
                        if (query.isEmpty()) Text(placeholder, color = colors.onSurfaceVariant)
                        field()
                    }
                },
            )
            IconButton(close) { Glyph(R.drawable.ic_close, stringResource(R.string.close_search)) }
        }
    }
}

@Composable
internal fun DepotNavigation(selected: DepotTab, updates: Int, navigate: (DepotTab) -> Unit) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        windowInsets = NavigationBarDefaults.windowInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
    ) {
        DepotTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == selected,
                onClick = { navigate(tab) },
                icon = {
                    if (tab == DepotTab.Updates && updates > 0) {
                        BadgedBox(badge = { Badge { Text(updates.toString()) } }) { Glyph(tab.glyph) }
                    } else Glyph(tab.glyph)
                },
                label = { Text(stringResource(tab.title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

@Composable
internal fun DepotList(content: LazyListScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = 680.dp).fillMaxWidth().imePadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content,
        )
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(text, Modifier.padding(start = 8.dp, top = 8.dp, bottom = 2.dp),
        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
}

@Composable
internal fun PackageTile(@DrawableRes icon: Int, modifier: Modifier = Modifier, accent: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier.size(if (accent) 60.dp else 48.dp),
        shape = RoundedCornerShape(if (accent) 20.dp else 16.dp),
        color = if (accent) colors.primary else colors.secondaryContainer,
        contentColor = if (accent) colors.onPrimary else colors.onSecondaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) { Glyph(icon) }
    }
}

@Composable
internal fun InventoryRow(
    icon: Int, title: String, subtitle: String, shape: RoundedCornerShape, onClick: () -> Unit,
    update: Boolean = false, warning: String? = null,
) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PackageTile(icon)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                SupportingText(subtitle)
                if (warning != null) Text(warning, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
            if (update) Glyph(R.drawable.ic_updates, stringResource(R.string.update_available))
            else Glyph(R.drawable.ic_chevron)
        }
    }
}

internal fun rowShape(index: Int, last: Int): RoundedCornerShape = RoundedCornerShape(
    topStart = if (index == 0) 24.dp else 6.dp, topEnd = if (index == 0) 24.dp else 6.dp,
    bottomStart = if (index == last) 24.dp else 6.dp, bottomEnd = if (index == last) 24.dp else 6.dp,
)

@Composable
internal fun SupportingText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
internal fun EmptyPanel(icon: Int, title: String, description: String? = null) {
    Panel {
        PackageTile(icon)
        Text(title, style = MaterialTheme.typography.titleLarge)
        description?.let { SupportingText(it) }
    }
}

@Composable
internal fun CatalogWelcome(enabled: Boolean, add: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth(), shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PackageStack(Modifier.fillMaxWidth().height(156.dp))
            Text(stringResource(R.string.no_catalogs), style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.catalog_welcome), style = MaterialTheme.typography.bodyLarge)
            Button(add, enabled = enabled, modifier = Modifier.padding(top = 4.dp)) {
                Glyph(R.drawable.ic_add)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.add_source))
            }
        }
    }
}

@Composable
private fun PackageStack(modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Canvas(modifier) {
        val factor = minOf(size.width / 300f, size.height / 156f)
        val start = Offset((size.width - 300 * factor) / 2, (size.height - 156 * factor) / 2)
        scale(factor, factor, pivot = start) {
            fun plane(vararg vertices: Pair<Int, Int>): Path = polygon(start, vertices)
            drawPath(plane(18 to 104, 149 to 38, 284 to 103, 152 to 151), colors.primary.copy(alpha = .14f))
            drawPath(plane(37 to 95, 149 to 39, 263 to 95, 150 to 149), colors.onPrimaryContainer.copy(alpha = .10f))
            drawPath(plane(61 to 76, 150 to 33, 239 to 76, 150 to 119), colors.primary)
            drawPath(plane(61 to 76, 150 to 119, 150 to 141, 61 to 98), colors.primary.copy(alpha = .75f))
            drawPath(plane(150 to 119, 239 to 76, 239 to 98, 150 to 141), colors.onPrimaryContainer)
            drawPath(plane(92 to 43, 150 to 15, 208 to 43, 150 to 71), colors.tertiaryContainer)
            drawPath(plane(92 to 43, 150 to 71, 150 to 107, 92 to 79), colors.tertiary)
            drawPath(plane(150 to 71, 208 to 43, 208 to 79, 150 to 107), colors.onTertiaryContainer)
            drawPath(plane(128 to 25, 138 to 20, 196 to 49, 186 to 54), colors.onTertiaryContainer.copy(alpha = .60f))
            drawPath(plane(103 to 61, 124 to 71, 124 to 81, 103 to 71), colors.tertiaryContainer)
        }
    }
}

private fun DrawScope.polygon(start: Offset, vertices: Array<out Pair<Int, Int>>) = Path().apply {
    vertices.forEachIndexed { index, (x, y) ->
        if (index == 0) moveTo(start.x + x, start.y + y) else lineTo(start.x + x, start.y + y)
    }
    close()
}

@Composable
internal fun DetailHeader(icon: Int, title: String, subtitle: String) {
    Column(Modifier.padding(horizontal = 8.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PackageTile(icon, accent = true)
        Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        SupportingText(subtitle)
    }
}

@Composable
internal fun VerificationDetails(content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column {
        TextButton(onClick = { expanded = !expanded }) {
            Glyph(if (expanded) R.drawable.ic_up else R.drawable.ic_down)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.verification))
        }
        if (expanded) SelectionContainer {
            Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
        }
    }
}

@Composable
internal fun TechnicalValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun PendingInstall(canConfirm: Boolean, busy: Boolean, confirm: () -> Unit, cancel: () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.pending_install), style = MaterialTheme.typography.titleMedium)
            if (canConfirm) Button(confirm, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.confirm_install))
            }
            TextButton(cancel, enabled = !busy) { Text(stringResource(R.string.cancel_install)) }
        }
    }
}
