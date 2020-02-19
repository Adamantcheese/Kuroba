package com.github.adamantcheese.chan.core.manager

import android.annotation.SuppressLint
import android.os.Build
import com.github.adamantcheese.chan.BuildConfig
import com.github.adamantcheese.chan.core.base.MResult
import com.github.adamantcheese.chan.core.settings.ChanSettings
import com.github.adamantcheese.chan.ui.controller.LogsController
import com.github.adamantcheese.chan.utils.BackgroundUtils
import com.github.adamantcheese.chan.utils.Logger
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import io.reactivex.Completable
import io.reactivex.Flowable
import io.reactivex.Single
import io.reactivex.processors.PublishProcessor
import io.reactivex.schedulers.Schedulers
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ReportManager(
        private val okHttpClient: OkHttpClient,
        private val threadSaveManager: ThreadSaveManager,
        private val gson: Gson,
        private val crashLogsDirPath: File
) {
    private val crashLogSenderQueue = PublishProcessor.create<ReportRequestWithFile>()

    private val senderThreadIndex = AtomicInteger(0)
    private val senderScheduler = Schedulers.from(
            Executors.newFixedThreadPool(1) { runnable ->
                return@newFixedThreadPool Thread(
                        runnable,
                        String.format(
                                Locale.US,
                                SENDER_THREAD_NAME_FORMAT,
                                senderThreadIndex.getAndIncrement()
                        )
                )
            }
    )

    init {
        createCrashLogsDirIfNotExists()

        initSenderQueue()
    }

    /**
     * This is a singleton class so we don't care about the disposable since we will never should
     * dispose of this stream
     * */
    @SuppressLint("CheckResult")
    private fun initSenderQueue() {
        crashLogSenderQueue
                .subscribeOn(senderScheduler)
                .observeOn(senderScheduler)
                .buffer(1, TimeUnit.SECONDS)
                .onBackpressureBuffer(UNBOUNDED_QUEUE_MIN_SIZE, false, true)
                .filter { requests -> requests.isNotEmpty() }
                .flatMap { requests ->
                    Logger.d(TAG, "Collected ${requests.size} crash logs")

                    return@flatMap Flowable.fromIterable(requests)
                            .flatMapSingle { (request, crashLogFile) ->
                                return@flatMapSingle processSingleRequest(request, crashLogFile)
                            }
                }
                .subscribe({
                    // Do nothing
                }, { error ->
                    throw RuntimeException("$TAG Uncaught exception!!! " +
                            "workerQueue is in error state now!!! " +
                            "This should not happen!!!, original error = " + error.message)
                }, {
                    throw RuntimeException(
                            "$TAG workerQueue stream has completed!!! This should not happen!!!"
                    )
                })
    }

    private fun processSingleRequest(request: ReportRequest, crashLogFile: File): Single<MResult<Boolean>> {
        BackgroundUtils.ensureBackgroundThread()

        return sendInternal(request)
                .onErrorReturn { error -> MResult.error(error) }
                .doOnSuccess { result ->
                    when (result) {
                        is MResult.Value -> {
                            Logger.d(TAG, "Crash log ${crashLogFile.absolutePath} sent")

                            if (!crashLogFile.delete()) {
                                Logger.e(TAG, "Couldn't delete crash log file: ${crashLogFile.absolutePath}")
                            }
                        }
                        is MResult.Error -> {
                            Logger.e(TAG, "Error while trying to send crash log", result.error)
                        }
                    }
                }
    }

    fun storeCrashLog(error: String) {
        if (!createCrashLogsDirIfNotExists()) {
            return
        }

        val time = System.nanoTime()
        val newCrashLog = File(crashLogsDirPath, "${CRASH_LOG_FILE_NAME_PREFIX}_${time}.txt")

        if (newCrashLog.exists()) {
            return
        }

        try {
            val settings = getSettingsStateString()
            val logs = LogsController.loadLogs(CRASH_REPORT_LOGS_LINES_COUNT)

            val resultString = buildString {
                appendln("=== LOGS ===")
                logs?.let { append(it) }
                append("\n\n")
                appendln("=== STACKTRACE ===")
                append(error)
                append("\n\n")
                appendln("=== SETTINGS ===")
                append(settings)
            }

            newCrashLog.writeText(resultString)
        } catch (error: Throwable) {
            Logger.e(TAG, "Error writing to a crash log file", error)
            return
        }

        Logger.d(TAG, "Stored new crash log, path = ${newCrashLog.absolutePath}")
    }

    // Since this is a singleton we don't care about disposing of this thing because nothing may
    // leak here
    @SuppressLint("CheckResult")
    fun sendCollectedCrashLogs() {
        if (!createCrashLogsDirIfNotExists()) {
            return
        }

        // Collect and create reports on a background thread because logs may wait quite a lot now
        // and it may lag the UI.
        Completable.fromAction {
            BackgroundUtils.ensureBackgroundThread()

            val potentialCrashLogs = crashLogsDirPath.listFiles()
            if (potentialCrashLogs.isNullOrEmpty()) {
                Logger.d(TAG, "No new crash logs")
                return@fromAction
            }

            potentialCrashLogs.asSequence()
                    .filter { file -> file.name.startsWith(CRASH_LOG_FILE_NAME_PREFIX) }
                    .map { file -> createReportRequest(file) }
                    .filterNotNull()
                    .forEach { request -> crashLogSenderQueue.onNext(request) }
        }
                .subscribeOn(senderScheduler)
                .subscribe({
                    // Do nothing
                }, { error ->
                    Logger.e(TAG, "Error while collecting logs: ${error}")
                })
    }

    fun sendReport(title: String, description: String, logs: String?): Single<MResult<Boolean>> {
        require(title.isNotEmpty()) { "title is empty" }
        require(description.isNotEmpty() || logs != null) { "description is empty" }
        require(title.length <= MAX_TITLE_LENGTH) { "title is too long ${title.length}" }
        require(description.length <= MAX_DESCRIPTION_LENGTH) { "description is too long ${description.length}" }
        logs?.let { require(it.length <= MAX_LOGS_LENGTH) { "logs are too long" } }

        val request = ReportRequest(
                buildFlavor = BuildConfig.FLAVOR,
                versionName = BuildConfig.VERSION_NAME,
                osInfo = getOsInfo(),
                title = title,
                description = description,
                logs = logs
        )

        return sendInternal(request)
    }

    private fun getSettingsStateString(): String {
        return buildString {
            appendln("Prefetching enabled: ${ChanSettings.autoLoadThreadImages.get()}")
            appendln("Thread downloading enabled: ${ChanSettings.incrementalThreadDownloadingEnabled.get()}, " +
                    "active downloads = ${threadSaveManager.countActiveDownloads()}")
            appendln("Hi-res thumbnails enabled: ${ChanSettings.highResCells.get()}")
            appendln("Youtube titles parsing enabled: ${ChanSettings.parseYoutubeTitles.get()}")
            appendln("Youtube durations parsing enabled: ${ChanSettings.parseYoutubeDuration.get()}")
            appendln("Concurrent file loading chunks count: ${ChanSettings.concurrentDownloadChunkCount.get().toInt()}")
            appendln("WEBM streaming enabled: ${ChanSettings.videoStream.get()}")
            appendln("Saved files base dir info: ${getFilesLocationInfo()}")
            appendln("Local threads base dir info: ${getLocalThreadsLocationInfo()}")
            appendln("Phone layout mode: ${ChanSettings.layoutMode.get().name}")
        }
    }

    private fun getLocalThreadsLocationInfo(): String {
        val localThreadsActiveDirType = when {
            ChanSettings.localThreadLocation.isFileDirActive() -> "Java API"
            ChanSettings.localThreadLocation.isSafDirActive() -> "SAF"
            else -> "Neither of them is active, wtf?!"
        }

        return "Java API location: ${ChanSettings.localThreadLocation.fileApiBaseDir.get()}, " +
                "SAF location: ${ChanSettings.localThreadLocation.safBaseDir.get()}, " +
                "active: $localThreadsActiveDirType"
    }

    private fun getFilesLocationInfo(): String {
        val filesLocationActiveDirType = when {
            ChanSettings.saveLocation.isFileDirActive() -> "Java API"
            ChanSettings.saveLocation.isSafDirActive() -> "SAF"
            else -> "Neither of them is active, wtf?!"
        }

        return "Java API location: ${ChanSettings.saveLocation.fileApiBaseDir.get()}, " +
                "SAF location: ${ChanSettings.saveLocation.safBaseDir.get()}, " +
                "active: $filesLocationActiveDirType"
    }

    private fun createReportRequest(file: File): ReportRequestWithFile? {
        BackgroundUtils.ensureBackgroundThread()

        val log = try {
            file.readText()
        } catch (error: Throwable) {
            Logger.e(TAG, "Error reading crash log file", error)
            return null
        }

        val request = ReportRequest(
                buildFlavor = BuildConfig.FLAVOR,
                versionName = BuildConfig.VERSION_NAME,
                osInfo = getOsInfo(),
                title = "Crash report",
                description = "No title",
                logs = log
        )

        return ReportRequestWithFile(
                reportRequest = request,
                crashLogFile = file
        )
    }

    private fun getOsInfo(): String {
        return String.format(
                "Android %s, sdk version: %d",
                Build.VERSION.RELEASE,
                Build.VERSION.SDK_INT
        )
    }

    private fun createCrashLogsDirIfNotExists(): Boolean {
        if (!crashLogsDirPath.exists()) {
            if (!crashLogsDirPath.mkdir()) {
                Logger.e(TAG, "Couldn't create crash logs directory! " +
                        "path = ${crashLogsDirPath.absolutePath}")
                return false
            }
        }

        return true
    }

    private fun sendInternal(reportRequest: ReportRequest): Single<MResult<Boolean>> {
        return Single.create<MResult<Boolean>> { emitter ->
            BackgroundUtils.ensureBackgroundThread()

            val json = try {
                gson.toJson(reportRequest)
            } catch (error: Throwable) {
                Logger.e(TAG, "Couldn't convert $reportRequest to json", error)
                emitter.tryOnError(error)
                return@create
            }

            val requestBody = json.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                    .url(REPORT_URL)
                    .post(requestBody)
                    .build()

            okHttpClient.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    emitter.onSuccess(MResult.error(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    if (!response.isSuccessful) {
                        val message = "Response is not successful, status = ${response.code}"
                        Logger.e(TAG, message)

                        emitter.onSuccess(MResult.error(IOException(message)))
                        return
                    }

                    emitter.onSuccess(MResult.value(true))
                }
            })
        }.subscribeOn(senderScheduler)
    }

    data class ReportRequestWithFile(
            val reportRequest: ReportRequest,
            val crashLogFile: File
    )

    data class ReportRequest(
            @SerializedName("build_flavor")
            val buildFlavor: String,
            @SerializedName("version_name")
            val versionName: String,
            @SerializedName("os_info")
            val osInfo: String,
            @SerializedName("report_title")
            val title: String,
            @SerializedName("report_description")
            val description: String,
            @SerializedName("report_logs")
            val logs: String?
    )

    companion object {
        private const val TAG = "ReportManager"
        private const val SENDER_THREAD_NAME_FORMAT = "ReportSenderThread-%d"
        private const val REPORT_URL = "${BuildConfig.DEV_API_ENDPOINT}/report"
        private const val CRASH_LOG_FILE_NAME_PREFIX = "crashlog"
        private const val UNBOUNDED_QUEUE_MIN_SIZE = 32

        private const val CRASH_REPORT_LOGS_LINES_COUNT = 500

        const val MAX_TITLE_LENGTH = 512
        const val MAX_DESCRIPTION_LENGTH = 8192
        const val MAX_LOGS_LENGTH = 65535
    }

}