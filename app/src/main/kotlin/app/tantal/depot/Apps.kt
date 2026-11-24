package app.tantal.depot

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.io.IOException

internal data class AppCard(val id: ApkId, val offers: List<Offer<ApkBuild>>) {
    val title get() = offers.first().build.title
    val builds get() = offers.groupBy { it.build.sha }.values
}

internal fun appCards(snapshot: List<Pair<Source, Index>>): List<AppCard> = snapshot
    .flatMap { (source, index) -> index.apks.map { Offer(source, it) } }
    .groupBy { it.build.id }.map { (id, offers) -> AppCard(id, offers) }

internal fun appOffer(
    offers: List<Offer<ApkBuild>>, pinned: String?, reasons: Map<String, String>, sdk: Int,
    abis: Set<String>, installedCode: Long? = null,
): Offer<ApkBuild>? = offers.asSequence()
    .filter { (source, build) ->
        (pinned == null || source.id == pinned) && reasons.apkReason(source.id, build) == null &&
            sdk in build.minSdk..build.maxSdk && (build.abis.isEmpty() || build.abis.any(abis::contains)) &&
            (installedCode == null || build.code > installedCode)
    }.maxByOrNull { it.build.code }

internal data class InstalledApp(val version: String, val code: Long, val signer: String?, val apk: File)

internal val signatureFlags get() = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())

internal fun PackageInfo.signerDigest(): String? {
    val signing = signingInfo ?: return null
    if (signing.hasMultipleSigners()) return null
    return signing.apkContentsSigners.singleOrNull()?.toByteArray()?.digest()
}

internal fun PackageManager.packageInfo(name: String): PackageInfo? = try {
    getPackageInfo(name, signatureFlags)
} catch (_: PackageManager.NameNotFoundException) {
    null
}

internal class Apps(private val context: Context, private val catalog: Catalog) {
    private val manager = context.packageManager
    private val installer = manager.packageInstaller
    private val sessions = context.getSharedPreferences("install_sessions", Context.MODE_PRIVATE)

    fun installed(name: String): InstalledApp? = manager.packageInfo(name)?.let {
        InstalledApp(it.versionName.orEmpty(), it.longVersionCode, it.signerDigest(),
            File(it.applicationInfo?.sourceDir ?: throw IOException("Installed APK path is unavailable")))
    }

    suspend fun install(offer: Offer<ApkBuild>) {
        val (source, build) = offer
        val file = File.createTempFile("release-", ".apk", context.cacheDir)
        try {
            fetchFile(build.url, file, build.size, build.sha)
            verify(build, file)
            synchronized(Catalog.lock) {
                requireAllowed(source.id, build.id.key, build.id.signer, build.sha)
                stage(offer, file)
            }
        } finally {
            deleteTemp(file)
        }
    }

    private fun verify(build: ApkBuild, file: File) {
        val pkg = manager.getPackageArchiveInfo(file.path, signatureFlags)
            ?: throw IOException("Could not inspect verified APK")
        require(pkg.packageName == build.id.packageName && pkg.longVersionCode == build.code &&
            pkg.versionName == build.version && pkg.signerDigest() == build.id.signer) {
            "APK package, version or signer does not match the catalog"
        }
        val current = installed(build.id.packageName)
        require(current == null || (current.signer == build.id.signer && build.code > current.code)) {
            "APK does not upgrade the installed package"
        }
        require(Build.VERSION.SDK_INT in build.minSdk..build.maxSdk)
    }

    private fun requireAllowed(source: String, packageId: String, signer: String, sha: String) {
        val reasons = catalog.reasons()
        val reason = reasons["$signer/apk/$sha"] ?: reasons["$source/apk/$sha"]
        require(reason == null) { reason ?: "APK withdrawn" }
        val pin = catalog.pinned(packageId)
        require(catalog.active(source) && (pin == null || pin == source)) { "APK source is unavailable" }
    }

    private fun stage(offer: Offer<ApkBuild>, file: File) {
        val (source, build) = offer
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(build.id.packageName)
            setSize(file.length())
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE)
        }
        check(installer.mySessions.isEmpty()) { "Another installation is pending" }
        val sessionId = installer.createSession(params)
        try {
            val value = listOf(source.id, build.id.key, build.id.signer, build.sha).joinToString("\n")
            check(sessions.edit().putString(sessionId.toString(), value).commit()) { "Could not save pending session" }
            installer.openSession(sessionId).use { session ->
                file.inputStream().use { input ->
                    session.openWrite("base.apk", 0, file.length()).use { destination ->
                        input.copyTo(destination)
                        session.fsync(destination)
                    }
                }
                requireAllowed(source.id, build.id.key, build.id.signer, build.sha)
                val callback = Intent(context, InstallResult::class.java)
                    .setData(Uri.parse("tantal-depot://install/$sessionId"))
                val pending = PendingIntent.getBroadcast(context, sessionId, callback, PendingIntent.FLAG_MUTABLE)
                session.commit(pending.intentSender)
            }
        } catch (error: Exception) {
            try {
                abandon(sessionId)
            } catch (cleanup: Exception) {
                error.addSuppressed(cleanup)
            }
            throw error
        }
    }

    private fun requireSession(id: Int) {
        val parts = sessions.getString(id.toString(), null)?.split('\n')
            ?: throw IOException("Unknown installation session")
        require(parts.size == 4) { "Invalid installation session" }
        requireAllowed(parts[0], parts[1], parts[2], parts[3])
    }

    fun confirm(activity: Context, confirmation: InstallEvent.Confirm) = synchronized(Catalog.lock) {
        requireSession(confirmation.id)
        activity.startActivity(confirmation.intent)
    }

    fun checkPending() = synchronized(Catalog.lock) {
        for (session in installer.mySessions) {
            try {
                requireSession(session.sessionId)
            } catch (error: Exception) {
                Log.w(TAG, "Installation session no longer eligible", error)
                abandon(session.sessionId)
                installEvent.value = InstallEvent.Done(session.sessionId, R.string.install_failed)
            }
        }
    }

    fun hasPending() = installer.mySessions.isNotEmpty()

    fun cancelPending() = synchronized(Catalog.lock) {
        installer.mySessions.forEach { abandon(it.sessionId) }
        installEvent.value = null
    }

    fun abandon(id: Int) {
        if (installer.getSessionInfo(id) != null) installer.abandonSession(id)
        finish(id)
    }

    fun finish(id: Int) {
        check(sessions.edit().remove(id.toString()).commit()) { "Could not clear installation session" }
    }
}

internal sealed interface InstallEvent {
    val id: Int
    data class Confirm(override val id: Int, val intent: Intent) : InstallEvent
    data class Done(override val id: Int, val message: Int) : InstallEvent
}

internal val installEvent = MutableStateFlow<InstallEvent?>(null)

class InstallResult : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.data?.lastPathSegment?.toIntOrNull() ?: return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val apps = Apps(context, Catalog(context))
        try {
            installEvent.value = if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                val confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    ?: throw IOException("Android did not return an installation confirmation")
                InstallEvent.Confirm(id, confirmation)
            } else {
                apps.finish(id)
                val message = when (status) {
                    PackageInstaller.STATUS_SUCCESS -> R.string.installed_success
                    PackageInstaller.STATUS_FAILURE_ABORTED -> R.string.install_cancelled
                    else -> {
                        Log.w(TAG, "PackageInstaller returned status $status")
                        R.string.install_failed
                    }
                }
                InstallEvent.Done(id, message)
            }
        } catch (error: Exception) {
            Log.w(TAG, "Could not handle installation result", error)
            try {
                apps.abandon(id)
            } catch (cleanup: Exception) {
                Log.w(TAG, "Could not abandon installation", cleanup)
            }
            installEvent.value = InstallEvent.Done(id, R.string.install_failed)
        }
    }
}
