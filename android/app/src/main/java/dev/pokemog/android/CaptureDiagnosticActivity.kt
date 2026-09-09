package dev.pokemog.android

import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import android.view.WindowManager

class CaptureDiagnosticViewModel : ViewModel() {
    var diagnostic by mutableStateOf(CaptureDiagnostics.acquire()); private set
    fun refresh() {
        val previous = diagnostic
        diagnostic = CaptureDiagnostics.acquire()
        previous?.release()
    }
    override fun onCleared() { diagnostic?.release() }
}

class CaptureDiagnosticActivity : ComponentActivity() {
    private val model: CaptureDiagnosticViewModel by viewModels()
    private var exporting by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        DiagnosticCache.start(this)
        enableEdgeToEdge()
        setContent {
            PokeMogTheme(PokeMogAppearance.isDark(this)) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.safeDrawingPadding().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("PokeMog capture inspector", style = MaterialTheme.typography.headlineSmall)
                        val diagnostic = model.diagnostic
                        if (diagnostic == null) {
                            Text("This diagnostic has expired. Run another overlay scan and inspect it before stopping the session.")
                        } else {
                            DisposableEffect(diagnostic) {
                                diagnostic.retain()
                                onDispose { diagnostic.release() }
                            }
                            Text("Compare what Android captured with the exact image used by recognition. Viewing does not save or upload either image.", style = MaterialTheme.typography.bodyMedium)
                            var analyzed by remember(diagnostic) { mutableStateOf(true) }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(analyzed, onClick = { analyzed = true }, label = { Text("Analyzed") }, shape = PixelShape)
                                FilterChip(!analyzed, onClick = { analyzed = false }, label = { Text("Captured") }, shape = PixelShape)
                            }
                            val bitmap = if (analyzed) diagnostic.analyzed else diagnostic.captured
                            Text("${if (analyzed) "Analyzed pixels" else "Captured pixels"}: ${bitmap.width} x ${bitmap.height}", style = MaterialTheme.typography.labelLarge)
                            Image(bitmap.asImageBitmap(), "${if (analyzed) "Analyzed" else "Captured"} failed scan", Modifier.fillMaxWidth().heightIn(max = 600.dp))
                            Text("Capture details", style = MaterialTheme.typography.titleMedium)
                            Text("app=PokeMog ${installedVersion(this@CaptureDiagnosticActivity)}\n${diagnostic.metadata}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()))
                            Text("Sharing creates a cached ZIP with both full images and these details. It may include your trainer name, location, notifications or other visible information. Only share it with someone you trust; close the inspector if you do not want to share.", style = MaterialTheme.typography.bodySmall)
                            Button(onClick = { share(diagnostic) }, enabled = !exporting, modifier = Modifier.fillMaxWidth(), shape = PixelShape,
                                border = BorderStroke(2.dp, MaterialTheme.colorScheme.onPrimary), elevation = null) { Text(if (exporting) "Preparing diagnostic..." else "Share diagnostic ZIP") }
                            if (exporting) LinearProgressIndicator(Modifier.fillMaxWidth())
                            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        }
                        Text("An open inspector retains its images even after capture stops. Close it to release its lease. Cached ZIP cleanup is attempted after one hour, on next startup if the process is stopped. Deleting local diagnostics cannot delete recipients' copies.", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = {
                            lifecycleScope.launch {
                                val failures = withContext(Dispatchers.IO) { DiagnosticCache.purge(this@CaptureDiagnosticActivity, all = true) }
                                error = if (failures == 0) "Local ZIPs deleted. Recipient copies are unaffected." else "Some local ZIPs could not be deleted or are still being prepared. Try again. Recipient copies are unaffected."
                            }
                        }, enabled = !exporting) { Text("Delete diagnostics") }
                        if (model.diagnostic == null) error?.let { Text(it) }
                        OutlinedButton(onClick = { finish() }, modifier = Modifier.fillMaxWidth(), shape = PixelShape,
                            border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline)) { Text("Close inspector") }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onStop() {
        DiagnosticCache.start(this)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        model.refresh()
        error = null
    }

    private fun share(diagnostic: CaptureDiagnostic) {
        if (exporting) return
        exporting = true; error = null
        lifecycleScope.launch {
            var outputFile: File? = null
            var handedToChooser = false
            var ownsPermit = false
            try {
                DiagnosticCache.exportGate.acquire()
                ownsPermit = true
                val file = withContext(Dispatchers.IO) {
                    ensureActive()
                    diagnostic.retain()
                    try {
                        val output = DiagnosticCache.begin(this@CaptureDiagnosticActivity)
                        outputFile = output
                        try {
                            ZipOutputStream(LimitedDiagnosticOutput(output.outputStream(),
                                DiagnosticCache.remainingBytes(this@CaptureDiagnosticActivity, output)).buffered()).use { zip ->
                                zip.putNextEntry(ZipEntry("captured.png"))
                                check(diagnostic.captured.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, zip))
                                zip.closeEntry()
                                ensureActive()
                                zip.putNextEntry(ZipEntry("analyzed.png"))
                                check(diagnostic.analyzed.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, zip))
                                zip.closeEntry()
                                ensureActive()
                                zip.putNextEntry(ZipEntry("capture-details.txt"))
                                zip.write("app=PokeMog ${installedVersion(this@CaptureDiagnosticActivity)}\n${diagnostic.metadata}".toByteArray(Charsets.UTF_8)); zip.closeEntry()
                            }
                            ensureActive()
                            check(DiagnosticCache.purge(this@CaptureDiagnosticActivity) == 0) { "Could not enforce diagnostic cache limits" }
                            output
                        } catch (cause: Throwable) { output.delete(); throw cause }
                    } finally { diagnostic.release() }
                }
                val uri = FileProvider.getUriForFile(this@CaptureDiagnosticActivity, "$packageName.diagnostics", file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "PokeMog failed capture diagnostic")
                    clipData = ClipData.newRawUri("PokeMog diagnostic", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(send, "Share PokeMog diagnostic"))
                handedToChooser = true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "Could not share this diagnostic. The cache may be full; use Delete diagnostics, then try again." }
            catch (_: OutOfMemoryError) { error = "Not enough memory to export this diagnostic." }
            finally {
                outputFile?.let {
                    if (!handedToChooser && it.exists() && !it.delete()) error = "Export failed; cached file could not be deleted. Use Delete diagnostics."
                    DiagnosticCache.finish(it)
                }
                if (ownsPermit) DiagnosticCache.exportGate.release()
                exporting = false
            }
        }
    }
}
