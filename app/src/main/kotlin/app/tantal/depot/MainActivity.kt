package app.tantal.depot

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.Formatter
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.tantal.theme.TantalTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var resumes by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val catalog = Catalog(this)
        setContent {
            TantalTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    Depot(this, catalog, resumes)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumes++
    }
}

internal data class DepotState(
    val sources: List<Source> = emptyList(),
    val indexes: List<Pair<Source, Index>> = emptyList(),
    val installed: Map<String, InstalledApp> = emptyMap(),
    val stored: List<StoredModel> = emptyList(),
    val kept: Set<String> = emptySet(),
    val reasons: Map<String, String> = emptyMap(),
    val appReasons: Map<String, List<String>> = emptyMap(),
    val owners: Map<ModelId, Set<String>> = emptyMap(),
    val pins: Map<String, String> = emptyMap(),
    val metered: Boolean = false,
    val canTransfer: Boolean = false,
    val pending: Boolean = false,
) {
    fun appOffer(offers: List<Offer<ApkBuild>>, id: ApkId): Offer<ApkBuild>? {
        val current = installed[id.packageName]
        if (current != null && current.signer != id.signer) return null
        return appOffer(offers, pins[id.key], reasons, Build.VERSION.SDK_INT,
            Build.SUPPORTED_ABIS.toSet(), current?.code)
    }

    fun modelTitle(family: String): String = indexes.firstNotNullOfOrNull { (_, index) ->
        index.models.firstOrNull { it.id.family == family }?.title
    } ?: stored.firstOrNull { it.build.id.family == family }?.build?.title ?: family

    fun modelIds(): Set<ModelId> =
        (indexes.flatMap { (_, index) -> index.models.map(ModelBuild::id) } +
            stored.map { it.build.id } + owners.keys).toSet()
}

private fun load(catalog: Catalog, apps: Apps, models: Models): DepotState {
    val sources = catalog.sources()
    val indexes = catalog.snapshot()
    val stored = models.stored()
    val reasons = catalog.reasons()
    val cards = appCards(indexes)
    val installed = cards.map { it.id.packageName }.distinct().mapNotNull { name ->
        apps.installed(name)?.let { name to it }
    }.toMap()
    val appReasons = if (reasons.keys.any { "/apk/" in it }) installed.mapNotNull { (name, current) ->
        val signer = current.signer ?: return@mapNotNull null
        val sha = current.apk.fileDigest()
        val global = reasons["$signer/apk/$sha"]
        val local = sources.mapNotNull { source ->
            reasons["${source.id}/apk/$sha"]?.let { "${source.label} · $it" }
        }
        name to (listOfNotNull(global) + local)
    }.toMap() else emptyMap()
    val owners = models.owners()
    val sourceKeys = cards.map { it.id.key } + indexes.flatMap { (_, index) ->
        index.models.map { modelSourceKey(it.id.family) }
    } + stored.map { modelSourceKey(it.build.id.family) } + owners.keys.map { modelSourceKey(it.family) }
    val keys = sourceKeys + sourceKeys.filter { it.startsWith("model/") }.map {
        modelPublisherKey(it.removePrefix("model/"))
    }
    return DepotState(sources, indexes, installed, stored, stored.filter(models::isKept).map(StoredModel::id).toSet(),
        reasons, appReasons, owners, keys.distinct().mapNotNull { key ->
            catalog.pinned(key)?.let { key to it }
        }.toMap(), models.allowMetered, models.canTransfer(), apps.hasPending())
}

@Composable
private fun Depot(activity: MainActivity, catalog: Catalog, resumes: Int) {
    val apps = remember { Apps(activity, catalog) }
    val models = remember { Models(activity, catalog) }
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val event by installEvent.collectAsState()
    val modelsChanged by modelChanges.collectAsState()
    val activeTransfer by transferring.collectAsState()
    var state by remember { mutableStateOf(DepotState()) }
    var screen by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var tab by rememberSaveable { mutableStateOf(DepotTab.Apps) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    val scrollStates = rememberSaveableStateHolder()
    var changed by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var meteredOffer by remember { mutableStateOf<Offer<ModelBuild>?>(null) }
    var launched by rememberSaveable { mutableIntStateOf(-1) }

    fun action(operation: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { operation() }
            } catch (error: CancellationException) {
                throw error
            } catch (_: TransferDeferred) {
                launch { snack.showSnackbar(activity.getString(R.string.waiting_network)) }
            } catch (error: Exception) {
                Log.w(TAG, "Depot operation failed", error)
                launch { snack.showSnackbar(activity.getString(R.string.operation_failed)) }
            } finally {
                busy = false
                changed++
            }
        }
    }

    suspend fun refresh() {
        val failed = mutableListOf<String>()
        for (source in catalog.sources().filter(Source::enabled)) {
            try {
                catalog.refresh(source)
                apps.checkPending()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Could not refresh ${source.label}", error)
                failed.add(source.label)
            }
        }
        if (failed.isNotEmpty()) scope.launch {
            snack.showSnackbar(activity.getString(R.string.catalog_failed, failed.joinToString()))
        }
    }

    LaunchedEffect(Unit) {
        action {
            models.reconcileCaches()
            apps.checkPending()
            ModelSync.enqueue(activity)
            refresh()
        }
    }

    LaunchedEffect(changed, resumes, modelsChanged) {
        try {
            state = withContext(Dispatchers.IO) { load(catalog, apps, models) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Could not read Depot state", error)
            snack.showSnackbar(activity.getString(R.string.operation_failed))
        } finally {
            loaded = true
        }
    }

    LaunchedEffect(event) {
        val done = event as? InstallEvent.Done ?: return@LaunchedEffect
        installEvent.value = null
        changed++
        snack.showSnackbar(activity.getString(done.message))
    }

    fun installApk(offer: Offer<ApkBuild>) {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            try {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.fromParts("package", activity.packageName, null)))
                scope.launch { snack.showSnackbar(activity.getString(R.string.install_source)) }
            } catch (error: Exception) {
                Log.w(TAG, "Could not open installation settings", error)
                scope.launch { snack.showSnackbar(activity.getString(R.string.install_failed)) }
            }
        } else action { apps.install(offer) }
    }

    fun openApp(id: ApkId) {
        try {
            val intent = activity.packageManager.getLaunchIntentForPackage(id.packageName)
                ?: throw IllegalStateException("App has no launch activity")
            activity.startActivity(intent)
        } catch (error: Exception) {
            Log.w(TAG, "Could not launch installed app", error)
            scope.launch { snack.showSnackbar(activity.getString(R.string.open_failed)) }
        }
    }

    fun installModel(offer: Offer<ModelBuild>) {
        if (models.canTransfer()) action { models.install(offer, manual = true) }
        else if (models.canTransfer(override = true)) meteredOffer = offer
        else scope.launch { snack.showSnackbar(activity.getString(R.string.waiting_network)) }
    }

    fun pin(key: String, source: String?) = action {
        catalog.pin(key, source)
        apps.checkPending()
        ModelSync.enqueue(activity)
    }

    fun saveSources(sources: List<Source>) = action {
        catalog.saveSources(sources)
        apps.checkPending()
        ModelSync.enqueue(activity)
        refresh()
    }

    val confirmation = event as? InstallEvent.Confirm
    val showConfirmation = confirmation != null && confirmation.id != launched
    val cards = remember(state.indexes) { appCards(state.indexes) }
    val updateCount = remember(state, cards) {
        cards.count { it.id.packageName in state.installed && state.appOffer(it.offers, it.id) != null }
    }
    val app = cards.firstOrNull { screen == "app/${it.id.key}" }
    val family = screen.takeIf { it.startsWith("model/") }?.removePrefix("model/")
    val detail = screen.isNotEmpty()

    fun closeSearch() {
        searching = false
        query = ""
    }

    BackHandler(detail || searching) {
        if (detail) screen = "" else closeSearch()
    }
    Scaffold(
        topBar = {
            DepotHeader(
                title = stringResource(if (detail) tab.title else R.string.app_name),
                detail = detail,
                busy = busy,
                query = query,
                searching = searching && !detail,
                canSearch = when (tab) {
                    DepotTab.Apps -> cards.isNotEmpty()
                    DepotTab.Models -> state.modelIds().isNotEmpty()
                    DepotTab.Updates -> updateCount > 0
                    DepotTab.Catalogs -> false
                },
                canRefresh = state.sources.any(Source::enabled),
                onQuery = { query = it.take(80) },
                onSearch = { if (searching) closeSearch() else searching = true },
                onBack = { screen = "" },
                onRefresh = { action { refresh() } },
            )
        },
        bottomBar = {
            if (!detail) DepotNavigation(tab, updateCount) {
                closeSearch()
                tab = it
            }
        },
        snackbarHost = { SnackbarHost(snack) },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val pending: @Composable () -> Unit = {
                if (showConfirmation || state.pending) {
                    PendingInstall(
                        canConfirm = showConfirmation,
                        busy = busy,
                        confirm = {
                            if (confirmation != null) {
                                try {
                                    apps.confirm(activity, confirmation)
                                    launched = confirmation.id
                                } catch (error: Exception) {
                                    Log.w(TAG, "Could not confirm installation", error)
                                    action { apps.abandon(confirmation.id) }
                                    installEvent.value = InstallEvent.Done(confirmation.id, R.string.install_failed)
                                }
                            }
                        },
                        cancel = { action { apps.cancelPending() } },
                    )
                }
            }
            scrollStates.SaveableStateProvider(screen.ifEmpty { tab.name }) {
                when {
                    app != null -> AppPage(state, app, !busy && !state.pending && confirmation == null,
                        !busy, pending, ::pin, ::installApk, ::openApp)
                    family != null -> ModelPage(state, family, !busy, activeTransfer, pending, ::pin, ::installModel,
                        { model -> action { models.remove(model); ModelSync.enqueue(activity) } },
                        { model, keep -> action { models.keep(model, keep); ModelSync.cleanup(activity) } })
                    detail -> DepotList { item { EmptyPanel(R.drawable.ic_package, stringResource(R.string.package_unavailable)) } }
                    tab == DepotTab.Catalogs -> SourcePage(state, catalog.officialConfigured, busy, pending,
                        add = { adding = true }, change = ::saveSources,
                        metered = { value -> action { models.allowMetered = value; ModelSync.enqueue(activity) } })
                    else -> Inventory(state, tab, cards, query.trim(), loaded, busy, pending,
                        add = { adding = true }, navigate = { screen = it })
                }
            }
        }
    }
    if (adding) AddSource(state.sources, onDismiss = { adding = false }) {
        adding = false
        saveSources(state.sources + it)
    }
    meteredOffer?.let { offer ->
        AlertDialog(onDismissRequest = { meteredOffer = null }, title = { Text(stringResource(R.string.metered_once)) },
            text = { Text(Formatter.formatFileSize(activity, offer.build.size)) },
            dismissButton = { TextButton({ meteredOffer = null }) { Text(stringResource(R.string.cancel)) } },
            confirmButton = { TextButton({
                meteredOffer = null
                action { models.install(offer, manual = state.owners[offer.build.id].isNullOrEmpty(), overrideMetered = true) }
            }) { Text(stringResource(R.string.download)) } })
    }
}
