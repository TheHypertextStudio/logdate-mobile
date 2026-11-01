import tla2sany.parser.SimpleCharStream
import tla2sany.parser.TLAplusParserConstants
import tla2sany.parser.TLAplusParserTokenManager
import tla2sany.parser.Token
import tlc2.tool.impl.ModelConfig
import java.io.ByteArrayInputStream
import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** Actual TLC parsing plus ordered official token/comment attachment equality. */
object TlcConfigVerifier {
    private data class Lexeme(
        val kind: Int,
        val image: String,
    )

    private data class Entry(
        val specials: List<Lexeme>,
        val regular: Lexeme,
    )

    private fun tokens(path: Path): List<Entry> {
        // TLC appends a newline so an EOF line comment is lexically terminated.
        val text = Files.readString(path, StandardCharsets.UTF_8) + "\n"
        val stream =
            SimpleCharStream(
                ByteArrayInputStream(text.toByteArray(StandardCharsets.UTF_8)),
                1,
                1,
            )
        val manager = TLAplusParserTokenManager(stream, 2)
        val result = mutableListOf<Entry>()
        while (true) {
            val regular = manager.nextToken
            val specials = mutableListOf<Lexeme>()
            var special: Token? = regular.specialToken
            while (special != null) {
                specials.add(Lexeme(special.kind, special.image))
                special = special.specialToken
            }
            specials.reverse()
            result.add(Entry(specials.toList(), Lexeme(regular.kind, regular.image)))
            if (regular.kind == TLAplusParserConstants.EOF) {
                return result.toList()
            }
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2) { "Expected original and formatted CFG paths" }
        val original = Path.of(args[0])
        val formatted = Path.of(args[1])
        // ModelConfig resets shared ModelValue state; snapshot tokens first and
        // never compare cross-instance Value objects or partial getter snapshots.
        ModelConfig(original.toString(), null).parse()
        val before = tokens(original)
        ModelConfig(formatted.toString(), null).parse()
        val after = tokens(formatted)
        require(before == after) { "Official TLC token/comment stream changed" }
        println(
            "TLC-PARSER-ROUNDTRIP tokens=${before.size}" +
                " java=${System.getProperty("java.home")}" +
                " maxHeap=${Runtime.getRuntime().maxMemory()}" +
                " args=${ManagementFactory.getRuntimeMXBean().inputArguments}",
        )
    }
}
