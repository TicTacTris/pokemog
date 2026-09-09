package dev.pokemog.android

import android.app.Activity
import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.drawable.RippleDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

/** A consent token belongs to this service instance and is never persisted or reused. */
class OverlayScanService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_PROJECTION_DATA = "projectionData"
        const val EXTRA_SESSION_ID = "sessionId"
        const val ACTION_STOP = "dev.pokemog.android.STOP"
        internal const val CHANNEL_ID = "overlay_scan"
        private const val NOTIFICATION_ID = 4107
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windows: WindowManager
    private lateinit var ui: Context
    private var repository: PokemonRepository? = null
    private val dataManager by lazy { PokemonDataManager.get(applicationContext) }
    private var autoDataVersion: Long? = null
    private var scanner: ScreenshotScanner? = null
    private var engineReady: Deferred<ScreenshotScanner>? = null
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var captureWidth = 0
    private var captureHeight = 0
    private var sourceWidth = 0
    private var sourceHeight = 0
    private var lowRam = false
    private var capturePaused = false
    private var root: View? = null
    private var panel: View? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private enum class Mode { ORB, RESULTS, MENU }
    private var mode = Mode.ORB
    private var menuReturn = Mode.ORB
    private var currentScanned: ScanResult? = null
    private var currentSummary: ScanSummary? = null
    private var hasDiagnostic = false
    private var shadowChecked = false
    private var busy = false
    private var errorMessage: String? = null
    private var detailsExpanded = false
    private var panelScroll: ScrollView? = null
    private var orbX = 0
    private var orbY = 100
    private var appliedInsets: OverlayInsets? = null
    private lateinit var appearance: SharedPreferences
    private val appearanceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        mainHandler.post {
            if (!stopped) {
                if (key == null || key == AutoScanSettings.KEY) {
                    val enabled = AutoScanSettings.enabled(this)
                    if (enabled != autoEnabled) {
                        autoEnabled = enabled
                        resetAuto(cancelAutomatic = true)
                        if (enabled) startAutoLoop() else { autoLoop?.cancel(); autoLoop = null }
                    }
                }
                if (root != null && (key == null || key == PokeMogAppearance.DARK_MODE || key == AutoScanSettings.KEY)) render()
            }
        }
    }
    private var hidden = false
    private var started = false
    private var stopped = false
    private var foreground = false
    private var sessionRequestId = -1L
    private var generation = 0
    private var work: Job? = null
    private var autoEnabled = false
    private var autoLoop: Job? = null
    private var autoProbe: AutoScanProbe? = null
    private var autoBaseline: AutoSample? = null
    private val autoTracker = AutoScanTracker()
    private var autoEpoch = 0L
    private var automaticRun = false
    private var acquiringFrame = false
    private var capturedContentVisible = true
    private var autoPauseUntil = 0L
    private var latestAutoSample: AutoSample? = null
    private var latestAutoSampleAt = 0L

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            finishSession("Screen capture ended. Start a new session from PokeMog.")
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            if (!stopped && width > 0 && height > 0) {
                try {
                    resizeCapture(width, height)
                } catch (_: Exception) {
                    finishSession("Unable to resize screen capture. Please start a new session.")
                }
            }
        }

        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            capturedContentVisible = isVisible
            if (!isVisible) pauseAutoRun()
        }
    }

    override fun onCreate() {
        super.onCreate()
        DiagnosticCache.start(this)
        windows = getSystemService(WindowManager::class.java)
        lowRam = getSystemService(ActivityManager::class.java).isLowRamDevice
        ui = ContextThemeWrapper(this, android.R.style.Theme_Material_Light_NoActionBar)
        appearance = PokeMogAppearance.preferences(this)
        autoEnabled = AutoScanSettings.enabled(this)
        appearance.registerOnSharedPreferenceChangeListener(appearanceListener)
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, "Screen scan session", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Visible controls while user-approved screen capture is active"
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            setShowBadge(false)
        })
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            finishSession()
            return START_NOT_STICKY
        }
        if (started || stopped) return START_NOT_STICKY
        started = true
        sessionRequestId = intent?.getLongExtra(EXTRA_SESSION_ID, -1L) ?: -1L
        try {
            // Android 14+ requires the typed foreground service before redeeming consent.
            startForeground(NOTIFICATION_ID, notification("Preparing capture controls"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            foreground = true
            if (!OverlaySession.isStarting(sessionRequestId) || OverlaySession.state.value.awaitingPermission) {
                finishSession()
                return START_NOT_STICKY
            }
            check(Settings.canDrawOverlays(this)) {
                "Allow PokeMog to display over other apps, then start a new scan session."
            }
            val code = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            val data = if (Build.VERSION.SDK_INT >= 33) {
                intent?.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra<Intent>(EXTRA_PROJECTION_DATA)
            }
            require(code == Activity.RESULT_OK && data != null) {
                "Screen capture consent was denied or missing. Start from PokeMog."
            }
            val manager = getSystemService(MediaProjectionManager::class.java)
            val session = checkNotNull(manager.getMediaProjection(code, data)) {
                "Screen capture is unavailable. Request fresh consent from PokeMog."
            }
            projection = session
            session.registerCallback(projectionCallback, mainHandler)
            val source = screenSize()
            sourceWidth = source.first
            sourceHeight = source.second
            val (width, height) = CaptureSizing.nativeSize(sourceWidth, sourceHeight, lowRam)
            reader = newReader(width, height)
            captureWidth = width
            captureHeight = height
            display = session.createVirtualDisplay("PokeMog tap capture", width, height,
                resources.displayMetrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader!!.surface, null, mainHandler)
            check(display != null) { "Unable to create the screen capture display." }
            status("Tap the scan icon. Long press for Stop. Captures are not saved or shared automatically.")
            render()
            if (!stopped && (display == null || root?.visibility != View.VISIBLE ||
                    !OverlaySession.running(sessionRequestId))) finishSession()
            if (!stopped) {
                prepareEngine()
                startAutoLoop()
            }
        } catch (e: Exception) {
            finishSession(if (e is SecurityException)
                "Screen capture permission is unavailable or expired. Grant permissions and request fresh consent in PokeMog."
            else e.message ?: "Unable to start screen capture. Please try again from PokeMog.")
        }
        return START_NOT_STICKY
    }

    private fun notification(status: String): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, OverlayScanService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("PokeMog")
            .setContentText(status)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Open PokeMog", open).build())
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification).setContentTitle("PokeMog")
                .setContentText("Open PokeMog for controls").setContentIntent(open)
                .addAction(Notification.Action.Builder(null, "Stop", stop).build()).build())
            .build()
    }

    private fun status(message: String) {
        if (foreground && !stopped) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message))
        }
    }

    private fun newReader(width: Int, height: Int): ImageReader =
        // All acquired Images are closed on main before another acquireLatestImage call.
        ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

    /** Prepare data/model while the user returns to the game, rather than on the first scan. */
    private fun prepareEngine(): Deferred<ScreenshotScanner> {
        engineReady?.takeUnless { it.isCancelled }?.let { return it }
        val pending = scope.async {
            withContext(Dispatchers.IO) { dataManager.snapshot(); dataManager.checkOnStartup() }
            scanner ?: ScreenshotScanner(applicationContext).also { scanner = it }
        }
        engineReady = pending
        scope.launch {
            try { pending.await().warmUp() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* A real scan can retry initialization. */ }
        }
        return pending
    }

    private fun resizeCapture(width: Int, height: Int, freshReader: Boolean = false) {
        if (width != sourceWidth || height != sourceHeight) resetAuto(cancelAutomatic = true)
        // Keep source bounds separate: repeated app-capture callbacks must not rescale our output.
        sourceWidth = width
        sourceHeight = height
        val (outputWidth, outputHeight) = CaptureSizing.nativeSize(width, height, lowRam)
        if (!freshReader && outputWidth == captureWidth && outputHeight == captureHeight) return
        val activeDisplay = display ?: return
        val replacement = newReader(outputWidth, outputHeight)
        try {
            activeDisplay.surface = null
            if (outputWidth != captureWidth || outputHeight != captureHeight) {
                activeDisplay.resize(outputWidth, outputHeight, resources.displayMetrics.densityDpi)
            }
            if (!capturePaused) activeDisplay.surface = replacement.surface
        } catch (e: Exception) {
            replacement.close()
            throw e
        }
        val old = reader
        reader = replacement
        captureWidth = outputWidth
        captureHeight = outputHeight
        old?.close()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (stopped) return
        appliedInsets = null
        try {
            // On 34+ content bounds may be a selected app, not the physical display.
            if (Build.VERSION.SDK_INT < 34) {
                val (width, height) = screenSize()
                resizeCapture(width, height)
            }
            if (root != null) render()
        } catch (_: Exception) {
            finishSession("Unable to update capture controls after rotation. Start a new session.")
        }
    }

    private fun resetAuto(cancelAutomatic: Boolean) {
        autoEpoch++
        autoProbe = null
        autoBaseline = null
        autoTracker.reset()
        latestAutoSample = null
        latestAutoSampleAt = 0
        if (cancelAutomatic && automaticRun) {
            automaticRun = false
            cancelWork()
            acquiringFrame = false
            if (mode != Mode.MENU) mode = Mode.ORB
        }
        AutoScanStatus.update(if (autoEnabled) "Scan once to enable automatic changes." else "Auto-scan is off.")
    }

    private fun refreshAutoDataVersion() {
        val version = dataManager.status.value.active?.version ?: return
        if (autoDataVersion != null && autoDataVersion != version) {
            resetAuto(cancelAutomatic = true)
            if (autoEnabled) AutoScanStatus.update("Pokemon data updated. Scan manually to recalibrate.")
        }
        autoDataVersion = version
    }

    private fun pauseAutoRun() {
        if (automaticRun) {
            automaticRun = false
            cancelWork()
            acquiringFrame = false
            autoTracker.abortAttempt()
            if (mode != Mode.MENU) mode = Mode.ORB
            render()
        }
        latestAutoSample = null
        latestAutoSampleAt = 0
        autoTracker.observe(null, SystemClock.elapsedRealtime())
        if (autoEnabled) AutoScanStatus.update("Auto-scan paused.")
    }

    private fun autoCanObserve(): Boolean = !stopped && foreground && projection != null && autoEnabled &&
        capturedContentVisible && mode != Mode.MENU && SystemClock.elapsedRealtime() >= autoPauseUntil &&
        getSystemService(PowerManager::class.java).isInteractive && !getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun startAutoLoop() {
        if (!autoEnabled || stopped || !foreground || autoLoop?.isActive == true) return
        AutoScanStatus.update("Scan once to enable automatic changes.")
        autoLoop = scope.launch {
            while (isActive && !stopped && autoEnabled) {
                delay(250)
                refreshAutoDataVersion()
                val probe = autoProbe ?: continue
                if (acquiringFrame || capturePaused || hidden) continue
                if (!autoCanObserve()) { pauseAutoRun(); continue }
                val epoch = autoEpoch
                val scanGeneration = generation
                val observedAt = SystemClock.elapsedRealtime()
                try {
                    val image = reader?.acquireLatestImage() ?: continue
                    val snapshot = image.use {
                        // During a manual scan/Shadow update, drain the stream but do not inspect it.
                        if (busy && !automaticRun) null else captureAutoProbe(image, probe)
                    }
                    if (busy && !automaticRun) { autoTracker.observe(null, observedAt); continue }
                    val sample = snapshot?.let { withContext(Dispatchers.Default) { probe.analyze(it) } }
                    if (epoch != autoEpoch || probe !== autoProbe || scanGeneration != generation || !autoCanObserve()) continue
                    latestAutoSample = sample
                    latestAutoSampleAt = observedAt
                    if (automaticRun) continue // The in-flight scan validates this evidence before publishing.
                    AutoScanStatus.update(if (sample == null) "Waiting for a visible appraisal." else "Watching for appraisal changes.")
                    if (autoTracker.observe(sample, observedAt)) scan(automatic = true)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: OutOfMemoryError) {
                    resetAuto(cancelAutomatic = true)
                    AutoScanStatus.update("Auto-scan paused for memory. Scan manually to resume.")
                }
                catch (_: Exception) {
                    latestAutoSample = null
                    latestAutoSampleAt = observedAt
                    autoTracker.observe(null, observedAt)
                    AutoScanStatus.update("Waiting for a readable appraisal.")
                }
            }
        }
    }

    /** Copies only bounded probe pixels while Image ownership is valid; analysis runs off-main. */
    private fun captureAutoProbe(image: Image, probe: AutoScanProbe): AutoProbeSnapshot? {
        require(image.format == PixelFormat.RGBA_8888)
        val crop = image.cropRect
        require(crop.left >= 0 && crop.top >= 0 && crop.right <= image.width && crop.bottom <= image.height)
        val plane = image.planes[0]
        val buffer = plane.buffer.duplicate()
        val stride = plane.pixelStride
        val rowStride = plane.rowStride
        require(stride >= 4 && rowStride > 0 && crop.width() > 0 && crop.height() > 0)
        val last = (crop.bottom - 1L) * rowStride + (crop.right - 1L) * stride + 4
        require(last <= buffer.limit())
        return probe.capture(crop.width(), crop.height()) { x, y ->
            val offset = (crop.top + y) * rowStride + (crop.left + x) * stride
            if ((buffer.get(offset + 3).toInt() and 255) < 240) 0 else {
                (255 shl 24) or ((buffer.get(offset).toInt() and 255) shl 16) or
                    ((buffer.get(offset + 1).toInt() and 255) shl 8) or (buffer.get(offset + 2).toInt() and 255)
            }
        }
    }

    private fun sameProfile(before: ScanResult?, previous: ScanSummary?, after: ScanResult, next: ScanSummary): Boolean =
        previous != null && ((previous.formUnresolved && next.formUnresolved &&
            previous.unresolvedCandidateIdentities.map { it.id } == next.unresolvedCandidateIdentities.map { it.id }) ||
            (previous.pokemon != null && next.pokemon != null &&
                (previous.pokemon.normalId ?: previous.pokemon.id) == (next.pokemon.normalId ?: next.pokemon.id))) &&
            before?.candidates?.toSet() == after.candidates.toSet() &&
            before?.ivs == after.ivs && before?.cp == after.cp && before?.hp == after.hp

    private class AutoFrameChanged : CancellationException("Appraisal changed during recognition")

    /** A static shared app may emit no post-OCR frame. Request one without reusing a capture token. */
    private suspend fun freshAutoObservation(probe: AutoScanProbe, ownerGeneration: Int): AutoSample? {
        if (ownerGeneration != generation || !autoCanObserve()) return null
        acquiringFrame = true
        try {
            reader?.acquireLatestImage()?.close()
            capturePaused = true
            checkNotNull(display).surface = null
            hidden = true
            root?.visibility = View.INVISIBLE
            delay(250)
            if (ownerGeneration != generation || !autoCanObserve()) return null
            capturePaused = false
            resizeCapture(sourceWidth, sourceHeight, freshReader = true)
            var obtained = false
            var snapshot: AutoProbeSnapshot? = null
            var observedAt = 0L
            withTimeout(1000) {
                while (!obtained) {
                    coroutineContext.ensureActive()
                    reader?.acquireLatestImage()?.use { image ->
                        obtained = true
                        observedAt = SystemClock.elapsedRealtime()
                        snapshot = captureAutoProbe(image, probe)
                    }
                    if (!obtained) delay(32)
                }
            }
            val sample = snapshot?.let { withContext(Dispatchers.Default) { probe.analyze(it) } }
            if (ownerGeneration != generation || stopped) return null
            latestAutoSample = sample
            latestAutoSampleAt = observedAt
            return sample
        } catch (_: TimeoutCancellationException) {
            if (ownerGeneration == generation && !stopped) { latestAutoSample = null; latestAutoSampleAt = 0 }
            return null
        } finally {
            if (ownerGeneration == generation && !stopped) {
                acquiringFrame = false
                restoreOverlay()
            }
        }
    }

    private fun scan(automatic: Boolean = false) {
        if (stopped) return
        refreshAutoDataVersion()
        if (automatic && (!autoCanObserve() || autoProbe == null || busy)) { autoTracker.completeAttempt(); return }
        val previousScan = currentScanned
        val previousSummary = currentSummary
        val previousRepository = repository
        val previousShadow = shadowChecked
        val previousMode = mode
        if (!automatic) resetAuto(cancelAutomatic = false)
        val probeForAttempt = autoProbe
        val probeEpoch = autoEpoch
        val runStarted = System.nanoTime()
        cancelWork()
        automaticRun = automatic
        acquiringFrame = true
        val ticket = generation
        currentScanned = null
        currentSummary = null
        repository = null
        OverlayResultData.update(null)
        hasDiagnostic = false
        CaptureDiagnostics.clear()
        shadowChecked = false
        errorMessage = null
        detailsExpanded = false
        busy = true
        mode = Mode.ORB
        render(false)
        status(if (captureNotificationEnabled(this)) "Capturing one frame. Use notification Stop, PokeMog, or long press the Pokeball."
            else "Capturing one frame. Stop from PokeMog or long press the Pokeball; notifications are disabled.")
        work = scope.launch {
            var performance: ScanPerformance? = null
            var calibratedProbe: AutoScanProbe? = null
            var calibratedBaseline: AutoSample? = null
            var autoSucceeded = false
            var attemptVersion: Long? = null
            var failureMessage = "Unable to acquire a capture frame. Keep the shared app visible and rescan, or request new consent."
            try {
                val snapshot = withTimeout(4000) {
                    reader?.acquireLatestImage()?.close()
                    capturePaused = true
                    checkNotNull(display) { "Capture has ended." }.surface = null
                    hidden = true
                    root?.visibility = View.INVISIBLE
                    delay(250)
                    // A new queue attached AFTER hiding forces composition even for a static app.
                    // No image in this queue predates hiding; surface timestamps may reset, so
                    // neither a wall clock nor timestamps from the old surface establish freshness.
                    capturePaused = false
                    resizeCapture(sourceWidth, sourceHeight, freshReader = true)
                    var captured: FrameSnapshot? = null
                    while (captured == null) {
                        coroutineContext.ensureActive()
                        val source = checkNotNull(reader) { "Capture has ended." }
                        source.acquireLatestImage()?.use { image ->
                            failureMessage = "The captured frame could not be copied or converted. Rescan, or stop and request new consent."
                            captured = snapshotFrame(image)
                        }
                        if (captured == null) delay(32)
                    }
                    checkNotNull(captured)
                }
                acquiringFrame = false
                val capturedAt = SystemClock.elapsedRealtime()
                val captureMs = elapsedMs(runStarted)
                restoreOverlay()
                status("Reading requested frame on device. Tap the icon to cancel.")
                failureMessage = "On-device OCR could not read the capture. Rescan with the name and appraisal bars visible."
                val engine = prepareEngine().await()
                val repo = dataManager.snapshot()
                attemptVersion = repo.identity.version
                if (autoDataVersion == null) autoDataVersion = repo.identity.version
                withContext(Dispatchers.Default) {
                    val workerContext = coroutineContext
                    failureMessage = "The captured frame could not be converted. Rescan, or stop and request new consent."
                    val conversionStarted = System.nanoTime()
                    val frame = convertFrame(snapshot.pixels)
                    val conversionMs = elapsedMs(conversionStarted)
                    var diagnostic: CaptureDiagnostic? = null
                    try {
                        failureMessage = "On-device OCR failed to read the capture. Rescan with the name and appraisal bars visible."
                        var summary: ScanSummary? = null
                        var scannedEvidence: AutoSample? = null
                        var assessmentMs = 0L
                        val result = engine.scan(frame, onAnalyzed = { parsed, analyzed ->
                            workerContext.ensureActive()
                            failureMessage = "The scan was read, but its league assessment failed. Rescan and try again."
                            val assessmentStarted = System.nanoTime()
                            val assessment = ScanAssessments.calculate(repo, parsed, false)
                            assessmentMs = elapsedMs(assessmentStarted)
                            workerContext.ensureActive()
                            summary = assessment
                            if (autoEnabled && probeEpoch == autoEpoch) {
                                val candidate = AutoScanProbe.create(parsed, assessment)
                                val baseline = candidate?.calibrate(frame.width, frame.height, frame::getPixel)
                                if (baseline != null) { calibratedProbe = candidate; calibratedBaseline = baseline }
                                if (automatic) {
                                    scannedEvidence = probeForAttempt?.sample(frame.width, frame.height, frame::getPixel)
                                    if (calibratedProbe == null && scannedEvidence?.ivs == parsed.ivs && parsed.ivs != null && assessment.leagues.isNotEmpty()) {
                                        calibratedProbe = probeForAttempt
                                        calibratedBaseline = scannedEvidence
                                    }
                                }
                            }
                            if (assessment.leagues.isEmpty() || parsed.cp == null || parsed.hp == null || assessment.effectiveLevels.isEmpty()) {
                                try {
                                    diagnostic = CaptureDiagnostic.snapshot(frame, analyzed, snapshot.metadata +
                                        "\nbarResult=${parsed.ivs ?: "unreadable"}; cp=${parsed.cp ?: "unreadable"}; maxHP=${parsed.hp ?: "unreadable"}; candidates=${parsed.candidates.joinToString()}" +
                                        "\nperformance: capture=${captureMs}ms; conversion=${conversionMs}ms; assessment=${assessmentMs}ms; ${parsed.timings}\nrecognizedText:\n${parsed.text}")
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: OutOfMemoryError) {
                                    summary = assessment.copy(message = assessment.message + " Diagnostic images could not be retained due to memory limits.")
                                } catch (_: Exception) {
                                    summary = assessment.copy(message = assessment.message + " Diagnostic images could not be retained.")
                                }
                            }
                            workerContext.ensureActive()
                            failureMessage = "On-device OCR failed to complete the capture. Rescan and try again."
                        }, onFailure = { analyzed, cause ->
                            workerContext.ensureActive()
                            if (diagnostic == null) {
                                try {
                                    diagnostic = CaptureDiagnostic.snapshot(frame, analyzed, snapshot.metadata +
                                        "\nfailedStage=$failureMessage\nexception=${cause.javaClass.simpleName}")
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: OutOfMemoryError) { /* Diagnostics must not worsen a memory failure. */ }
                                catch (_: Exception) { /* Keep the original recognition error. */ }
                            }
                        }, collectAutoAnchors = autoEnabled, repository = repo)
                        val assessment = checkNotNull(summary) { "Scanner did not provide its analyzed image callback" }
                        // Keep ownership on the worker until main accepts it. A cancelled dispatcher
                        // return must never drop owned bitmaps or publish diagnostics from an old scan.
                        withContext(Dispatchers.Main.immediate) {
                            coroutineContext.ensureActive()
                            if (automatic) {
                                withTimeoutOrNull(450) {
                                    while (latestAutoSampleAt < capturedAt && ticket == generation && probeEpoch == autoEpoch && autoCanObserve()) delay(40)
                                }
                                if (!freshAutoEvidence(latestAutoSampleAt, capturedAt, SystemClock.elapsedRealtime()) &&
                                    ticket == generation && probeEpoch == autoEpoch && autoCanObserve()) {
                                    freshAutoObservation(checkNotNull(probeForAttempt), ticket)
                                }
                                val stillCurrent = repo.identity.version == dataManager.status.value.active?.version &&
                                    ticket == generation && probeEpoch == autoEpoch && autoCanObserve() &&
                                    freshAutoEvidence(latestAutoSampleAt, capturedAt, SystemClock.elapsedRealtime()) &&
                                    autoTracker.pendingMatches(scannedEvidence) && autoTracker.pendingMatches(latestAutoSample)
                                if (!stillCurrent) throw AutoFrameChanged()
                            }
                            if (ticket == generation && !stopped) {
                                CaptureDiagnostics.set(diagnostic)
                                hasDiagnostic = diagnostic != null
                                diagnostic = null
                                currentScanned = result
                                val unchanged = automatic && previousRepository?.identity == repo.identity && sameProfile(previousScan, previousSummary, result, assessment)
                                repository = if (unchanged) previousRepository else repo
                                OverlayResultData.update(repository?.identity)
                                currentSummary = if (unchanged) previousSummary else assessment
                                if (unchanged) shadowChecked = previousShadow
                                performance = ScanPerformance("Overlay", captureMs, conversionMs, assessmentMs,
                                    elapsedMs(runStarted), result.timings)
                                val showMode = if (automatic && assessment.leagues.isEmpty()) Mode.ORB else if (unchanged) previousMode else Mode.RESULTS
                                if (mode == Mode.MENU) menuReturn = showMode else mode = showMode
                                autoSucceeded = calibratedProbe != null && calibratedBaseline != null
                                status("Scanned estimate ready. Open overlay controls or Stop.")
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        withContext(Dispatchers.Main.immediate) {
                            coroutineContext.ensureActive()
                            if (ticket == generation && !stopped && diagnostic != null) {
                                CaptureDiagnostics.set(diagnostic)
                                hasDiagnostic = true
                                diagnostic = null
                            }
                        }
                        throw error
                    } finally {
                        diagnostic?.release()
                        frame.recycle()
                    }
                }
            } catch (_: AutoFrameChanged) {
                if (ticket == generation && !stopped) {
                    autoTracker.abortAttempt()
                    mode = Mode.ORB
                    AutoScanStatus.update("Waiting for a fresh, settled appraisal.")
                }
            } catch (_: TimeoutCancellationException) {
                if (ticket == generation && !stopped) {
                    scanFailure("No fresh frame arrived. Keep the shared app visible and rescan. Protected content cannot be captured.")
                    if (automatic) mode = Mode.ORB
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: OutOfMemoryError) {
                if (ticket == generation && !stopped) {
                    scanFailure("Not enough memory to process this capture. Close other apps, then rescan.")
                    if (automatic) mode = Mode.ORB
                }
            } catch (_: Exception) {
                if (ticket == generation && !stopped) {
                    scanFailure(failureMessage)
                    if (automatic) mode = Mode.ORB
                }
            } finally {
                if (ticket == generation) {
                    acquiringFrame = false
                    automaticRun = false
                    if (attemptVersion != null && attemptVersion != dataManager.status.value.active?.version) {
                        resetAuto(cancelAutomatic = false)
                        if (autoEnabled) AutoScanStatus.update("Pokemon data updated. Scan manually to recalibrate.")
                    }
                    if (probeEpoch == autoEpoch && autoEnabled) {
                        if (automatic) autoTracker.completeAttempt(if (autoSucceeded) calibratedBaseline else null)
                        if (autoSucceeded) {
                            autoProbe = calibratedProbe
                            autoBaseline = calibratedBaseline
                            if (!automatic) autoTracker.seed(calibratedBaseline, SystemClock.elapsedRealtime())
                            latestAutoSample = null; latestAutoSampleAt = 0
                            AutoScanStatus.update("Watching for appraisal changes.")
                        } else if (!automatic || currentSummary?.leagues?.isNotEmpty() == true) {
                            autoProbe = null; autoBaseline = null
                            autoTracker.seed(null, SystemClock.elapsedRealtime())
                            AutoScanStatus.update("Scan a clear appraisal to enable automatic changes.")
                        }
                    }
                    restoreOverlay()
                    work = null
                    busy = false
                    val renderStarted = System.nanoTime()
                    render(false)
                    performance?.let { RecentScanPerformance.record(it.copy(readyMs = elapsedMs(runStarted), renderMs = elapsedMs(renderStarted))) }
                }
            }
        }
    }

    private data class FrameSnapshot(
        val pixels: CaptureFramePixels, val metadata: String,
    )

    /** Main-thread bulk copy only. No Image/reader/buffer escapes into the worker. */
    private fun snapshotFrame(image: Image): FrameSnapshot {
        val crop = image.cropRect
        require(image.format == PixelFormat.RGBA_8888) { "Capture is not RGBA_8888" }
        require(CaptureSizing.nativeSize(image.width, image.height, lowRam) == PixelSize(image.width, image.height)) {
            "Capture exceeds device buffer limits"
        }
        val plane = image.planes[0]
        val pixels = CaptureFramePixels.snapshot(plane.buffer, image.width, image.height,
            plane.pixelStride, plane.rowStride, crop.left, crop.top, crop.width(), crop.height())
        val metadata = buildString {
            appendLine("PokeMog overlay capture / attempt=1")
            appendLine("source=${sourceWidth}x$sourceHeight; output=${captureWidth}x$captureHeight")
            appendLine("buffer=${image.width}x${image.height}; crop=${crop.width()}x${crop.height()} at ${crop.left},${crop.top}")
            appendLine("pixelStride=${plane.pixelStride}; rowStride=${plane.rowStride}; bufferLimit=${plane.buffer.limit()}")
            appendLine("frame.timestamp=${image.timestamp}; sourceFormat=${image.format} (RGBA_8888)")
            if (Build.VERSION.SDK_INT >= 33) appendLine("sourceDataSpace=${image.dataSpace}")
            append("densityDpi=${resources.displayMetrics.densityDpi}; lowRAM=$lowRam")
        }
        return FrameSnapshot(pixels, metadata)
    }

    private suspend fun convertFrame(frame: CaptureFramePixels): Bitmap {
        coroutineContext.ensureActive()
        val bitmap = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
        try {
            val row = IntArray(frame.width)
            for (y in 0 until frame.height) {
                coroutineContext.ensureActive()
                frame.readArgbRow(y, row)
                bitmap.setPixels(row, 0, row.size, 0, y, row.size, 1)
            }
            return bitmap
        } catch (e: Throwable) {
            bitmap.recycle()
            throw e
        }
    }

    private fun restoreOverlay() {
        if (capturePaused) {
            capturePaused = false
            if (!stopped) {
                try { display?.surface = reader?.surface }
                catch (_: Exception) { finishSession("Unable to resume capture. Start a new session.") }
            }
        }
        hidden = false
        if (!stopped) root?.visibility = View.VISIBLE
    }

    private fun cancelWork() {
        val wasBusy = busy
        if (automaticRun) {
            automaticRun = false
            autoTracker.completeAttempt()
            latestAutoSample = null
            latestAutoSampleAt = 0
        }
        acquiringFrame = false
        generation++
        work?.cancel()
        work = null
        busy = false
        currentSummary?.let { shadowChecked = it.shadow }
        restoreOverlay()
        if (wasBusy) status("Scan cancelled. Tap the icon to rescan; long press for Stop.")
    }

    private fun scanFailure(message: String) {
        errorMessage = message
        if (mode == Mode.MENU) menuReturn = Mode.RESULTS else mode = Mode.RESULTS
        status("Scan needs attention. Rescan from the overlay or Stop.")
    }

    private fun changeShadow(checked: Boolean) {
        if (busy || currentSummary?.canToggleShadow != true || checked == shadowChecked) return
        val scan = currentScanned ?: return
        val repo = repository ?: return
        cancelWork()
        val ticket = generation
        shadowChecked = checked
        errorMessage = null
        busy = true
        render()
        status("Updating the scanned estimate on device")
        work = scope.launch {
            try {
                val summary = withContext(Dispatchers.Default) { ScanAssessments.calculate(repo, scan, checked) }
                if (ticket == generation && !stopped) {
                    currentSummary = summary
                    if (summary.leagues.isNotEmpty() && scan.cp != null && scan.hp != null && summary.effectiveLevels.isNotEmpty()) {
                        hasDiagnostic = false
                        CaptureDiagnostics.clear()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (ticket == generation && !stopped) {
                    shadowChecked = currentSummary?.shadow ?: false
                    errorMessage = "Unable to assess this form. Change Shadow or rescan."
                }
            } finally {
                if (ticket == generation && !stopped) {
                    busy = false
                    work = null
                    status("Scanned estimate ready. Open overlay controls or Stop.")
                    render()
                }
            }
        }
    }

    private fun openMenu() {
        menuReturn = mode
        mode = Mode.MENU
        if (autoEnabled) AutoScanStatus.update("Auto-scan paused while controls are open.")
        render(false)
    }

    private fun collapse() {
        mode = Mode.ORB
        render(false)
    }

    private fun shape(color: Int) = retroBackground(color, PokeMogAppearance.palette(this).outline,
        resources.displayMetrics.density)

    private fun label(value: String, size: Float = 14f, color: Int = PokeMogAppearance.palette(this).text,
        bold: Boolean = false) = TextView(ui).apply {
        text = value
        textSize = size
        setTextColor(color)
        typeface = retroTypeface(ui)
        setPadding(0, dp(3), 0, dp(3))
        setLineSpacing(dp(2).toFloat(), 1f)
    }

    private fun button(value: String, primary: Boolean = false, action: () -> Unit): Button {
        val p = PokeMogAppearance.palette(this)
        return Button(ui).apply {
            text = value
            textSize = 14f
            isAllCaps = false
            setSingleLine(false)
            typeface = retroTypeface(ui)
            setTextColor(if (primary) p.onPrimary else p.primary)
            background = RippleDrawable(ColorStateList.valueOf(p.outline),
                retroBackground(if (primary) p.primary else p.elevated,
                    if (primary) p.onPrimary else p.outline, resources.displayMetrics.density), null)
            backgroundTintList = null
            stateListAnimator = null
            minimumWidth = 0
            minWidth = 0
            minimumHeight = dp(48)
            minHeight = dp(48)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { if (!stopped) action() }
        }
    }

    private fun column() = LinearLayout(ui).apply { orientation = LinearLayout.VERTICAL }

    private fun statsRows(target: LinearLayout, stats: Stats, level: Double, shadow: Boolean, current: Boolean = false) {
        val p = PokeMogAppearance.palette(this)
        val row = LinearLayout(ui).apply { orientation = LinearLayout.HORIZONTAL }
        if (current) target.addView(label("Effective level ${levelNumber(level)}", 12f, p.muted))
        val values = if (current) listOf("CP ${stats.cp}", "HP ${stats.hp}")
            else listOf("CP ${stats.cp}", "Lv ${fmt(level)}", "HP ${stats.hp}")
        for (value in values) {
            row.addView(label(value, 13f), LinearLayout.LayoutParams(0, -2, 1f))
        }
        target.addView(row)
        target.addView(label("ATK ${fmt(stats.attack)}   DEF ${fmt(stats.defense)}", 13f, p.muted))
        if (shadow) target.addView(label("Shadow ATK equivalent ${fmt(stats.attack * 1.2)}", 12f, p.gold))
    }

    private fun leagueCard(league: ScanLeague, entry: EvolutionProjection): View {
        val p = PokeMogAppearance.palette(this)
        return column().apply {
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = shape(p.elevated)
            val heading = LinearLayout(ui).apply { gravity = Gravity.CENTER }
            heading.addView(ImageView(ui).apply {
                setImageResource(if (league.cpCap == 1500) R.drawable.ic_league_great else R.drawable.ic_league_ultra)
                contentDescription = "${league.title}, ${league.cpCap} CP limit"
            }, LinearLayout.LayoutParams(dp(28), dp(28)))
            heading.addView(label("${if (league.cpCap == 1500) "GL" else "UL"} / ${league.cpCap}", 12f, p.muted, true).apply {
                setPadding(dp(5), 0, 0, 0)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            addView(heading)
            val optimum = entry.optimal
            val percentile = if (optimum != null && entry.eligibleSpreads > 0) entry.ivPercentile else null
            addView(LeagueArcView(ui, p, percentile, if (league.cpCap == 1500) 0xFF6CA9E8.toInt() else p.gold),
                LinearLayout.LayoutParams(-1, -2))
            shortFeasibility(entry.feasibility)?.takeUnless { it == "Not eligible" }?.let {
                addView(label(it, 12f, p.error).apply { gravity = Gravity.CENTER })
            }
        }
    }

    private fun details(content: LinearLayout, summary: ScanSummary) {
        val p = PokeMogAppearance.palette(this)
        content.addView(label("PvP IV percentile compares eligible IV spreads of this form, not win chance. Percentage of best compares stat products.", 12f, p.muted))
        content.addView(label("Current CP ${currentScanned?.cp ?: "--"} / HP ${currentScanned?.hp ?: "--"}", 12f, p.muted))
        content.addView(label("Effective level ${effectiveLevelLabel(summary)}", 12f, p.muted))
        summary.candidateLabel?.let { content.addView(label(it, 12f, p.muted)) }
        for (league in summary.leagues) {
            val entries = league.assessment.evolutions
            if (entries.isEmpty()) continue
            detailHeader(content, "${league.title} / League optimum")
            for (entry in entries) {
                val group = detailGroup(content, entry.pokemon.name)
                if (summary.shadow && entry.pokemon.id in summary.unverifiedShadowIds) {
                    group.addView(label("Shadow availability unverified", 12f, p.gold))
                }
                val optimum = entry.optimal
                if (optimum == null) group.addView(label("Not eligible", 13f, p.muted))
                else {
                    val percentile = entry.ivPercentile?.let { "${percent(it)}% percentile / " }.orEmpty()
                    group.addView(label("${percentile}Rank #${optimum.rank} / ${percent(entry.percentBest)}% stat product", 12f, p.muted))
                    statsRows(group, optimum.stats, optimum.level, summary.shadow)
                }
                shortFeasibility(entry.feasibility)?.let { group.addView(label(it, 12f, p.muted)) }
            }
        }
        detailHeader(content, "Evolution & maximum")
        for (entry in summary.leagues.firstOrNull()?.assessment?.evolutions.orEmpty()) {
            val group = detailGroup(content, entry.pokemon.name)
            if (summary.shadow && entry.pokemon.id in summary.unverifiedShadowIds) {
                group.addView(label("Shadow availability unverified", 12f, p.gold))
            }
            detailHeader(group, if (summary.isCurrent(entry.pokemon)) "Current stats" else "After evolution, no power-ups")
            val current = entry.current
            if (current.isEmpty()) group.addView(label("Current level unknown", 12f, p.muted))
            else if (current.size == 1) {
                statsRows(group, current.single().second, current.single().first, summary.shadow, current = true)
            } else {
                val first = current.first()
                val last = current.last()
                group.addView(label("Effective level: ${current.joinToString(", ") { levelNumber(it.first) }}", 12f, p.muted))
                group.addView(label("CP ${first.second.cp}-${last.second.cp} / HP ${first.second.hp}-${last.second.hp}", 12f))
                group.addView(label("ATK ${fmt(first.second.attack)}-${fmt(last.second.attack)} / DEF ${fmt(first.second.defense)}-${fmt(last.second.defense)}", 12f))
                if (summary.shadow) group.addView(label("Shadow ATK equivalent ${fmt(first.second.attack * 1.2)}-${fmt(last.second.attack * 1.2)}", 12f, p.gold))
            }
            for (alternative in levelAlternatives(summary.leagues.first().assessment.levelScenarios)) {
                group.addView(label(alternative, 12f, p.muted))
            }
            detailHeader(group, "Fully powered up / Effective level ${levelNumber(entry.maximumLevel)}")
            statsRows(group, entry.maximum, entry.maximumLevel, summary.shadow)
        }
    }

    private fun detailHeader(parent: LinearLayout, title: String) {
        val p = PokeMogAppearance.palette(this)
        parent.addView(label(title, 14f, p.gold, true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        parent.addView(View(ui).apply { setBackgroundColor(p.outline) }, LinearLayout.LayoutParams(-1, dp(1)).apply { bottomMargin = dp(6) })
    }

    private fun detailGroup(parent: LinearLayout, title: String): LinearLayout {
        val p = PokeMogAppearance.palette(this)
        val group = column().apply {
            setPadding(dp(10), dp(6), dp(10), dp(10))
            background = shape(p.surface)
        }
        parent.addView(group, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        detailHeader(group, title)
        return group
    }

    /** Rendering reads state only: theme/configuration changes never restart capture or assessment. */
    private fun render(preserveScroll: Boolean = true) {
        if (stopped) return
        val scrollY = if (preserveScroll) panelScroll?.scrollY ?: 0 else 0
        removeOverlay()
        val p = PokeMogAppearance.palette(this)
        val usable = usableScreen()
        val orbBounds = OverlayGeometry.orb(usable, orbX, orbY, dp(56))
        val params = windowLayout(orbBounds.width, orbBounds.height).apply {
            x = orbBounds.left
            y = orbBounds.top
        }
        orbX = params.x
        orbY = params.y
        var initialX = 0
        var initialY = 0
        val orb = ScanOrbView(ui, p, busy, beginDrag = {
            autoPauseUntil = SystemClock.elapsedRealtime() + 800
            initialX = params.x
            initialY = params.y
        }, move = { dx, dy, finished ->
            autoPauseUntil = SystemClock.elapsedRealtime() + 800
            if (!stopped && windowParams === params) {
                val position = OverlayGeometry.orb(usableScreen(), initialX + dx.toInt(), initialY + dy.toInt(), dp(56), finished)
                params.x = position.left
                params.y = position.top
                orbX = params.x
                orbY = params.y
                root?.let { current ->
                    try { windows.updateViewLayout(current, params) }
                    catch (_: Exception) { finishSession("Overlay controls are no longer available.") }
                }
            }
        }).apply {
            setOnClickListener {
                if (this@OverlayScanService.busy) {
                    cancelWork()
                    render()
                } else scan()
            }
            setOnLongClickListener { if (mode != Mode.MENU) openMenu(); true }
        }
        root = orb
        windowParams = params
        orb.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        orb.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val safe = safeInsets(windows.maximumWindowMetrics.windowInsets)
                if (appliedInsets != safe) {
                    appliedInsets = safe
                    mainHandler.post { if (!stopped && root === view) render() }
                }
            }
            insets
        }
        var cardParams: WindowManager.LayoutParams? = null
        if (mode != Mode.ORB) {
            val limit = OverlayGeometry.panel(usable, dp(if (mode == Mode.MENU) 280 else 380), Int.MAX_VALUE, dp(8))
            val card = OverlayCardLayout(ui, limit.height).apply {
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = shape(p.surface)
            }
            val content = column()
            val scroll = ScrollView(ui).apply {
                isFillViewport = false
                isVerticalScrollBarEnabled = false
                addView(content)
            }
            panelScroll = scroll
            card.addView(scroll)
            val footer = OverlayActionFooter(ui, resources.displayMetrics.density)
            if (mode == Mode.MENU) {
                content.addView(label("Controls", 18f, p.text, true))
                if (autoEnabled) content.addView(label(AutoScanStatus.state.value, 12f, p.muted))
                if (currentSummary != null || errorMessage != null) content.addView(button("Last result") {
                    mode = Mode.RESULTS
                    render(false)
                })
                footer.addView(button("Stop", true) { finishSession() })
                footer.addView(button("Back") { mode = menuReturn; render(false) })
            } else {
                val summary = currentSummary
                val stackHeading = resources.configuration.fontScale > 1.3f || limit.width < dp(360)
                val heading = LinearLayout(ui).apply {
                    orientation = if (stackHeading) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val identity = column()
                identity.addView(label(if (errorMessage != null) "Rescan needed"
                    else summary?.displayName ?: "Rescan needed", 20f, p.text, true))
                summary?.formLabel?.let { identity.addView(label(it, 12f, p.muted)) }
                val ivs = summary?.ivs
                if (ivs != null) identity.addView(label("${ivs.attack} / ${ivs.defense} / ${ivs.stamina} IVs", 15f, p.gold, true))
                if (summary?.shadow == true && summary.leagues.firstOrNull()?.assessment?.evolutions?.firstOrNull()?.pokemon?.id in summary.unverifiedShadowIds) {
                    identity.addView(label("Shadow availability unverified", 12f, p.gold))
                }
                heading.addView(identity, if (stackHeading) LinearLayout.LayoutParams(-1, -2)
                    else LinearLayout.LayoutParams(0, -2, 1f))
                if (summary != null) heading.addView(RetroShadowButton(ui, shadowChecked,
                    !busy && summary.canToggleShadow, p) { checked -> changeShadow(checked) })
                content.addView(heading)
                val message = when {
                    busy -> "Updating..."
                    errorMessage != null -> when {
                        errorMessage!!.contains("fresh frame", true) -> "Capture timed out. Rescan."
                        errorMessage!!.contains("memory", true) -> "Low memory. Rescan."
                        errorMessage!!.contains("convert", true) || errorMessage!!.contains("copied", true) -> "Capture pixels unavailable. Rescan."
                        errorMessage!!.contains("OCR", true) -> "Text unreadable. Rescan."
                        else -> "Scan failed. Rescan."
                    }
                    summary == null -> ""
                    summary.leagues.isEmpty() -> shortScanIssue(summary)
                    else -> ""
                }
                if (message.isNotBlank()) content.addView(label(message, 12f, if (errorMessage != null) p.error else p.muted))
                if (!busy && hasDiagnostic) {
                    content.addView(button("Inspect failed capture") {
                        try {
                            CaptureDiagnostics.inspect(this@OverlayScanService)
                            collapse()
                        } catch (_: Exception) {
                            Toast.makeText(this@OverlayScanService, "Unable to open the failed capture preview.", Toast.LENGTH_LONG).show()
                        }
                    })
                }
                if (errorMessage == null && summary != null) {
                    val sideBySide = OverlayGeometry.sideBySide(limit.width - dp(24),
                        resources.displayMetrics.density, resources.configuration.fontScale)
                    val metrics = LinearLayout(ui).apply {
                        orientation = if (sideBySide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                        isBaselineAligned = false
                    }
                    for (league in summary.leagues.filter { it.cpCap == 1500 || it.cpCap == 2500 }) {
                        league.assessment.evolutions.firstOrNull()?.let { entry ->
                            val layout = if (sideBySide) LinearLayout.LayoutParams(0, -2, 1f) else LinearLayout.LayoutParams(-1, -2)
                            metrics.addView(leagueCard(league, entry), layout.apply {
                                if (metrics.childCount > 0) {
                                    if (sideBySide) leftMargin = dp(8) else topMargin = dp(8)
                                }
                            })
                        }
                    }
                    content.addView(metrics, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
                    if (summary.leagues.isNotEmpty()) {
                        content.addView(label("PvP IV percentile", 12f, p.muted).apply { gravity = Gravity.CENTER })
                        content.addView(button("Details") {
                            detailsExpanded = !detailsExpanded
                            render()
                        }.apply { contentDescription = if (detailsExpanded) "Collapse details" else "Expand details" },
                            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
                        if (detailsExpanded) details(content, summary)
                    }
                }
                footer.addView(button("Rescan", true) { scan() })
                footer.addView(button("Collapse") { collapse() })
            }
            card.addView(footer)
            card.measure(View.MeasureSpec.makeMeasureSpec(limit.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(limit.height, View.MeasureSpec.AT_MOST))
            val position = OverlayGeometry.panel(usable, limit.width, card.measuredHeight, dp(8))
            cardParams = windowLayout(position.width, position.height).apply {
                x = position.left
                y = position.top
            }
            panel = card
            scroll.post { if (panelScroll === scroll) scroll.scrollTo(0, scrollY) }
        }
        try {
            check(Settings.canDrawOverlays(this))
            check(params.width > 0 && params.height > 0)
            panel?.let { windows.addView(it, checkNotNull(cardParams)) }
            // The orb stays in its own window, above the panel, and keeps its independent coordinates.
            windows.addView(orb, params)
        } catch (_: Exception) {
            finishSession("Overlay permission is unavailable. Enable display over other apps, then start again.")
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun screenSize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= 30) {
            val bounds = windows.maximumWindowMetrics.bounds
            return bounds.width() to bounds.height()
        }
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        windows.defaultDisplay.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun windowLayout(width: Int, height: Int) = WindowManager.LayoutParams(
        width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        // Geometry uses full-display coordinates and applies safe insets exactly once.
        if (Build.VERSION.SDK_INT >= 30) {
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    @Suppress("DEPRECATION")
    private fun safeInsets(insets: WindowInsets): OverlayInsets {
        if (Build.VERSION.SDK_INT >= 30) {
            val safe = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            return OverlayInsets(safe.left, safe.top, safe.right, safe.bottom)
        }
        val cutout = insets.displayCutout
        return OverlayInsets(
            maxOf(insets.stableInsetLeft, insets.systemWindowInsetLeft, cutout?.safeInsetLeft ?: 0),
            maxOf(insets.stableInsetTop, insets.systemWindowInsetTop, cutout?.safeInsetTop ?: 0),
            maxOf(insets.stableInsetRight, insets.systemWindowInsetRight, cutout?.safeInsetRight ?: 0),
            maxOf(insets.stableInsetBottom, insets.systemWindowInsetBottom, cutout?.safeInsetBottom ?: 0),
        )
    }

    @Suppress("DEPRECATION", "DiscouragedApi")
    private fun usableScreen(): OverlayBounds {
        val (width, height) = screenSize()
        val insets = if (Build.VERSION.SDK_INT >= 30) safeInsets(windows.maximumWindowMetrics.windowInsets)
        else run {
            // API 29's small-window insets are frame-relative, not full-display safe bounds.
            // Reserve conservative display bar dimensions and the display's cutout instead.
            fun dimension(name: String): Int {
                val id = resources.getIdentifier(name, "dimen", "android")
                return if (id == 0) 0 else resources.getDimensionPixelSize(id)
            }
            val cutout = windows.defaultDisplay.cutout
            OverlayInsets(cutout?.safeInsetLeft ?: 0,
                maxOf(dimension("status_bar_height"), cutout?.safeInsetTop ?: 0),
                maxOf(if (width > height) dimension("navigation_bar_width") else 0, cutout?.safeInsetRight ?: 0),
                maxOf(dimension("navigation_bar_height"), cutout?.safeInsetBottom ?: 0))
        }
        return OverlayGeometry.usable(width, height, insets)
    }

    private fun removeOverlay() {
        panel?.let { view ->
            runCatching { windows.removeViewImmediate(view) }
        }
        panel = null
        root?.let { view ->
            runCatching { windows.removeViewImmediate(view) }
        }
        root = null
        windowParams = null
        panelScroll = null
    }

    private fun finishSession(message: String? = null) {
        if (stopped) return
        stopped = true
        try {
            generation++
            autoEpoch++
            autoEnabled = false
            autoLoop?.cancel()
            autoLoop = null
            autoProbe = null
            autoBaseline = null
            latestAutoSample = null
            autoTracker.reset()
            automaticRun = false
            acquiringFrame = false
            AutoScanStatus.update("Start an overlay session, then scan once.")
            work?.cancel()
            work = null
            scope.cancel()
            hasDiagnostic = false
            runCatching { CaptureDiagnostics.clear() }
            if (::appearance.isInitialized) appearance.unregisterOnSharedPreferenceChangeListener(appearanceListener)
            mainHandler.removeCallbacksAndMessages(null)
            removeOverlay()
            // Detach the producer before closing its consumer. No replacement token is requested.
            runCatching { display?.surface = null }
            runCatching { display?.release() }
            display = null
            runCatching { reader?.close() }
            reader = null
            val session = projection
            projection = null
            runCatching { session?.unregisterCallback(projectionCallback) }
            runCatching { session?.stop() }
            runCatching { scanner?.close() }
            scanner = null
            engineReady = null
            repository = null
            OverlayResultData.update(null)
            currentScanned = null
            currentSummary = null
            if (foreground) stopForeground(STOP_FOREGROUND_REMOVE)
        } finally {
            foreground = false
            OverlaySession.stopped(sessionRequestId, message)
            stopSelf()
        }
        if (message != null) Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        finishSession()
        super.onDestroy()
    }
}
