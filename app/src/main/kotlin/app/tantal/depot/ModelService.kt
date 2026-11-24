package app.tantal.depot

import android.app.Service
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Process
import android.os.UserHandle
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal object ModelTrust {
    private const val TANTAL_SIGNER = "33328a5787483f896729c9d02298b98514986c29deb63098d81ccad84b942ff1"
    private val consumers = mapOf(
        "app.tantal.keyboard" to setOf("d3"),
        "app.tantal.recorder" to setOf("d3", "asr"),
    )

    fun allowed(caller: String, value: String): ModelId {
        val id = modelId(value)
        require(id.family in consumers[caller].orEmpty()) { "Consumer cannot use this model family" }
        return id
    }

    fun registered(name: String) = name in consumers

    fun matches(context: Context, name: String): Boolean =
        registered(name) && context.packageManager.packageInfo(name)?.signerDigest() == TANTAL_SIGNER

    fun authenticate(context: Context): String {
        val uid = Binder.getCallingUid()
        if (UserHandle.getUserHandleForUid(uid) != Process.myUserHandle()) throw SecurityException("Wrong profile")
        val name = context.packageManager.getPackagesForUid(uid)?.singleOrNull()
            ?: throw SecurityException("Missing package or shared UID")
        if (!matches(context, name)) throw SecurityException("Untrusted model consumer")
        return name
    }
}

class ModelService : Service() {
    private val models by lazy { Models(this, Catalog(this)) }

    private val connection = object : IModelDepot.Stub() {
        override fun declareRequired(purpose: String, packages: MutableList<String>): String = call("unavailable") { caller ->
            require(packages.size <= 32)
            models.declare(caller, purpose, packages.map { ModelTrust.allowed(caller, it) })
            ModelSync.enqueue(this@ModelService)
            "ok"
        }

        override fun openModel(packageVariant: String): ModelAccess = call(ModelAccess("unavailable")) { caller ->
            models.open(caller, ModelTrust.allowed(caller, packageVariant))
        }

        override fun reportModel(packageVariant: String, digest: String, success: Boolean): String = call("unavailable") { caller ->
            models.report(caller, ModelTrust.allowed(caller, packageVariant), digest, success)
        }

        override fun getStatus(packageVariant: String): String = call("unavailable") { caller ->
            models.status(caller, ModelTrust.allowed(caller, packageVariant))
        }

        private fun <T> call(fallback: T, operation: (String) -> T): T {
            val caller = ModelTrust.authenticate(this@ModelService)
            val identity = Binder.clearCallingIdentity()
            return try {
                operation(caller)
            } catch (error: Exception) {
                Log.w(TAG, "Model request from $caller failed", error)
                fallback
            } finally {
                Binder.restoreCallingIdentity(identity)
            }
        }
    }

    override fun onBind(intent: Intent) = connection
}

class ModelSync : JobService() {
    private val jobs = mutableMapOf<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        jobs[params.jobId] = CoroutineScope(Dispatchers.Main).launch {
            val retry = try {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    if (params.jobId == CLEANUP) {
                        Models(this@ModelSync, Catalog(this@ModelSync)).reconcileCaches()
                        false
                    } else reconcile()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Model reconciliation failed", error)
                true
            }
            jobs.remove(params.jobId)
            jobFinished(params, retry && params.jobId != PERIODIC)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        jobs.remove(params.jobId)?.cancel()
        return params.jobId != PERIODIC
    }

    private suspend fun reconcile(): Boolean {
        val catalog = Catalog(this)
        val models = Models(this, catalog)
        models.reconcileCaches()
        var retry = false
        for (source in catalog.sources().filter(Source::enabled)) {
            try {
                catalog.refresh(source)
                Apps(this, catalog).checkPending()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Could not refresh ${source.label}", error)
                retry = true
            }
        }
        val snapshot = catalog.snapshot()
        val reasons = catalog.reasons()
        for (id in models.owners().keys) {
            val offer = modelOffer(id, snapshot, catalog.pinned(modelSourceKey(id.family)), reasons,
                catalog.pinned(modelPublisherKey(id.family))) ?: continue
            try {
                models.install(offer, manual = false)
            } catch (_: TransferDeferred) {
                retry = true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Could not install requested model ${id.key}", error)
                retry = true
            }
        }
        return retry
    }

    companion object {
        private const val SYNC = 0x444550
        private const val PERIODIC = SYNC + 1
        private const val CLEANUP = SYNC + 2

        fun enqueue(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            val service = ComponentName(context, ModelSync::class.java)
            val models = Models(context, Catalog(context))
            val network = if (models.allowMetered) JobInfo.NETWORK_TYPE_ANY else JobInfo.NETWORK_TYPE_UNMETERED
            val job = JobInfo.Builder(SYNC, service).setRequiredNetworkType(network).setPersisted(true).build()
            check(scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS) { "Could not schedule model downloads" }
            if (scheduler.getPendingJob(PERIODIC) == null) {
                val periodic = JobInfo.Builder(PERIODIC, service).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(12 * 60 * 60 * 1000L).setPersisted(true).build()
                check(scheduler.schedule(periodic) == JobScheduler.RESULT_SUCCESS) { "Could not schedule catalog refresh" }
            }
        }

        fun cleanup(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            val job = JobInfo.Builder(CLEANUP, ComponentName(context, ModelSync::class.java)).setPersisted(true).build()
            check(scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS) { "Could not schedule model cleanup" }
        }
    }
}

class ConsumerRemoved : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_PACKAGE_FULLY_REMOVED) return
        val caller = intent.data?.schemeSpecificPart?.takeIf(ModelTrust::registered) ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Models(context, Catalog(context)).unregister(caller)
                ModelSync.cleanup(context)
            } catch (error: Exception) {
                Log.e(TAG, "Could not reconcile the removed package", error)
            } finally {
                pending.finish()
            }
        }
    }
}
