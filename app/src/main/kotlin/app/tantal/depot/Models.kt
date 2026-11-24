package app.tantal.depot

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.ParcelFileDescriptor
import android.util.AtomicFile
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal const val MODEL_READER = 1
internal val modelChanges = MutableStateFlow(0)
internal val transferring = MutableStateFlow<ModelId?>(null)

internal data class ModelFile(val role: String, val name: String, val size: Long, val digest: String)
internal data class Manifest(val files: List<ModelFile>, val bytes: ByteArray, val signature: ByteArray)

internal fun verifyManifest(build: ModelBuild, bytes: ByteArray, signature: ByteArray): Manifest {
    require(bytes.size <= MANIFEST_LIMIT && bytes.digest() == build.sha)
    verifySignature(build.publisher, bytes, signature)
    val json = JSONObject(bytes.decodeToString(throwOnInvalidSequence = true))
    require(json.getInt("schema") == 1 && json.getString("package") == build.id.family &&
        json.getString("variant") == build.id.variant && json.getLong("versionCode") == build.version &&
        json.getInt("minReader") == build.minReader && build.minReader in 1..MODEL_READER
    ) { "Model manifest does not match catalog or reader" }
    val format = json.name("format")
    val files = json.array("files").map {
        ModelFile(it.name("role"), it.name("name"), it.getLong("size").also { size ->
            require(size in 1..build.size)
        }, it.digest("sha256"))
    }
    require(files.size in 1..32 && files.distinctBy(ModelFile::name).size == files.size &&
        files.distinctBy(ModelFile::role).size == files.size && files.sumOf(ModelFile::size) == build.size &&
        files.none { it.name in setOf("manifest.json", "manifest.sig", "origin.json") }
    ) { "Invalid model file list" }
    if (format == "tflite") require(files.any { it.name.endsWith(".tflite") }) { "Model graph is missing" }
    return Manifest(files, bytes, signature)
}

internal fun modelOffer(
    id: ModelId, snapshot: List<Pair<Source, Index>>, pinned: String?, reasons: Map<String, String>,
    publisher: String? = null,
): Offer<ModelBuild>? = snapshot.asSequence()
    .filter { (source, _) -> pinned == null || source.id == pinned }
    .flatMap { (source, index) -> index.models.asSequence().map { Offer(source, it) } }
    .filter { (source, build) -> build.id == id && (publisher == null || build.publisher == publisher) &&
        build.minReader <= MODEL_READER &&
        reasons.modelReason(source.id, build) == null }
    .maxByOrNull { it.build.version }

internal data class StoredModel(val source: String, val build: ModelBuild, val directory: File) {
    val id get() = "${build.sha}@$source"
}

internal class TransferDeferred : IOException("Model transfer is waiting for an allowed network")

internal class Models(private val context: Context, private val catalog: Catalog) {
    private val root = File(context.noBackupFilesDir, "models")
    private val state = AtomicFile(File(root, "state.json"))
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val prefs = context.getSharedPreferences("model_downloads", Context.MODE_PRIVATE)

    var allowMetered: Boolean
        get() = prefs.getBoolean("metered", false)
        set(value) {
            if (!prefs.edit().putBoolean("metered", value).commit()) throw IOException("Could not save network policy")
        }

    fun canTransfer(override: Boolean = false): Boolean {
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
            (override || allowMetered || !connectivity.isActiveNetworkMetered)
    }

    private fun stage(offer: Offer<ModelBuild>) = File(root, ".${offer.build.sha}@${offer.source.id}")
    private fun destination(offer: Offer<ModelBuild>) = File(root, "${offer.build.sha}@${offer.source.id}")

    suspend fun install(offer: Offer<ModelBuild>, manual: Boolean, overrideMetered: Boolean = false) = transfer.withLock {
        val (source, build) = offer
        fun policy() {
            if (!canTransfer(overrideMetered)) throw TransferDeferred()
        }
        val target = destination(offer)
        synchronized(Catalog.lock) {
            requireEligible(source.id, build)
            if (!manual && build.id !in owners(readState())) return@withLock
            if (target.isDirectory) {
                if (manual) keep(readStored(target), true)
                return@withLock
            }
        }
        policy()
        transferring.value = build.id
        try {
            download(offer, manual, ::policy)
        } finally {
            transferring.value = null
            modelChanges.update { it + 1 }
        }
    }

    private suspend fun download(offer: Offer<ModelBuild>, manual: Boolean, policy: () -> Unit) {
        val (source, build) = offer
        val temp = stage(offer)
        Files.createDirectories(temp.toPath())
        val parts = Files.createDirectories(File(temp, ".parts").toPath()).toFile()
        val manifestFile = File(temp, "manifest.json")
        val signatureFile = File(temp, "manifest.sig")
        val manifest = if (manifestFile.isFile && signatureFile.isFile) {
            verifyManifest(build, manifestFile.readBytes(), signatureFile.readBytes())
        } else {
            val bytes = readUrl(build.asset("manifest.json"), MANIFEST_LIMIT)
            val signature = readUrl(build.asset("manifest.sig"), 64)
            verifyManifest(build, bytes, signature).also {
                AtomicFile(manifestFile).write(bytes)
                AtomicFile(signatureFile).write(signature)
            }
        }
        for (asset in manifest.files) {
            policy()
            val complete = File(temp, asset.name)
            if (complete.isFile && complete.length() == asset.size && complete.fileDigest() == asset.digest) continue
            val partial = File(parts, asset.name)
            fetchFile(build.asset(asset.name), partial, asset.size, asset.digest, resume = true, checkPolicy = policy)
            Files.move(partial.toPath(), complete.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }
        Files.delete(parts.toPath())
        synchronized(Catalog.lock) {
            requireEligible(source.id, build)
            if (!manual && build.id !in owners(readState())) {
                deleteTemp(temp)
                return
            }
            val origin = record(source.id, build).put("manual", manual)
            AtomicFile(File(temp, "origin.json")).write(origin.toString().encodeToByteArray())
            if (catalog.pinned(modelPublisherKey(build.id.family)) == null) {
                catalog.pin(modelPublisherKey(build.id.family), build.publisher)
            }
            Files.move(temp.toPath(), destination(offer).toPath(), StandardCopyOption.ATOMIC_MOVE)
        }
    }

    private fun requireEligible(source: String, build: ModelBuild) {
        val pin = catalog.pinned(modelSourceKey(build.id.family))
        require(catalog.active(source) && (pin == null || pin == source)) { "Selected model source is unavailable" }
        val publisher = catalog.pinned(modelPublisherKey(build.id.family))
        require(publisher == null || publisher == build.publisher) { "Unexpected model publisher" }
        val reason = catalog.reasons().modelReason(source, build)
        require(reason == null) { reason ?: "Model withdrawn" }
    }

    fun stored(): List<StoredModel> = synchronized(Catalog.lock) {
        val list = root.listFiles() ?: if (root.exists()) throw IOException("Could not enumerate models") else emptyArray()
        list.filter { it.isDirectory && !it.name.startsWith(".") }.mapNotNull { directory ->
            try {
                readStored(directory)
            } catch (error: Exception) {
                Log.e(TAG, "Could not read stored model ${directory.name}", error)
                null
            }
        }
    }

    private fun readStored(directory: File): StoredModel {
        val json = JSONObject(File(directory, "origin.json").readText())
        val id = ModelId(json.name("package"), json.name("variant"))
        val build = ModelBuild(id, json.getString("title"), json.getString("label"), json.getLong("versionCode"),
            json.getString("publisher"), json.getString("repository"), json.getString("commit"),
            json.name("directory"), json.digest("sha256"), json.getLong("size"), json.getInt("minReader"))
        return StoredModel(json.digest("source"), build, directory)
    }

    private fun record(source: String, build: ModelBuild) = JSONObject()
        .put("source", source).put("package", build.id.family).put("variant", build.id.variant)
        .put("title", build.title).put("label", build.label).put("versionCode", build.version)
        .put("publisher", build.publisher).put("repository", build.repository).put("commit", build.revision)
        .put("directory", build.directory).put("sha256", build.sha).put("size", build.size)
        .put("minReader", build.minReader)

    fun isKept(model: StoredModel) = JSONObject(File(model.directory, "origin.json").readText()).getBoolean("manual")

    fun keep(model: StoredModel, value: Boolean) = synchronized(Catalog.lock) {
        val file = File(model.directory, "origin.json")
        val origin = JSONObject(file.readText()).put("manual", value)
        AtomicFile(file).write(origin.toString().encodeToByteArray())
    }

    private fun readState(): JSONObject =
        if (state.baseFile.exists()) JSONObject(state.readFully().decodeToString())
        else JSONObject().put("requests", JSONObject()).put("consumers", JSONObject())

    private fun saveState(data: JSONObject) = state.write(data.toString().encodeToByteArray())

    fun declare(caller: String, purpose: String, requests: List<ModelId>) = synchronized(Catalog.lock) {
        require(namePattern.matches(purpose) && requests.size <= 32)
        val data = readState()
        val before = owners(data)
        val all = data.getJSONObject("requests")
        val purposes = all.optJSONObject(caller) ?: JSONObject().also { all.put(caller, it) }
        require(purposes.has(purpose) || purposes.length() < 16) { "Too many request purposes" }
        if (requests.isEmpty()) purposes.remove(purpose)
        else purposes.put(purpose, JSONArray(requests.distinct().map { it.key }))
        if (purposes.length() == 0) all.remove(caller)
        val after = owners(data)
        val consumer = data.getJSONObject("consumers").optJSONObject(caller)
        for ((id, owners) in before) {
            if (caller in owners && caller !in after[id].orEmpty()) consumer?.remove(id.key)
        }
        saveState(data)
        modelChanges.update { it + 1 }
    }

    private fun owners(data: JSONObject): Map<ModelId, Set<String>> {
        val all = data.getJSONObject("requests")
        val result = mutableMapOf<ModelId, MutableSet<String>>()
        for (caller in all.keys()) {
            val purposes = all.getJSONObject(caller)
            for (purpose in purposes.keys()) {
                val ids = purposes.getJSONArray(purpose)
                for (index in 0 until ids.length()) {
                    result.getOrPut(modelId(ids.getString(index)), ::mutableSetOf).add(caller)
                }
            }
        }
        return result
    }

    fun owners(): Map<ModelId, Set<String>> = synchronized(Catalog.lock) { owners(readState()) }

    fun unregister(caller: String) = synchronized(Catalog.lock) {
        val data = readState()
        data.getJSONObject("requests").remove(caller)
        data.getJSONObject("consumers").remove(caller)
        saveState(data)
        modelChanges.update { it + 1 }
    }

    fun reconcileCaches() = synchronized(Catalog.lock) {
        val data = readState()
        val all = data.getJSONObject("requests")
        val consumers = data.getJSONObject("consumers")
        val names = (all.keys().asSequence() + consumers.keys().asSequence()).toSet()
        for (name in names) {
            if (!ModelTrust.matches(context, name)) {
                all.remove(name)
                consumers.remove(name)
            }
        }
        val required = owners(data)
        saveState(data)
        val references = consumers.keys().asSequence().flatMap { caller ->
            val byModel = consumers.getJSONObject(caller)
            byModel.keys().asSequence().flatMap { key ->
                val model = byModel.getJSONObject(key)
                sequenceOf("opened", "working", "previous").map(model::optString)
            }
        }.filter(String::isNotEmpty).toSet()
        val installed = stored()
        val reasons = catalog.reasons()
        val sources = catalog.sources().filter(Source::enabled).map(Source::id)
        val current = installed.filter { model ->
            val pin = catalog.pinned(modelSourceKey(model.build.id.family))
            model.source in sources && (pin == null || pin == model.source) &&
                reasons.modelReason(model.source, model.build) == null
        }.groupBy { it.build.id }.mapValues { (_, versions) ->
            versions.maxWith(compareBy<StoredModel> { it.build.version }.thenBy { -sources.indexOf(it.source) })
        }
        for (model in installed) {
            val id = model.build.id
            val removed = id !in required
            val superseded = current[id]?.let { it.build.version > model.build.version } == true &&
                required[id].orEmpty().all { caller ->
                    val active = consumers.optJSONObject(caller)?.optJSONObject(id.key)?.optString("working")
                    active == current[id]?.build?.sha
                }
            if ((removed || superseded) && model.build.sha !in references && !isKept(model)) remove(model)
        }
        modelChanges.update { it + 1 }
    }

    fun remove(model: StoredModel) = synchronized(Catalog.lock) {
        require(model.directory.deleteRecursively()) { "Could not remove model files" }
    }

    private fun candidates(id: ModelId, consumer: JSONObject): List<StoredModel> {
        val pin = catalog.pinned(modelSourceKey(id.family))
        val reasons = catalog.reasons()
        val sources = catalog.sources().filter(Source::enabled).map(Source::id)
        val failed = consumer.optJSONObject("failed") ?: JSONObject()
        return stored().filter {
            val build = it.build
            build.id == id && build.minReader <= MODEL_READER && it.source in sources &&
                (pin == null || it.source == pin) && reasons.modelReason(it.source, build) == null &&
                !failed.has(build.sha)
        }.sortedWith(compareByDescending<StoredModel> { it.build.version }.thenBy { sources.indexOf(it.source) })
    }

    private fun consumer(data: JSONObject, caller: String, id: ModelId): JSONObject {
        val all = data.getJSONObject("consumers")
        val byModel = all.optJSONObject(caller) ?: JSONObject().also { all.put(caller, it) }
        require(byModel.has(id.key) || byModel.length() < 512) { "Too many model references" }
        return byModel.optJSONObject(id.key) ?: JSONObject().also { byModel.put(id.key, it) }
    }

    private fun eligibleVersions(id: ModelId, consumer: JSONObject): List<StoredModel> {
        val candidates = candidates(id, consumer)
        if (!consumer.optBoolean("recovering")) return candidates
        val working = consumer.optString("working")
        val failedVersion = consumer.optLong("failedVersion", 0)
        return candidates.filter { it.build.sha == working || it.build.version > failedVersion }
    }

    fun status(caller: String, id: ModelId): String = synchronized(Catalog.lock) {
        val versions = eligibleVersions(id, consumer(readState(), caller, id))
        for (model in versions) {
            try {
                openFiles(model).use { return "ready" }
            } catch (error: Exception) {
                Log.e(TAG, "Could not read installed model", error)
            }
        }
        val pin = catalog.pinned(modelSourceKey(id.family))
        val offer = modelOffer(id, catalog.snapshot(), pin, catalog.reasons(),
            catalog.pinned(modelPublisherKey(id.family)))
        when {
            offer == null && pin != null -> "source_unavailable"
            offer == null -> "unavailable"
            transferring.value == id -> "downloading"
            !canTransfer() -> "waiting_network"
            else -> "pending"
        }
    }

    fun open(caller: String, id: ModelId): ModelAccess = synchronized(Catalog.lock) {
        val data = readState()
        val consumer = consumer(data, caller, id)
        val options = eligibleVersions(id, consumer)
        for (model in options) {
            val output = try {
                openFiles(model)
            } catch (error: Exception) {
                Log.e(TAG, "Could not open model ${model.build.sha}", error)
                continue
            }
            try {
                consumer.put("opened", model.build.sha).put("openedVersion", model.build.version)
                saveState(data)
                return output
            } catch (error: Exception) {
                output.close()
                throw error
            }
        }
        ModelAccess("unavailable")
    }

    fun report(caller: String, id: ModelId, sha: String, success: Boolean): String = synchronized(Catalog.lock) {
        require(digestPattern.matches(sha))
        val data = readState()
        val consumer = consumer(data, caller, id)
        require(sha == consumer.optString("opened")) { "Build was not opened by this consumer" }
        if (success) {
            require(candidates(id, consumer).any { it.build.sha == sha }) { "Build is unavailable" }
            val previous = consumer.optString("working")
            if (previous != sha) consumer.put("previous", previous)
            consumer.put("working", sha)
            consumer.put("recovering", false)
        } else {
            val failed = consumer.optJSONObject("failed") ?: JSONObject().also { consumer.put("failed", it) }
            failed.put(sha, true)
            if (sha == consumer.optString("working")) consumer.put("working", consumer.optString("previous"))
            consumer.put("recovering", true).put("failedVersion", consumer.optLong("openedVersion"))
        }
        saveState(data)
        "ok"
    }

    private fun openFiles(model: StoredModel): ModelAccess {
        val manifest = verifyManifest(model.build, File(model.directory, "manifest.json").readBytes(),
            File(model.directory, "manifest.sig").readBytes())
        val files = arrayListOf<ParcelFileDescriptor>()
        return try {
            for (entry in manifest.files) {
                val file = File(model.directory, entry.name)
                require(file.length() == entry.size) { "Installed model length changed" }
                files.add(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
            }
            ModelAccess("ready", model.build.sha, manifest.bytes, manifest.signature, model.build.publisher,
                manifest.files.map(ModelFile::role), files)
        } catch (error: Exception) {
            files.forEach(::closeDescriptor)
            throw error
        }
    }

    companion object {
        private val transfer = Mutex()
    }
}

internal val ModelId.key get() = "$family/$variant"
internal fun modelId(value: String): ModelId {
    val parts = value.split('/')
    require(parts.size == 2 && parts.all(namePattern::matches)) { "Invalid model identifier" }
    return ModelId(parts[0], parts[1])
}
internal fun modelSourceKey(family: String) = "model/$family"
internal fun modelPublisherKey(family: String) = "publisher/$family"
internal fun closeDescriptor(file: ParcelFileDescriptor) {
    try {
        file.close()
    } catch (error: IOException) {
        Log.w(TAG, "Could not close model file", error)
    }
}
