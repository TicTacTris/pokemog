package dev.pokemog.android

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.ImageDecoder
import android.graphics.ColorSpace
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

// Process-wide: a replaced Activity/ViewModel must not bypass a blocked provider or native OCR.
private val importGate = Semaphore(1)

class MainActivity : ComponentActivity() {
    private val model: PokeMogViewModel by viewModels()
    private val captureConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val requestId = model.pendingOverlayRequest ?: return@registerForActivityResult
        model.pendingOverlayRequest = null
        if (!OverlaySession.isStarting(requestId)) return@registerForActivityResult
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            if (!OverlaySession.dispatched(requestId)) return@registerForActivityResult
            try {
                ContextCompat.startForegroundService(this,
                    Intent(this, OverlayScanService::class.java)
                        .putExtra(OverlayScanService.EXTRA_SESSION_ID, requestId)
                        .putExtra(OverlayScanService.EXTRA_RESULT_CODE, result.resultCode)
                        .putExtra(OverlayScanService.EXTRA_PROJECTION_DATA, result.data))
                model.watchOverlayStartup(requestId)
            } catch (_: Exception) { OverlaySession.stopped(requestId, "Overlay could not start.") }
        } else OverlaySession.stopped(requestId, "Screen capture permission not granted.")
    }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        requestCapture(notificationPermissionDenied = !granted)
    }
    private val overlayPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val requestId = model.pendingOverlayRequest ?: return@registerForActivityResult
        if (!OverlaySession.isStarting(requestId)) return@registerForActivityResult
        if (Settings.canDrawOverlays(this)) requestNotificationThenCapture()
        else failOverlayStart("Appear-on-top permission not granted.")
    }
    private val imagePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.scan(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        DiagnosticCache.start(this)
        model.dataManager.checkOnStartup()
        if (model.pendingOverlayRequest == null && OverlaySession.state.value.awaitingPermission) {
            val restored = savedInstanceState?.getLong("overlayPendingRequest", -1L) ?: -1L
            if (OverlaySession.isStarting(restored)) model.pendingOverlayRequest = restored
            else OverlaySession.stopped(OverlaySession.state.value.requestId, "Overlay start interrupted. Try again.")
        }
        if (savedInstanceState == null) receiveSharedImage(intent)
        setContent {
            val prefs = remember { PokeMogAppearance.preferences(this) }
            var savedDark by remember { mutableStateOf(if (prefs.contains(PokeMogAppearance.DARK_MODE)) prefs.getBoolean(PokeMogAppearance.DARK_MODE, false) else null) }
            DisposableEffect(prefs) {
                val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    if (key == PokeMogAppearance.DARK_MODE) savedDark = if (prefs.contains(key)) prefs.getBoolean(key, false) else null
                }
                prefs.registerOnSharedPreferenceChangeListener(listener)
                onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
            }
            val dark = savedDark ?: isSystemInDarkTheme()
            SideEffect {
                val clear = android.graphics.Color.TRANSPARENT
                enableEdgeToEdge(statusBarStyle = SystemBarStyle.auto(clear, clear) { dark },
                    navigationBarStyle = SystemBarStyle.auto(clear, clear) { dark })
            }
            PokeMogTheme(dark) {
                PokeMogScreen(model, dark, onTheme = { PokeMogAppearance.setDark(this, it) },
                    onImport = { imagePicker.launch(arrayOf("image/*")) }, onOverlay = ::toggleOverlay,
                    onStop = ::stopOverlay)
            }
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); receiveSharedImage(intent) }

    override fun onSaveInstanceState(outState: Bundle) {
        model.pendingOverlayRequest?.let { outState.putLong("overlayPendingRequest", it) }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (isFinishing) model.pendingOverlayRequest?.let {
            OverlaySession.stopped(it, "Overlay start cancelled.")
            model.pendingOverlayRequest = null
        }
        super.onDestroy()
    }

    private fun receiveSharedImage(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND || intent.type?.startsWith("image/") != true) return
        val uri = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        if (uri != null && uri.scheme == "content") model.scan(uri)
    }

    private fun toggleOverlay(expectedPhase: OverlayPhase) {
        if (OverlaySession.state.value.phase != expectedPhase) return
        if (OverlaySession.state.value.phase == OverlayPhase.ON) { stopOverlay(); return }
        val id = OverlaySession.beginStart() ?: return
        model.pendingOverlayRequest = id
        if (!Settings.canDrawOverlays(this)) {
            try { overlayPermission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
            catch (_: Exception) { failOverlayStart("Open Android Settings and allow PokeMog to appear on top.") }
        } else requestNotificationThenCapture()
    }

    private fun failOverlayStart(reason: String) {
        model.pendingOverlayRequest?.let { OverlaySession.stopped(it, reason) }
        model.pendingOverlayRequest = null
    }

    private fun stopOverlay() {
        val id = OverlaySession.beginStop() ?: return
        model.pendingOverlayRequest = null
        if (!stopService(Intent(this, OverlayScanService::class.java))) OverlaySession.stopped(id)
    }

    private fun requestNotificationThenCapture() {
        val id = model.pendingOverlayRequest ?: return
        if (!OverlaySession.isStarting(id)) return
        try {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            else requestCapture()
        } catch (_: Exception) { failOverlayStart("Permission request could not open.") }
    }

    private fun requestCapture(notificationPermissionDenied: Boolean = false) {
        val id = model.pendingOverlayRequest ?: return
        if (!OverlaySession.isStarting(id)) return
        if (notificationPermissionDenied || !captureNotificationEnabled(this)) {
            model.message = "Capture notifications are disabled. Stop capture from PokeMog or long press the floating Pokeball for Stop."
            android.widget.Toast.makeText(this, model.message, android.widget.Toast.LENGTH_LONG).show()
        }
        try { captureConsent.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent()) }
        catch (_: Exception) { failOverlayStart("Screen capture request could not open.") }
    }
}

class PokeMogViewModel(application: Application) : AndroidViewModel(application) {
    var pendingOverlayRequest: Long? = null
    val dataManager = PokemonDataManager.get(application)
    private val loading = viewModelScope.async(Dispatchers.IO) { dataManager.snapshot(); dataManager.checkOnStartup() }
    var resultRepository by mutableStateOf<PokemonRepository?>(null); private set
    var ready by mutableStateOf(false); private set
    var scanning by mutableStateOf(false); private set
    var calculating by mutableStateOf(false); private set
    var summary by mutableStateOf<ScanSummary?>(null); private set
    var lastScan by mutableStateOf<ScanResult?>(null); private set
    var shadow by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null)
    private var work: Job? = null
    private var generation = 0
    private var scanEngine: ScreenshotScanner? = null

    private fun engine(): ScreenshotScanner =
        scanEngine ?: ScreenshotScanner(getApplication<Application>()).also { scanEngine = it }

    override fun onCleared() {
        scanEngine?.close()
        scanEngine = null
        super.onCleared()
    }

    fun watchOverlayStartup(requestId: Long) {
        val application = getApplication<Application>()
        OverlaySession.watchStartup(requestId) {
            application.stopService(Intent(application, OverlayScanService::class.java))
        }
    }

    init {
        viewModelScope.launch {
            try {
                loading.await()
                val scanner = engine()
                ready = true
                scanner.warmUp()
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = "Could not load bundled data. Please reopen PokeMog." }
        }
    }

    fun scan(uri: Uri) {
        val runStarted = System.nanoTime()
        work?.cancel()
        val ticket = ++generation
        summary = null; lastScan = null; resultRepository = null; shadow = false; scanning = true; calculating = false; message = null
        work = viewModelScope.launch {
            try {
                withTimeout(90_000) {
                    loading.await()
                    val repo = dataManager.snapshot()
                    val scanner = engine()
                    var decodeMs = 0L
                    val scan = withContext(Dispatchers.IO) {
                        importGate.withPermit {
                            ensureActive()
                            require(uri.scheme == "content") { "Choose a content image." }
                            val encoded = ImportedImageFiles.create(getApplication<Application>().cacheDir)
                            try {
                                // Provider open/read and native decode may ignore cancellation. Keep the gate
                                // until they return, then reject cancelled work before allocating again.
                                getApplication<Application>().contentResolver.openInputStream(uri).use { input ->
                                    requireNotNull(input) { "Image provider could not open this image." }
                                    encoded.outputStream().use { output -> input.copyImportedImage(output) { ensureActive() } }
                                }
                                ensureActive()
                                val decodeStarted = System.nanoTime()
                                val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(encoded)) { decoder, info, _ ->
                                    ensureActive()
                                    require(info.size.width >= 100 && info.size.height >= 100 && info.size.width.toLong() * info.size.height <= 32_000_000L) { "Use a screenshot between 100 pixels and 32 megapixels." }
                                    val lowRam = getApplication<Application>().getSystemService(ActivityManager::class.java).isLowRamDevice
                                    val size = CaptureSizing.nativeSize(info.size.width, info.size.height, lowRam)
                                    decoder.setTargetSize(size.width, size.height)
                                    decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                                }
                                // Keep ownership here even if the dispatcher return is cancelled.
                                try {
                                    decodeMs = elapsedMs(decodeStarted)
                                    encoded.delete()
                                    ensureActive()
                                    scanner.scan(bitmap, repository = repo)
                                } finally { bitmap.recycle(); scanner.awaitIdle() }
                            } finally { ImportedImageFiles.release(encoded) }
                        }
                    }
                    if (ticket != generation) return@withTimeout
                    lastScan = scan; resultRepository = repo; scanning = false; calculating = true
                    val assessmentStarted = System.nanoTime()
                    val result = withContext(Dispatchers.Default) { ScanAssessments.calculate(repo, scan, false) }
                    if (ticket == generation) {
                        summary = result
                        RecentScanPerformance.record(ScanPerformance("Import", decodeMs, 0,
                            elapsedMs(assessmentStarted), elapsedMs(runStarted), scan.timings))
                    }
                }
            } catch (_: TimeoutCancellationException) {
                if (ticket == generation) message = "Scan took too long. Try another screenshot."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: OutOfMemoryError) {
                if (ticket == generation) { summary = null; lastScan = null; resultRepository = null; message = "Not enough memory to read this image. Try a smaller screenshot." }
            }
            catch (error: Exception) {
                if (ticket == generation) message = "Could not read this screenshot. ${error.message ?: "Please try another image."}"
            } finally {
                if (ticket == generation) { scanning = false; calculating = false }
            }
        }
    }

    fun shadowChange(checked: Boolean) {
        val scan = lastScan ?: return
        val repo = resultRepository ?: return
        if (summary?.canToggleShadow != true || calculating || checked == shadow) return
        work?.cancel(); val ticket = ++generation
        shadow = checked; calculating = true
        work = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.Default) { ScanAssessments.calculate(repo, scan, checked) }
                if (ticket == generation) summary = result
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (ticket == generation) { summary = null; message = "Could not calculate this scan. Please rescan." } }
            finally { if (ticket == generation) calculating = false }
        }
    }

    fun cancelScan() { generation++; work?.cancel(); scanning = false; calculating = false; summary = null; lastScan = null; resultRepository = null; message = "Scan cancelled." }
    fun clearResult() { cancelScan(); message = null }
}

@Composable
private fun PokeMogScreen(model: PokeMogViewModel, dark: Boolean, onTheme: (Boolean) -> Unit, onImport: () -> Unit, onOverlay: (OverlayPhase) -> Unit, onStop: () -> Unit) {
    val overlay by OverlaySession.state.collectAsState()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var details by remember(model.lastScan) { mutableStateOf(false) }
    BackHandler(drawer.isOpen) { scope.launch { drawer.close() } }
    ModalNavigationDrawer(drawerState = drawer, drawerContent = {
        ModalDrawerSheet(Modifier.widthIn(max = 340.dp).border(2.dp, MaterialTheme.colorScheme.outline, PixelShape), drawerShape = PixelShape) {
            MenuContent(model, overlay, dark, onTheme, onStop) { scope.launch { drawer.close() } }
        }
    }) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 680.dp).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 22.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { scope.launch { drawer.open() } }, modifier = Modifier.border(2.dp, MaterialTheme.colorScheme.outline, PixelShape)) {
                            Icon(painterResource(R.drawable.ic_menu), contentDescription = "Menu")
                        }
                        Spacer(Modifier.width(6.dp))
                        Text("PokeMog", style = MaterialTheme.typography.titleLarge)
                    }
                    Text("PVP IVs on the GO", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                }
                item {
                    val canToggle = overlay.phase == OverlayPhase.ON || (overlay.phase == OverlayPhase.OFF && model.ready && !model.scanning)
                    Button(onClick = { onOverlay(overlay.phase) }, enabled = canToggle,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("overlay-toggle").semantics { stateDescription = overlay.phase.name.lowercase() },
                        shape = PixelShape, border = BorderStroke(2.dp, MaterialTheme.colorScheme.onPrimary), elevation = null) { Text(overlay.buttonText) }
                    Text(overlay.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 6.dp).testTag("overlay-description"))
                    overlay.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onImport, enabled = model.ready && !model.scanning,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = PixelShape,
                        border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline)) { Text("Import screenshot") }
                    if (!model.ready && model.message == null) { Spacer(Modifier.height(12.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    if (model.scanning || model.calculating) {
                        Spacer(Modifier.height(14.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Muted(if (model.scanning) "Scanning..." else "Calculating...")
                        TextButton(onClick = model::cancelScan) { Text("Cancel") }
                    }
                    model.message?.let { Spacer(Modifier.height(12.dp)); Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
                val result = model.summary
                if (result != null) {
                    item {
                        ResultHeader(result, !model.calculating, model::shadowChange)
                    }
                    if (result.leagues.isEmpty()) item {
                        SoftCard {
                            Text(shortScanIssue(result), style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = onImport) { Text("Choose screenshot") }
                        }
                    }
                    else {
                        for (league in result.leagues.take(2)) item(key = league.cpCap) {
                            LeagueCard(league.title, league.cpCap, league.assessment.evolutions.first())
                        }
                        item {
                            TextButton(onClick = { details = !details }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (details) "Hide details" else "Details") }
                            if (details) {
                                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                    Muted("PvP IV percentile compares eligible IV spreads of this form, not win chance. Percentage of best compares stat products.")
                                    Muted("Current CP ${model.lastScan?.cp ?: "--"} / HP ${model.lastScan?.hp ?: "--"}")
                                    Muted("Effective level ${effectiveLevelLabel(result)}")
                                    result.candidateLabel?.let { Muted(it) }
                                    result.leagues.firstOrNull { it.cpCap == 500 }?.let { LeagueCard(it.title, it.cpCap, it.assessment.evolutions.first()) }
                                    for (entry in result.leagues.first().assessment.evolutions) {
                                        SoftCard {
                                            DetailHeading(entry.pokemon.name)
                                            if (result.shadow && entry.pokemon.id in result.unverifiedShadowIds) {
                                                Text("Shadow availability unverified", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                                            }
                                            CurrentStats(entry, result.shadow, result.isCurrent(entry.pokemon))
                                            for (alternative in levelAlternatives(result.leagues.first().assessment.levelScenarios)) Muted(alternative)
                                            for (league in result.leagues) {
                                                val evolved = league.assessment.evolutions.first { it.pokemon.id == entry.pokemon.id }
                                                DetailHeading("${league.title} / League optimum")
                                                if (evolved.optimal == null) Muted("No eligible build")
                                                else {
                                                    Text("${percent(evolved.ivPercentile!!)}% PvP IV percentile / #${evolved.optimal.rank}")
                                                    Muted("Stat product ${percent(evolved.percentBest)}% / Effective level ${levelNumber(evolved.optimal.level)}")
                                                    StatsGrid(evolved.optimal.stats, result.shadow)
                                                }
                                                shortFeasibility(evolved.feasibility)?.let { Muted(it) }
                                            }
                                            DetailHeading("Fully powered up / Effective level ${levelNumber(entry.maximumLevel)}")
                                            StatsGrid(entry.maximum, result.shadow)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    item { TextButton(onClick = model::clearResult, modifier = Modifier.fillMaxWidth()) { Text("Clear scan") } }
                }
            }
        }
    }
    }
}

@Composable
private fun MenuContent(model: PokeMogViewModel, overlay: OverlaySessionState, dark: Boolean, onTheme: (Boolean) -> Unit, onStop: () -> Unit, onClose: () -> Unit) {
    var savedSection by rememberSaveable { mutableStateOf("Instructions") }
    val section = if (savedSection == "Scan details") "Instructions" else savedSection
    LaunchedEffect(section) { savedSection = section }
    val application = model.getApplication<Application>()
    val preferences = remember { PokeMogAppearance.preferences(application) }
    var automatic by remember { mutableStateOf(AutoScanSettings.enabled(application)) }
    val autoStatus by AutoScanStatus.state.collectAsState()
    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == AutoScanSettings.KEY) automatic = AutoScanSettings.enabled(application)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    Column(Modifier.fillMaxHeight().padding(horizontal = 18.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("PokeMog", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(painterResource(R.drawable.ic_close), contentDescription = "Close menu") }
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).background(MaterialTheme.colorScheme.surfaceVariant, PixelShape)
            .border(2.dp, MaterialTheme.colorScheme.outline, PixelShape).toggleable(dark, role = Role.Switch, onValueChange = onTheme)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics { contentDescription = "Dark mode" }, verticalAlignment = Alignment.CenterVertically) {
            Text("Dark mode", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(if (dark) "ON" else "OFF", style = MaterialTheme.typography.labelLarge)
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).background(MaterialTheme.colorScheme.surfaceVariant, PixelShape)
            .border(2.dp, MaterialTheme.colorScheme.outline, PixelShape)
            .toggleable(automatic, role = Role.Switch, onValueChange = { AutoScanSettings.setEnabled(application, it) })
            .padding(horizontal = 12.dp, vertical = 8.dp).semantics { contentDescription = "Auto-scan appraisals" },
            verticalAlignment = Alignment.CenterVertically) {
            Text("Auto-scan", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(if (automatic) "ON" else "OFF", style = MaterialTheme.typography.labelLarge)
        }
        Muted(if (!automatic) "Manual scanning only." else if (overlay.phase != OverlayPhase.ON) "Start overlay, then scan once." else autoStatus)
        for (name in listOf("Instructions", "Calculations", "Privacy & diagnostics", "About & licenses")) {
            NavigationDrawerItem(label = { Text(name) }, selected = section == name, onClick = { savedSection = name }, shape = PixelShape)
        }
        if (overlay.phase == OverlayPhase.ON) TextButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("Stop overlay") }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
        Text(section, style = MaterialTheme.typography.titleMedium)
        when (section) {
            "Instructions" -> {
                Muted("Open Appraise in Pokemon GO. Start the overlay and approve the Android permissions, then share Pokemon GO. Tap the floating Pokeball to scan, drag to move it, and long press for Stop. You can always stop from PokeMog; notification Stop is available only when notifications and the capture channel are enabled.")
                Muted("You can also import or share a screenshot to PokeMog. The Pokemon name, CP, HP and all three appraisal bars should be visible. There is no manual entry; Shadow is the only editable calculation input.")
                Muted("The overlay results sit in the lower half of the screen. League badges identify Great (1500 CP) and Ultra (2500 CP). The arc shows PvP IV percentile, not the in-game CP level progress.")
                Muted("Auto-scan is optional and off by default. Enable it, then make one complete manual appraisal scan. It watches for changed, settled appraisal fields and scans again automatically. Use Pokemon GO-only sharing where available. If full-screen sharing puts the results panel over the appraisal bars, collapse the panel so detection can continue.")
                Muted("Automatic detection pauses when the shared app is hidden, the phone is locked, or appraisal fields cannot be found. After rotation or a capture-size change, scan once to establish a new baseline. Identical visible fields cannot reliably identify a different Pokemon; use manual Rescan in that case. Check Shadow on each new result.")
            }
            "Calculations" -> {
                Muted("PvP IV percentile compares this spread's rank with all eligible spreads of the same species/form. Rank 1 is 100%. Tied ranks share a percentile. Stat-product % compares its optimal Attack x Defense x HP with that form's best build.")
                Muted("Builds use a level 50 cap without a future Best Buddy boost. If current buddy status or base level is uncertain, feasibility stays uncertain. Pokemon cannot be powered down. Rank is not a measure of overall species strength or win rate.")
                Muted("Shadow deals and receives 20% more damage. Shadow ATK equivalent is a damage modifier, not a change to actual Attack, CP, HP or standard IV ranking.")
                Muted("Shadow is a condition you set, even when this snapshot has no separate Shadow entry. Those builds and unconfirmed evolution paths are marked Shadow availability unverified in Details; the checkbox does not prove that form is obtainable. Current level is the observed effective level; an active buddy can make its base level one lower.")
                Muted("Details include Little League, evolved forms and maximum stats. Evolution conditions, event-only paths and cup restrictions still apply. Some forms and families are missing. Purification and power-up costs are not calculated.")
            }
            "Privacy & diagnostics" -> {
                Muted("OCR runs on-device with a bundled English model. Startup checks download validated public Pokemon data from GitHub. Screenshots and recognized text are not sent by the updater. GitHub receives ordinary connection metadata, including your IP address. Capture consent is requested for each new session and is never reused after process death.")
                Muted("Scans can be incorrect. Rescan unreadable or inconsistent images. For a failed overlay scan, Inspect failed capture shows the captured and analyzed images without saving or uploading them.")
                Muted("Share diagnostic ZIP explicitly creates a cached file and opens Android's share chooser; this does not verify delivery. Images may contain trainer details, location or other visible information. Review them before sharing. Failed exports are deleted when possible. Cached ZIPs are limited to three / 100 MiB, with cleanup attempted after one hour and on startup, inspector open/close and export. Process death delays cleanup until next startup. An open inspector retains images after capture stops until closed. Deletion here cannot remove recipients' copies.")
                val privacyScope = rememberCoroutineScope()
                var deletionMessage by remember { mutableStateOf<String?>(null) }
                TextButton(onClick = { privacyScope.launch {
                    val failures = withContext(Dispatchers.IO) { DiagnosticCache.purge(application, all = true) }
                    deletionMessage = if (failures == 0) "Local diagnostic ZIPs deleted. Recipient copies and open inspector images are unaffected."
                        else "Some ZIPs could not be deleted or are being prepared. Try again. Recipient copies are unaffected."
                } }) { Text("Delete diagnostics") }
                deletionMessage?.let { Muted(it) }
                Muted("Internet permission applies to the whole app, including SDKs. ML Kit may send diagnostic and usage metrics to Google; on-device OCR does not mean all SDK networking is disabled.")
                val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                TextButton(onClick = { uriHandler.openUri("https://developers.google.com/ml-kit/terms") }) { Text("ML Kit terms & privacy") }
                TextButton(onClick = { uriHandler.openUri("https://policies.google.com/privacy") }) { Text("Google privacy policy") }
                Muted("Protected screens cannot be captured. Locking the phone, revoked permissions or manufacturer background limits can stop a session. The home button reflects the actual session state.")
                Muted("With Auto-scan enabled, the active capture session is sampled locally at up to four checks per second after a successful manual scan. Only small text masks and an appraisal-area thumbnail are analyzed to trigger OCR; no monitoring occurs outside the authorized session. Select Pokemon GO-only sharing to exclude other apps. No extra accessibility or usage-access permission is needed.")
            }
            else -> {
                Muted("PokeMog ${installedVersion(application)} / Android. Unofficial fan tool; not affiliated with Pokemon or Niantic.")
                val dataStatus by model.dataManager.status.collectAsState()
                Muted("Pokemon data: ${dataStatus.active?.version ?: "Loading"} / ${dataStatus.message}")
                dataStatus.active?.let { Muted("Active source: ${it.sourceCommit}\nPublished: ${it.publishedAt}") }
                model.resultRepository?.let { Muted("Imported result data: ${it.identity.version}") }
                val overlayResultData by OverlayResultData.identity.collectAsState()
                overlayResultData?.let { Muted("Overlay result data: ${it.version}") }
                Muted("English text recognition: Google ML Kit. Pixel artwork is original to this app.")
                val license = remember(dataStatus.active) { model.dataManager.snapshot().license }
                Muted(license)
                val fontLicense = remember { model.getApplication<Application>().assets.open("Silkscreen-OFL.txt").bufferedReader().use { it.readText() } }
                Muted(fontLicense)
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
internal fun ResultHeader(result: ScanSummary, enabled: Boolean, onShadow: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(result.displayName ?: "Scan needs attention", style = MaterialTheme.typography.headlineSmall)
        result.formLabel?.let { Muted(it) }
        val ivs = result.ivs
        if (ivs != null) Text("${ivs.attack} / ${ivs.defense} / ${ivs.stamina}", style = MaterialTheme.typography.titleLarge, fontSize = 20.sp)
        ShadowToggle(result.shadow, enabled && result.canToggleShadow, onShadow)
        if (result.shadow && result.leagues.firstOrNull()?.assessment?.evolutions?.firstOrNull()?.pokemon?.id in result.unverifiedShadowIds) {
            Muted("Shadow availability unverified")
        }
        if (!enabled) Muted("Updating...")
    }
}

@Composable
internal fun LeagueCard(title: String, cap: Int, entry: EvolutionProjection) {
    SoftCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val icon = when (cap) { 1500 -> R.drawable.ic_league_great; 2500 -> R.drawable.ic_league_ultra; else -> R.drawable.ic_league_little }
            Icon(painterResource(icon), contentDescription = title, tint = Color.Unspecified, modifier = Modifier.size(30.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
            }
        }
        androidx.compose.runtime.key(entry.ivPercentile, MaterialTheme.colorScheme) {
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { context -> LeagueArcView(context, PokeMogAppearance.palette(context), entry.ivPercentile,
                    if (cap == 1500) 0xFF6CA9E8.toInt() else PokeMogAppearance.palette(context).gold) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Muted("PvP IV percentile")
        val optimum = entry.optimal
        if (optimum != null) shortFeasibility(entry.feasibility)?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun CurrentStats(entry: EvolutionProjection, shadow: Boolean, selected: Boolean) {
    DetailHeading(if (selected) "Current stats" else "After evolution, no power-ups")
    if (entry.current.isEmpty()) Muted("Current level unknown")
    else if (entry.current.size == 1) StatsGrid(entry.current.single().second, shadow, entry.current.single().first)
    else {
        val first = entry.current.first(); val last = entry.current.last()
        Muted("Effective level: ${entry.current.joinToString(", ") { levelNumber(it.first) }} / CP ${first.second.cp}-${last.second.cp} / HP ${first.second.hp}-${last.second.hp}")
        Muted("ATK ${fmt(first.second.attack)}-${fmt(last.second.attack)} / DEF ${fmt(first.second.defense)}-${fmt(last.second.defense)}")
        if (shadow) Muted("Shadow ATK equivalent ${fmt(first.second.attack * 1.2)}-${fmt(last.second.attack * 1.2)}")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatsGrid(stats: Stats, shadow: Boolean, level: Double? = null) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val fields = mutableListOf("CP" to stats.cp.toString(), "HP" to stats.hp.toString())
        if (level != null) fields.add("Effective level" to levelNumber(level))
        fields.add("ATTACK" to fmt(stats.attack)); fields.add("DEFENSE" to fmt(stats.defense))
        for ((label, value) in fields) Column(Modifier.widthIn(min = 70.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
    }
    if (shadow) Muted("Shadow ATK equivalent ${fmt(stats.attack * 1.2)} / damage x1.2")
}

@Composable
private fun SoftCard(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = PixelShape,
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp), content = content)
    }
}

@Composable
private fun DetailHeading(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.outline)
}

@Composable
private fun Muted(text: String) { Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
