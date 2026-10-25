package pro.cslauncher.core.content

import java.io.*
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Complete NBT (Named Binary Tag) implementation for level.dat and servers.dat. */
sealed class Tag {
    data class TByte(val v: Byte) : Tag(); data class TShort(val v: Short) : Tag(); data class TInt(val v: Int) : Tag()
    data class TLong(val v: Long) : Tag(); data class TFloat(val v: Float) : Tag(); data class TDouble(val v: Double) : Tag()
    class TByteArray(val v: ByteArray) : Tag(); data class TString(val v: String) : Tag()
    data class TList(val elementType: Int, val v: List<Tag>) : Tag(); data class TCompound(val v: LinkedHashMap<String, Tag>) : Tag()
    class TIntArray(val v: IntArray) : Tag(); class TLongArray(val v: LongArray) : Tag()

    val id: Int get() = when (this) {
        is TByte -> 1; is TShort -> 2; is TInt -> 3; is TLong -> 4; is TFloat -> 5; is TDouble -> 6; is TByteArray -> 7
        is TString -> 8; is TList -> 9; is TCompound -> 10; is TIntArray -> 11; is TLongArray -> 12
    }
}

fun Tag.TCompound.compound(k: String) = v[k] as? Tag.TCompound
fun Tag.TCompound.string(k: String) = (v[k] as? Tag.TString)?.v
fun Tag.TCompound.long(k: String) = when (val t = v[k]) { is Tag.TLong -> t.v; is Tag.TInt -> t.v.toLong(); else -> null }
fun Tag.TCompound.int(k: String) = when (val t = v[k]) { is Tag.TInt -> t.v; is Tag.TByte -> t.v.toInt(); is Tag.TShort -> t.v.toInt(); else -> null }

object Nbt {
    private const val MAX_DEPTH = 512

    fun readFile(f: File): Pair<String, Tag.TCompound> {
        val raw = BufferedInputStream(FileInputStream(f))
        raw.mark(2); val b0 = raw.read(); val b1 = raw.read(); raw.reset()
        val ins: InputStream = if (b0 == 0x1f && b1 == 0x8b) GZIPInputStream(raw) else raw
        return DataInputStream(ins).use { read(it) }
    }

    fun read(d: DataInput): Pair<String, Tag.TCompound> {
        val type = d.readByte().toInt()
        require(type == 10) { "Root tag is not a compound" }
        val name = d.readUTF()
        return name to (readPayload(d, 10, 0) as Tag.TCompound)
    }

    private fun readPayload(d: DataInput, type: Int, depth: Int): Tag {
        require(depth < MAX_DEPTH) { "NBT nesting too deep" }
        return when (type) {
            1 -> Tag.TByte(d.readByte()); 2 -> Tag.TShort(d.readShort()); 3 -> Tag.TInt(d.readInt()); 4 -> Tag.TLong(d.readLong())
            5 -> Tag.TFloat(d.readFloat()); 6 -> Tag.TDouble(d.readDouble())
            7 -> { val n = d.readInt(); require(n in 0..(64 shl 20)); Tag.TByteArray(ByteArray(n).also { d.readFully(it) }) }
            8 -> Tag.TString(d.readUTF())
            9 -> { val et = d.readByte().toInt(); val n = d.readInt(); require(n in 0..10_000_000); Tag.TList(et, List(n) { readPayload(d, et, depth + 1) }) }
            10 -> { val m = LinkedHashMap<String, Tag>(); while (true) { val t = d.readByte().toInt(); if (t == 0) break; val k = d.readUTF(); m[k] = readPayload(d, t, depth + 1) }; Tag.TCompound(m) }
            11 -> { val n = d.readInt(); require(n in 0..(16 shl 20)); Tag.TIntArray(IntArray(n) { d.readInt() }) }
            12 -> { val n = d.readInt(); require(n in 0..(8 shl 20)); Tag.TLongArray(LongArray(n) { d.readLong() }) }
            else -> throw IOException("Unknown NBT tag $type")
        }
    }

    fun write(d: DataOutput, name: String, root: Tag.TCompound) { d.writeByte(10); d.writeUTF(name); writePayload(d, root) }

    private fun writePayload(d: DataOutput, t: Tag) {
        when (t) {
            is Tag.TByte -> d.writeByte(t.v.toInt()); is Tag.TShort -> d.writeShort(t.v.toInt()); is Tag.TInt -> d.writeInt(t.v)
            is Tag.TLong -> d.writeLong(t.v); is Tag.TFloat -> d.writeFloat(t.v); is Tag.TDouble -> d.writeDouble(t.v)
            is Tag.TByteArray -> { d.writeInt(t.v.size); d.write(t.v) }
            is Tag.TString -> d.writeUTF(t.v)
            is Tag.TList -> { d.writeByte(if (t.v.isEmpty()) t.elementType else t.v[0].id); d.writeInt(t.v.size); t.v.forEach { writePayload(d, it) } }
            is Tag.TCompound -> { for ((k, v) in t.v) { d.writeByte(v.id); d.writeUTF(k); writePayload(d, v) }; d.writeByte(0) }
            is Tag.TIntArray -> { d.writeInt(t.v.size); t.v.forEach { d.writeInt(it) } }
            is Tag.TLongArray -> { d.writeInt(t.v.size); t.v.forEach { d.writeLong(it) } }
        }
    }

    fun writeFile(f: File, name: String, root: Tag.TCompound, gzip: Boolean) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        DataOutputStream(BufferedOutputStream(if (gzip) GZIPOutputStream(FileOutputStream(tmp)) else FileOutputStream(tmp))).use { write(it, name, root) }
        java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
}
