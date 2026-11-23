package io.github.markusaugust.streamlord.analysis

import java.io.DataInputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * HotSpot compiles no method of more than 8,000 bytes of bytecode (`-XX:HugeMethodLimit`), and runs it interpreted, many
 * times slower. The scanner of a file's declarations was one byte under it, and one more rule put the whole reading of a
 * file past it. So every method of the analysis stays under it.
 */
class MethodSizeTest {
    @Test
    fun `every method of the analysis is small enough for the JIT to compile`() {
        val classes =
            File(
                Analyzer::class.java.protectionDomain.codeSource.location
                    .toURI(),
            )
        val huge =
            classes
                .walkTopDown()
                .filter { it.extension == "class" }
                .flatMap { file ->
                    codeLengths(file.readBytes()).map { (method, length) -> Triple(file.nameWithoutExtension, method, length) }
                }.filter { it.third > HUGE }
                .map { "${it.first}.${it.second}: ${it.third} bytes" }
                .toList()
        assertEquals(emptyList(), huge)
    }

    /** The length of each method's code in a class file, by the method's name. */
    private fun codeLengths(bytes: ByteArray): List<Pair<String, Int>> {
        val input = DataInputStream(bytes.inputStream())
        input.skipBytes(8)
        val utf8 = HashMap<Int, String>()
        val count = input.readUnsignedShort()
        var k = 1
        while (k < count) {
            val tag = input.readUnsignedByte()
            when (tag) {
                1 -> utf8[k] = input.readUTF()
                3, 4, 9, 10, 11, 12, 17, 18 -> input.skipBytes(4)
                5, 6 -> input.skipBytes(8).also { k++ }
                7, 8, 16, 19, 20 -> input.skipBytes(2)
                15 -> input.skipBytes(3)
                else -> error("constant pool tag $tag")
            }
            k++
        }
        input.skipBytes(6)
        input.skipBytes(2 * input.readUnsignedShort())

        fun skipAttributes() = repeat(input.readUnsignedShort()) { input.skipBytes(2).also { input.skipBytes(input.readInt()) } }
        repeat(input.readUnsignedShort()) {
            input.skipBytes(6)
            skipAttributes()
        }
        val out = ArrayList<Pair<String, Int>>()
        repeat(input.readUnsignedShort()) {
            input.skipBytes(2)
            val name = utf8.getValue(input.readUnsignedShort())
            input.skipBytes(2)
            repeat(input.readUnsignedShort()) {
                val attribute = utf8.getValue(input.readUnsignedShort())
                val length = input.readInt()
                if (attribute == "Code") {
                    input.skipBytes(4)
                    out += name to input.readInt()
                    input.skipBytes(length - 8)
                } else {
                    input.skipBytes(length)
                }
            }
        }
        return out
    }

    private companion object {
        const val HUGE = 8_000
    }
}
