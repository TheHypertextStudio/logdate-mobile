package studio.hypertext.atproto.repo

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.syntax.Nsid
import studio.hypertext.atproto.syntax.RecordKey
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTime

/**
 * Times single-record writes and reads in repositories of 1,000, 10,000 and 100,000 records, and
 * writes the medians to `build/reports/benchmarks/repo-engine.txt`.
 *
 * The bound it asserts is loose, so a slow machine still passes; it fails when a write costs work
 * proportional to the repository again.
 */
class RepoEngineScalingBenchmark {
    private val repo = AtprotoDid.require("did:plc:ewvi7nxzyoun6zhxrhs64oiz")
    private val collection = Nsid.require("studio.hypertext.logdate.entry")

    @Test
    fun `a write or read costs about the same at every repository size`() =
        runBlocking {
            val results =
                SIZES.map { size ->
                    val engine = DefaultRepoEngine(InMemoryRepoBlockStore())
                    val build =
                        measureTime {
                            (0 until size).chunked(BUILD_BATCH).forEach { batch ->
                                engine.putRecords(batch.map { BatchRecordWrite(record(it), value(it)) }).getOrThrow()
                            }
                        }
                    val puts = List(SAMPLES) { n -> measureTime { engine.putRecord(record(size + n), value(n)).getOrThrow() } }
                    val gets = List(SAMPLES) { n -> measureTime { engine.getRecord(record(n * (size / SAMPLES))).getOrThrow() } }
                    Result(size, build, puts.median(), gets.median())
                }

            val report =
                results.joinToString("\n", prefix = "records\tbuild\tput (median)\tget (median)\n") {
                    "${it.size}\t${it.build}\t${it.put}\t${it.get}"
                }
            File("build/reports/benchmarks").apply { mkdirs() }.resolve("repo-engine.txt").writeText(report + "\n")
            assertTrue(results.last().put < MAX_PUT_AT_LARGEST, report)
        }

    private fun record(n: Int) = RepoRecordId(repo, collection, RecordKey.require("entry-${n.toString().padStart(7, '0')}"))

    private fun value(n: Int) =
        buildJsonObject {
            put("\$type", collection.toString())
            put("text", "entry $n")
        }

    private fun List<Duration>.median(): Duration = sorted()[size / 2]

    private data class Result(
        val size: Int,
        val build: Duration,
        val put: Duration,
        val get: Duration,
    )

    private companion object {
        val SIZES = listOf(1_000, 10_000, 100_000)
        const val BUILD_BATCH = 10_000
        const val SAMPLES = 25
        val MAX_PUT_AT_LARGEST = 250.milliseconds
    }
}
