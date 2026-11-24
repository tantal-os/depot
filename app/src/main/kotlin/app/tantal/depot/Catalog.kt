package app.tantal.depot

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

internal const val TAG = "TantalDepot"
internal const val INDEX_LIMIT = 4 * 1024 * 1024
internal const val MANIFEST_LIMIT = 32 * 1024
internal val digestPattern = Regex("[a-f0-9]{64}")
internal val namePattern = Regex("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,95}")
internal val packagePattern = Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+")
private const val OFFICIAL_URL =
    "https://raw.githubusercontent.com/tantal-os/packages/index/index.json"

internal fun sha256() = MessageDigest.getInstance("SHA-256")
internal fun ByteArray.digest() = sha256().digest(this).toHexString()
internal fun String.decoded(): ByteArray = Base64.getDecoder().decode(this)
internal fun ByteArray.encoded(): String = Base64.getEncoder().encodeToString(this)
internal fun publicKey(encoded: String) =
    KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(encoded.decoded()))

internal fun verifySignature(key: String, bytes: ByteArray, signature: ByteArray) {
    require(Signature.getInstance("Ed25519").run {
        initVerify(publicKey(key))
        update(bytes)
        verify(signature)
    }) { "Invalid Ed25519 signature" }
}

internal fun https(value: String): URI = URI(value).also {
    require(it.scheme == "https" && !it.host.isNullOrBlank() &&
        it.port in listOf(-1, 443) && it.rawUserInfo == null && it.rawFragment == null
    ) { "HTTPS on port 443 is required" }
}

internal fun JSONArray.objects() = List(length(), ::getJSONObject)
internal fun JSONObject.array(key: String) = getJSONArray(key).objects()
internal fun JSONObject.text(key: String, limit: Int) = getString(key).also {
    require(it.isNotBlank() && it.length <= limit) { "Invalid $key" }
}
internal fun JSONObject.digest(key: String) = getString(key).also {
    require(digestPattern.matches(it)) { "Invalid $key digest" }
}
internal fun JSONObject.name(key: String) = getString(key).also {
    require(namePattern.matches(it)) { "Invalid $key identifier" }
}

internal data class Source(val label: String, val url: String, val key: String, val enabled: Boolean = true) {
    val id = "$url\n$key".encodeToByteArray().digest()
    val fingerprint get() = publicKey(key).encoded.digest()

    fun json() = JSONObject().put("label", label).put("url", url).put("key", key).put("enabled", enabled)

    companion object {
        fun parse(json: JSONObject): Source = from(json.text("label", 80), json.text("url", 2048),
            json.text("key", 128)).copy(enabled = json.getBoolean("enabled"))

        fun from(label: String, url: String, key: String): Source {
            require(label.isNotBlank() && label.length <= 80 && url.length <= 2048)
            return Source(label, https(url).normalize().toASCIIString(), publicKey(key).encoded.encoded())
        }
    }
}

internal data class ApkId(val packageName: String, val signer: String) {
    val key get() = "$packageName@$signer"
}
internal data class ApkBuild(
    val id: ApkId, val title: String, val version: String, val code: Long, val minSdk: Int,
    val maxSdk: Int, val abis: List<String>, val sha: String, val size: Long, val url: URI,
)
internal data class ModelId(val family: String, val variant: String)
internal data class ModelBuild(
    val id: ModelId, val title: String, val label: String, val version: Long,
    val publisher: String, val repository: String, val revision: String, val directory: String,
    val sha: String, val size: Long, val minReader: Int,
) {
    fun asset(name: String) = URI("https://huggingface.co/$repository/resolve/$revision/$directory/$name")
}

internal data class Withdrawal(val kind: String, val sha: String, val reason: String)
internal data class Revocation(val kind: String, val sha: String, val signer: String, val reason: String)
internal data class Index(
    val revision: Long, val apks: List<ApkBuild>, val models: List<ModelBuild>,
    val withdrawals: List<Withdrawal>, val revocations: List<Revocation>,
)

internal data class Offer<T>(val source: Source, val build: T)

internal fun readIndex(source: Source, envelope: ByteArray): Index {
    val signed = JSONObject(envelope.decodeToString(throwOnInvalidSequence = true))
    val bytes = signed.getString("payload").decoded()
    verifySignature(source.key, bytes, signed.getString("signature").decoded())
    val json = JSONObject(bytes.decodeToString(throwOnInvalidSequence = true))
    require(json.getInt("schema") == 1)
    val revision = json.getLong("revision").also { require(it > 0) }
    val apks = json.array("apks").map { row ->
        val pkg = row.text("package", 255).also { require(packagePattern.matches(it)) }
        val abis = row.getJSONArray("abis")
        val minSdk = row.getInt("minSdk").also { require(it >= 1) }
        ApkBuild(ApkId(pkg, row.digest("signer")), row.text("title", 100), row.text("version", 100),
            row.getLong("versionCode").also { require(it > 0) }, minSdk,
            row.getInt("maxSdk").also { require(it >= minSdk) },
            List(abis.length()) { abis.getString(it) }, row.digest("sha256"),
            row.getLong("size").also { require(it in 1..2L * 1024 * 1024 * 1024) },
            https(row.text("url", 2048)))
    }
    val models = json.array("models").map { row ->
        val repository = row.text("repository", 193).also {
            require(it.split('/').let { parts -> parts.size == 2 && parts.all(namePattern::matches) })
        }
        val revisionId = row.text("commit", 40).also { require(it.matches(Regex("[a-f0-9]{40}"))) }
        ModelBuild(ModelId(row.name("package"), row.name("variant")), row.text("title", 100),
            row.text("label", 100), row.getLong("versionCode").also { require(it > 0) },
            publicKey(row.text("publisher", 128)).encoded.encoded(), repository, revisionId,
            row.name("directory"), row.digest("sha256"), row.getLong("size").also {
                require(it in 1..(16L shl 30))
            }, row.getInt("minReader").also { require(it > 0) })
    }
    val withdrawals = json.array("withdrawals").map {
        Withdrawal(it.kind(), it.digest("sha256"), it.text("reason", 500))
    }
    return Index(revision, apks, models, withdrawals, json.array("revocations").map(::readRevocation))
}

private fun JSONObject.kind() = getString("kind").also { require(it == "apk" || it == "model") }

private fun readRevocation(envelope: JSONObject): Revocation {
    val bytes = envelope.text("payload", 4096).decoded()
    val row = JSONObject(bytes.decodeToString(throwOnInvalidSequence = true))
    require(row.getInt("schema") == 1 && row.getString("statement") == "revoke")
    val kind = row.kind()
    val key = envelope.text("publisher", 8192)
    val signature = envelope.text("signature", 2048).decoded()
    val signer = if (kind == "apk") {
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(key.decoded().inputStream())
        val algorithm = when (certificate.publicKey.algorithm) {
            "RSA" -> "SHA256withRSA"
            "EC" -> "SHA256withECDSA"
            else -> throw IOException("Unsupported APK revocation key")
        }
        require(Signature.getInstance(algorithm).run {
            initVerify(certificate)
            update(bytes)
            verify(signature)
        }) { "Invalid APK revocation signature" }
        certificate.encoded.digest()
    } else {
        verifySignature(key, bytes, signature)
        publicKey(key).encoded.digest()
    }
    return Revocation(kind, row.digest("sha256"), signer, row.text("reason", 500))
}

internal class Catalog(context: Context) {
    private val root = File(context.noBackupFilesDir, "catalogs")
    private val config = AtomicFile(File(root, "sources.json"))
    private val prefs = context.getSharedPreferences("catalog_sources", Context.MODE_PRIVATE)
    private val official = BuildConfig.CATALOG_KEY.takeIf(String::isNotEmpty)?.let {
        Source.from("Tantal", OFFICIAL_URL, it)
    }
    val officialConfigured get() = official != null

    fun sources(): List<Source> = synchronized(lock) {
        val saved = if (config.baseFile.exists()) {
            JSONArray(config.readFully().decodeToString()).objects().map(Source::parse)
        } else emptyList()
        if (official != null && saved.none { it.id == official.id } && !config.baseFile.exists()) {
            listOf(official)
        } else saved
    }

    fun saveSources(sources: List<Source>) = synchronized(lock) {
        require(sources.size <= 32 && sources.distinctBy(Source::id).size == sources.size) {
            "Too many or duplicate catalogs"
        }
        config.write(JSONArray(sources.map(Source::json)).toString().encodeToByteArray())
    }

    fun pinned(id: String): String? = prefs.getString(id, null)

    fun pin(id: String, source: String?) = synchronized(lock) {
        if (!prefs.edit().putString(id, source).commit()) throw IOException("Could not save source preference")
    }

    private fun cache(id: String) = AtomicFile(File(root, "$id.json"))
    private fun ledger() = AtomicFile(File(root, "withdrawals.json"))
    private fun ledgerRows() = ledger().let {
        if (it.baseFile.exists()) JSONArray(it.readFully().decodeToString()).objects() else emptyList()
    }

    fun index(source: Source): Index? = synchronized(lock) {
        cache(source.id).let { if (it.baseFile.exists()) readIndex(source, it.readFully()) else null }
    }

    fun snapshot(): List<Pair<Source, Index>> = synchronized(lock) {
        sources().filter(Source::enabled).mapNotNull { source -> index(source)?.let { source to it } }
    }

    fun refresh(source: Source) {
        val next = readUrl(https(source.url), INDEX_LIMIT)
        val index = readIndex(source, next)
        synchronized(lock) {
            val cache = cache(source.id)
            if (cache.baseFile.exists()) {
                val old = cache.readFully()
                val previous = readIndex(source, old)
                require(index.revision >= previous.revision &&
                    (index.revision != previous.revision || next.contentEquals(old))) { "Catalog revision rollback" }
                if (next.contentEquals(old)) return
            }
            val entries = ledgerRows().associateBy { it.getString("id") }.toMutableMap()
            for (withdrawal in index.withdrawals) {
                val id = "${source.id}/${withdrawal.kind}/${withdrawal.sha}"
                entries[id] = JSONObject().put("id", id).put("reason", withdrawal.reason)
            }
            for (revocation in index.revocations) {
                val id = "${revocation.signer}/${revocation.kind}/${revocation.sha}"
                entries[id] = JSONObject().put("id", id).put("reason", revocation.reason)
            }
            ledger().write(JSONArray(entries.values.toList()).toString().encodeToByteArray())
            cache.write(next)
        }
    }

    fun reasons(): Map<String, String> = synchronized(lock) {
        ledgerRows().associate { it.getString("id") to it.getString("reason") }
    }

    fun active(id: String) = sources().any { it.id == id && it.enabled }

    companion object {
        internal val lock = Any()
    }
}

internal fun Map<String, String>.apkReason(source: String, build: ApkBuild): String? =
    this["${build.id.signer}/apk/${build.sha}"] ?: this["$source/apk/${build.sha}"]

internal fun Map<String, String>.modelReason(source: String, build: ModelBuild): String? =
    this["${build.publisher.decoded().digest()}/model/${build.sha}"] ?: this["$source/model/${build.sha}"]

internal fun AtomicFile.write(data: ByteArray) {
    val output = startWrite()
    try {
        output.write(data)
        output.fd.sync()
        finishWrite(output)
    } catch (error: Exception) {
        failWrite(output)
        throw error
    }
}

internal fun readUrl(url: URI, limit: Int): ByteArray = request(url) { connection ->
    require(connection.responseCode == 200) { "Unexpected partial response" }
    connection.inputStream.use {
        it.readNBytes(limit + 1).also { data -> require(data.size <= limit) { "Response is too large" } }
    }
}

private fun <T> request(
    first: URI, offset: Long = 0, operation: (HttpURLConnection) -> T,
): T {
    var uri = first
    repeat(8) {
        val connection = https(uri.toString()).toURL().openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("User-Agent", "TantalDepot/0.1")
            connection.setRequestProperty("Accept-Encoding", "identity")
            if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
            when (val code = connection.responseCode) {
                200, 206 -> return operation(connection)
                301, 302, 303, 307, 308 -> uri = uri.resolve(connection.getHeaderField("Location")
                    ?: throw IOException("Redirect has no Location"))
                else -> throw IOException("Request failed with HTTP $code")
            }
        } finally {
            connection.disconnect()
        }
    }
    throw IOException("Too many redirects")
}

internal suspend fun fetchFile(
    url: URI, file: File, size: Long, sha: String, resume: Boolean = false, checkPolicy: () -> Unit = {},
) {
    val coroutine = currentCoroutineContext()
    val offset = file.length().takeIf { resume && it in 1 until size } ?: 0
    if (resume && file.length() == size && file.fileDigest() == sha) return
    request(url, offset) { connection ->
        val start = if (connection.responseCode == 206) {
            require(offset > 0 && connection.getHeaderField("Content-Range") ==
                "bytes $offset-${size - 1}/$size") { "Unexpected file range" }
            offset
        } else 0
        val advertised = connection.contentLengthLong
        require(advertised < 0 || advertised == size - start) { "Unexpected file length" }
        val digest = sha256()
        val buffer = ByteArray(64 * 1024)
        if (start > 0) file.inputStream().use { input ->
            while (true) {
                coroutine.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        var total = start
        var invalid = false
        try {
            connection.inputStream.use { input ->
                FileOutputStream(file, start > 0).use { output ->
                    while (true) {
                        coroutine.ensureActive()
                        checkPolicy()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        invalid = total > size
                        require(!invalid) { "Download exceeds declared length" }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                    if (total != size) throw IOException("Download ended before the declared length")
                    invalid = digest.digest().toHexString() != sha
                    require(!invalid) { "Download digest mismatch" }
                    output.fd.sync()
                }
            }
        } finally {
            if (invalid) deleteTemp(file)
        }
    }
}

internal fun File.fileDigest(): String = inputStream().use { input ->
    val digest = sha256()
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    digest.digest().toHexString()
}

internal fun deleteTemp(file: File) {
    if (file.exists() && !file.deleteRecursively()) Log.w(TAG, "Could not remove temporary data")
}
