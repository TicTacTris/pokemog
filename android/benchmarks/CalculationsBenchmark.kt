package dev.pokemog.android

import com.sun.management.ThreadMXBean
import java.io.File
import java.lang.management.ManagementFactory
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** Host-only observational benchmark, outside Android's restricted test bootclasspath. */
object CalculationsBenchmark {
    @Volatile private var sink: Any? = null

    @JvmStatic fun main(args: Array<String>) {
        val root = File(args[0])
        val resources = File(root, "android/app/src/test/resources")
        val cpms = File(resources, "cp-multipliers.tsv").readLines().filter(String::isNotBlank).map(String::toDouble)
        val json = JSONArray(File(root, "android/app/src/main/assets/pokemon.json").readText())
        val catalog = List(json.length()) { i ->
            val p = json.getJSONObject(i)
            val edges = p.getJSONArray("evolutions")
            Pokemon(p.getString("id"), p.getString("name"), p.getInt("attack"), p.getInt("defense"), p.getInt("stamina"),
                List(edges.length()) { edges.getString(it) }, p.optString("shadowId").ifEmpty { null }, p.optString("normalId").ifEmpty { null })
        }
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        bean.isThreadAllocatedMemoryEnabled = true
        fun measure(name: String, iterations: Int, block: (Int) -> Any?) {
            repeat(maxOf(1000, iterations)) { sink = block(it) }
            val times = ArrayList<Double>()
            val allocations = ArrayList<Double>()
            repeat(5) {
                val bytes = bean.getThreadAllocatedBytes(Thread.currentThread().id)
                val start = System.nanoTime()
                repeat(iterations) { sink = block(it) }
                times.add((System.nanoTime() - start) / 1e6 / iterations)
                allocations.add((bean.getThreadAllocatedBytes(Thread.currentThread().id) - bytes).toDouble() / iterations)
            }
            println(String.format(Locale.US, "%s: median %.6f ms/op, %.0f bytes/op (5 x %d)", name, times.sorted()[2], allocations.sorted()[2], iterations))
        }
        println("Host JVM only: ${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}; no OCR/capture/decode I/O")
        val shinx = catalog.single { it.id == "shinx" }
        measure("rank cold shinx/1500/50", 100) { Calculations(cpms).rankIVs(shinx, 1500, 50.0) }
        val warm = Calculations(cpms)
        measure("rank warm shinx/1500/50", 1000) { warm.rankIVs(shinx, 1500, 50.0) }
        for ((id, ivs) in listOf("shinx" to IVs(4, 12, 15), "giratina_altered" to IVs(8, 11, 6), "eevee" to IVs(1, 15, 14))) {
            val p = catalog.single { it.id == id }
            val observed = warm.stats(p, ivs, 20.0)
            val scan = ScanResult("", listOf(id), ivs, observed.cp, observed.hp)
            measure("assessment cold $id/3 leagues", 30) { ScanAssessments.calculate(Calculations(cpms), catalog, scan, false) }
            val calc = Calculations(cpms)
            measure("assessment warm $id/3 leagues", 30) { ScanAssessments.calculate(calc, catalog, scan, false) }
            measure("assessment toggle $id/3 leagues", 30) { ScanAssessments.calculate(calc, catalog, scan, it % 2 == 0) }
        }
        for (name in listOf("giratina-ffmpeg", "giratina-imageio", "shinx-native", "shinx-analyzed", "s23-native", "s23-nearest", "s23-bilinear")) {
            val image = JSONObject(File(resources, "appraisal-$name.json").readText())
            val width = image.getInt("width"); val height = image.getInt("height")
            val pixels = IntArray(width * height)
            val runs = image.getJSONArray("runs")
            var offset = 0
            for (i in 0 until runs.length() step 2) {
                val end = offset + runs.getInt(i)
                pixels.fill(runs.getInt(i + 1), offset, end)
                offset = end
            }
            check(offset == pixels.size)
            println("fixture $name ${width}x$height result=${AppraisalBarDetector.detect(pixels, width, height)}")
            measure("bars $name", 100) { AppraisalBarDetector.detect(pixels, width, height) }
        }
    }
}
