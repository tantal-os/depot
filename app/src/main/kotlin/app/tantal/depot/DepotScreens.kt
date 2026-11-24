package app.tantal.depot

import android.text.format.Formatter
import android.util.Log
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
internal fun Inventory(
    state: DepotState, tab: DepotTab, cards: List<AppCard>, query: String, loaded: Boolean, busy: Boolean,
    pending: @Composable () -> Unit, add: () -> Unit, navigate: (String) -> Unit,
) {
    var installedOnly by rememberSaveable { mutableStateOf(false) }
    val updates = tab == DepotTab.Updates
    val apps = cards.filter { card ->
        val installed = state.installed[card.id.packageName]
        val visible = when {
            updates -> installed != null && state.appOffer(card.offers, card.id) != null
            installedOnly -> installed?.signer == card.id.signer
            else -> true
        }
        visible && (card.title.contains(query, true) || card.id.packageName.contains(query, true))
    }.sortedBy(AppCard::title)
    val models = state.modelIds().groupBy(ModelId::family).entries
        .filter { (family, _) -> state.modelTitle(family).contains(query, true) || family.contains(query, true) }
        .sortedBy { (family, _) -> state.modelTitle(family) }
    DepotList {
        item { pending() }
        when {
            !loaded -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.sources.isEmpty() && (tab != DepotTab.Models || models.isEmpty()) ->
                item { CatalogWelcome(enabled = !busy, add) }
            tab == DepotTab.Models -> {
                item { SectionTitle(stringResource(R.string.models)) }
                if (models.isEmpty()) item {
                    EmptyPanel(R.drawable.ic_model, stringResource(
                        if (query.isEmpty()) R.string.no_models else R.string.no_matches,
                    ))
                }
                itemsIndexed(models, key = { _, entry -> entry.key }) { index, (family, variants) ->
                    InventoryRow(
                        R.drawable.ic_model, state.modelTitle(family),
                        pluralStringResource(R.plurals.model_variants, variants.size, variants.size),
                        rowShape(index, models.lastIndex), { navigate("model/$family") },
                    )
                }
            }
            else -> {
                item {
                    if (updates) {
                        SectionTitle(stringResource(R.string.updates))
                    } else {
                        SectionTitle(stringResource(R.string.catalog))
                        if (cards.isNotEmpty()) Row(
                            Modifier.padding(start = 8.dp, top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(!installedOnly, onClick = { installedOnly = false },
                                label = { Text(stringResource(R.string.all_apps)) })
                            FilterChip(installedOnly, onClick = { installedOnly = true },
                                label = { Text(stringResource(R.string.installed)) })
                        }
                    }
                }
                if (apps.isEmpty()) item {
                    val title = when {
                        query.isNotEmpty() -> R.string.no_matches
                        state.indexes.isEmpty() -> R.string.catalog_unavailable
                        updates -> R.string.up_to_date
                        installedOnly -> R.string.no_installed_apps
                        else -> R.string.no_results
                    }
                    EmptyPanel(if (updates) R.drawable.ic_updates else R.drawable.ic_package, stringResource(title))
                }
                itemsIndexed(apps, key = { _, card -> card.id.key }) { index, card ->
                    val current = state.installed[card.id.packageName]
                    val offer = state.appOffer(card.offers, card.id)
                    val update = current != null && offer != null
                    val version = when {
                        update -> offer.build.version
                        current?.signer == card.id.signer -> current.version
                        else -> offer?.build?.version
                    }
                    val sources = card.sourceLabels()
                    val warning = when {
                        current != null && current.signer != card.id.signer -> stringResource(R.string.different_signer)
                        else -> state.appReasons[card.id.packageName].orEmpty().firstOrNull()
                    }
                    InventoryRow(appGlyph(card.id.packageName), card.title, listOfNotNull(sources, version).joinToString(" · "),
                        rowShape(index, apps.lastIndex), { navigate("app/${card.id.key}") }, update, warning)
                }
            }
        }
    }
}

@Composable
internal fun AppPage(
    state: DepotState, card: AppCard, canInstall: Boolean, canChangeSource: Boolean,
    pending: @Composable () -> Unit,
    pin: (String, String?) -> Unit, install: (Offer<ApkBuild>) -> Unit, open: (ApkId) -> Unit,
) {
    val context = LocalContext.current
    val current = state.installed[card.id.packageName]
    val installed = current?.takeIf { it.signer == card.id.signer }
    val latest = state.appOffer(card.offers, card.id)
    val label = stringResource(if (installed != null) R.string.update else R.string.install)
    val builds = card.builds.sortedByDescending { it.first().build.code }
    val warnings = when {
        installed != null -> state.appReasons[card.id.packageName].orEmpty()
        current != null -> listOf(stringResource(R.string.different_signer))
        else -> emptyList()
    }
    DepotList {
        item { pending() }
        item {
            DetailHeader(appGlyph(card.id.packageName), card.title, card.sourceLabels())
            if (latest != null || installed != null) FlowRow(
                Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (latest != null) Button({ install(latest) }, enabled = canInstall) {
                    Glyph(R.drawable.ic_download)
                    Spacer(Modifier.width(8.dp))
                    Text(label)
                }
                if (installed != null) FilledTonalButton({ open(card.id) }) { Text(stringResource(R.string.open)) }
            }
            if (latest != null) SupportingText(
                stringResource(R.string.build_summary, latest.build.version, Formatter.formatFileSize(context, latest.build.size)),
                Modifier.padding(start = 8.dp, top = 8.dp),
            )
        }
        if (warnings.isNotEmpty()) item {
            Panel { warnings.forEach { Text(it, color = MaterialTheme.colorScheme.error) } }
        }
        item {
            Panel {
                installed?.let { SupportingText(stringResource(R.string.installed_version, it.version)) }
                PickSource(state, card.id.key, canChangeSource, pin)
                VerificationDetails {
                    TechnicalValue(stringResource(R.string.package_id), card.id.packageName)
                    TechnicalValue(stringResource(R.string.certificate), card.id.signer)
                }
            }
        }
        item { SectionTitle(stringResource(R.string.builds)) }
        itemsIndexed(builds, key = { _, offers -> offers.first().build.sha }) { _, offers ->
            val build = offers.first().build
            val offer = state.appOffer(offers, card.id)
            Panel {
                Text(stringResource(R.string.build_version, build.version, build.code), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                SupportingText(Formatter.formatFileSize(context, build.size))
                for ((source, row) in offers.distinctBy { it.source.id }) {
                    val reason = state.reasons.apkReason(source.id, row)
                    if (reason == null) SupportingText(source.label)
                    else Text("${source.label} · $reason", color = MaterialTheme.colorScheme.error)
                }
                if (offer != null && latest?.build?.sha != build.sha) {
                    FilledTonalButton({ install(offer) }, enabled = canInstall) { Text(label) }
                }
                VerificationDetails { TechnicalValue(stringResource(R.string.digest), build.sha) }
            }
        }
    }
}

@Composable
internal fun ModelPage(
    state: DepotState, family: String, enabled: Boolean, activeTransfer: ModelId?, pending: @Composable () -> Unit,
    pin: (String, String?) -> Unit, install: (Offer<ModelBuild>) -> Unit,
    remove: (StoredModel) -> Unit, keep: (StoredModel, Boolean) -> Unit,
) {
    val context = LocalContext.current
    val key = modelSourceKey(family)
    val selected = state.pins[key]
    val ids = state.modelIds().filter { it.family == family }.sortedBy(ModelId::variant)
    DepotList {
        item { pending() }
        item {
            DetailHeader(R.drawable.ic_model, state.modelTitle(family),
                pluralStringResource(R.plurals.model_variants, ids.size, ids.size))
        }
        item { Panel { PickSource(state, key, enabled, pin) } }
        item { SectionTitle(stringResource(R.string.model_packs)) }
        itemsIndexed(ids, key = { _, id -> id.key }) { _, id ->
            val installed = state.stored.filter { it.build.id == id }.sortedByDescending { it.build.version }
            val offer = modelOffer(id, state.indexes, selected, state.reasons, state.pins[modelPublisherKey(family)])
            val title = offer?.build?.label ?: installed.firstOrNull()?.build?.label ?: id.variant
            val owners = state.owners[id].orEmpty()
            Panel {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                if (owners.isNotEmpty()) SupportingText(stringResource(R.string.required_by, owners.joinToString()))
                if (activeTransfer == id) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    SupportingText(stringResource(R.string.downloading))
                }
                if (offer == null) {
                    SupportingText(stringResource(if (selected == null) R.string.model_unavailable else R.string.pinned_unavailable))
                } else {
                    SupportingText("${offer.source.label} · ${Formatter.formatFileSize(context, offer.build.size)}")
                    if (installed.none { it.build.sha == offer.build.sha && it.source == offer.source.id }) {
                        if (owners.isNotEmpty() && activeTransfer != id && !state.canTransfer) {
                            SupportingText(stringResource(R.string.waiting_network))
                        }
                        FilledTonalButton({ install(offer) }, enabled = enabled && activeTransfer == null) {
                            Glyph(R.drawable.ic_download)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(if (installed.isEmpty()) R.string.download else R.string.update))
                        }
                    }
                }
                for (model in installed) {
                    HorizontalDivider()
                    Text(stringResource(R.string.installed_version, model.build.version.toString()),
                        style = MaterialTheme.typography.titleMedium)
                    state.reasons.modelReason(model.source, model.build)?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                    val kept = model.id in state.kept
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton({ keep(model, !kept) }, enabled = enabled) {
                            Text(stringResource(if (kept) R.string.unkeep else R.string.keep))
                        }
                        TextButton({ remove(model) }, enabled = enabled && owners.isEmpty()) {
                            Text(stringResource(R.string.remove))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickSource(state: DepotState, key: String, enabled: Boolean, pin: (String, String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = state.pins[key]
    val label = state.sources.firstOrNull { it.id == selected }?.label
        ?: stringResource(if (selected == null) R.string.source_auto else R.string.model_unavailable)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.package_source), style = MaterialTheme.typography.labelLarge)
        Box {
            FilledTonalButton(onClick = { expanded = true }, enabled = enabled && state.sources.isNotEmpty()) {
                Text(label)
                Spacer(Modifier.width(6.dp))
                Glyph(R.drawable.ic_down)
            }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.source_auto)) },
                    onClick = { expanded = false; pin(key, null) })
                state.sources.forEach { source ->
                    DropdownMenuItem(text = { Text(source.label) }, onClick = { expanded = false; pin(key, source.id) })
                }
            }
        }
    }
}

@Composable
internal fun SourcePage(
    state: DepotState, official: Boolean, busy: Boolean, pending: @Composable () -> Unit,
    add: () -> Unit, change: (List<Source>) -> Unit, metered: (Boolean) -> Unit,
) {
    DepotList {
        item { pending() }
        item {
            SectionTitle(stringResource(R.string.sources))
            if (state.sources.isNotEmpty()) FilledTonalButton(add, enabled = !busy, modifier = Modifier.padding(start = 8.dp)) {
                Glyph(R.drawable.ic_add)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.add_source))
            }
        }
        if (state.sources.isEmpty()) item { CatalogWelcome(!busy, add) }
        itemsIndexed(state.sources, key = { _, source -> source.id }) { index, source ->
            val verified = state.indexes.any { (entry, _) -> entry.id == source.id }
            SourcePanel(
                source = source, verified = verified, busy = busy, canMoveUp = index > 0,
                canMoveDown = index < state.sources.lastIndex,
                change = { enabled ->
                    change(state.sources.toMutableList().apply { set(index, source.copy(enabled = enabled)) })
                },
                move = { offset ->
                    change(state.sources.toMutableList().apply { add(index + offset, removeAt(index)) })
                },
                remove = { change(state.sources - source) },
            )
        }
        if (!official && state.sources.isEmpty()) item {
            SupportingText(stringResource(R.string.no_official_key), Modifier.padding(horizontal = 8.dp))
        }
        item { SectionTitle(stringResource(R.string.download_preferences)) }
        item {
            Surface(
                Modifier.fillMaxWidth().toggleable(state.metered, enabled = !busy, role = Role.Switch, onValueChange = metered),
                color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(24.dp),
            ) {
                Row(
                    Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.allow_metered), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Switch(state.metered, onCheckedChange = null, enabled = !busy)
                }
            }
        }
    }
}

@Composable
private fun SourcePanel(
    source: Source, verified: Boolean, busy: Boolean, canMoveUp: Boolean, canMoveDown: Boolean,
    change: (Boolean) -> Unit, move: (Int) -> Unit, remove: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var removing by rememberSaveable { mutableStateOf(false) }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PackageTile(R.drawable.ic_catalogs)
            Column(Modifier.weight(1f)) {
                Text(source.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                SupportingText(stringResource(when {
                    !source.enabled -> R.string.catalog_disabled
                    verified -> R.string.catalog_verified
                    else -> R.string.catalog_unverified
                }))
            }
            Switch(source.enabled, change, enabled = !busy, modifier = Modifier.semantics {
                contentDescription = source.label
            })
            Box {
                IconButton(onClick = { menu = true }, enabled = !busy) {
                    Glyph(R.drawable.ic_more, stringResource(R.string.catalog_actions, source.label))
                }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.move_up)) },
                        onClick = { menu = false; move(-1) }, enabled = canMoveUp)
                    DropdownMenuItem(text = { Text(stringResource(R.string.move_down)) },
                        onClick = { menu = false; move(1) }, enabled = canMoveDown)
                    DropdownMenuItem(text = { Text(stringResource(R.string.remove)) },
                        onClick = { menu = false; removing = true })
                }
            }
        }
        VerificationDetails {
            TechnicalValue(stringResource(R.string.source_url), source.url)
            TechnicalValue(stringResource(R.string.key_fingerprint), source.fingerprint)
        }
    }
    if (removing) AlertDialog(
        onDismissRequest = { removing = false },
        title = { Text(stringResource(R.string.remove_catalog, source.label)) },
        dismissButton = { TextButton({ removing = false }) { Text(stringResource(R.string.cancel)) } },
        confirmButton = {
            TextButton({ removing = false; remove() }, enabled = !busy) { Text(stringResource(R.string.remove)) }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddSource(sources: List<Source>, onDismiss: () -> Unit, save: (Source) -> Unit) {
    var label by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var key by rememberSaveable { mutableStateOf("") }
    var confirmed by remember { mutableStateOf<Source?>(null) }
    var invalid by rememberSaveable { mutableStateOf(false) }
    val source = confirmed
    val focus = LocalFocusManager.current
    val fieldShape = RoundedCornerShape(16.dp)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
    ) {
        Column(
            Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(if (source == null) R.string.add_source else R.string.confirm_key),
                style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            if (source != null) {
                SupportingText(stringResource(R.string.compare_key))
                Panel {
                    Text(source.label, style = MaterialTheme.typography.titleMedium)
                    TechnicalValue(stringResource(R.string.source_url), source.url)
                    TechnicalValue(stringResource(R.string.key_fingerprint), source.fingerprint)
                }
            } else {
                OutlinedTextField(label, { label = it.take(80); invalid = false }, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.source_name)) }, singleLine = true, shape = fieldShape,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
                OutlinedTextField(url, { url = it.take(2048); invalid = false }, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.source_url)) }, singleLine = true, shape = fieldShape,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next))
                OutlinedTextField(key, { key = it.take(128); invalid = false }, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.source_key)) }, singleLine = true, shape = fieldShape,
                    supportingText = { Text(stringResource(R.string.key_format)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), isError = invalid)
                if (invalid) Text(stringResource(R.string.invalid_source), color = MaterialTheme.colorScheme.error)
            }
            Button(
                onClick = {
                    if (source != null) save(source)
                    else try {
                        val candidate = Source.from(label.trim(), url.trim(), key.trim())
                        require(sources.none { it.id == candidate.id })
                        focus.clearFocus()
                        confirmed = candidate
                        invalid = false
                    } catch (error: Exception) {
                        Log.w(TAG, "Invalid catalog configuration", error)
                        invalid = true
                    }
                },
                enabled = source != null || (label.isNotBlank() && url.isNotBlank() && key.isNotBlank()),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(if (source == null) R.string.review_key else R.string.trust_key)) }
            if (source != null) TextButton(onClick = { confirmed = null }, Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.back))
            }
        }
    }
}

private fun AppCard.sourceLabels() = offers.map { it.source.label }.distinct().joinToString()

@DrawableRes
private fun appGlyph(packageName: String) = when (packageName) {
    "app.tantal.calendar" -> R.drawable.ic_calendar_month
    "app.tantal.clock" -> R.drawable.ic_alarm
    "app.tantal.depot" -> R.drawable.ic_catalogs
    "app.tantal.gallery" -> R.drawable.ic_photo_library
    "app.tantal.keyboard" -> R.drawable.ic_keyboard
    "app.tantal.messages" -> R.drawable.ic_chat
    "app.tantal.music" -> R.drawable.ic_music_note
    "app.tantal.notepad" -> R.drawable.ic_description
    "app.tantal.phone" -> R.drawable.ic_call
    "app.tantal.recorder" -> R.drawable.ic_mic
    "app.tantal.weather" -> R.drawable.ic_partly_cloudy_day
    else -> R.drawable.ic_package
}
