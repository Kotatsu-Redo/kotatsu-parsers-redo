package tasks.bundle

import java.io.DataInputStream
import java.io.File

private const val ACC_PUBLIC = 0x0001
private const val ACC_PRIVATE = 0x0002
private const val ACC_PROTECTED = 0x0004
private const val ACC_STATIC = 0x0008
private const val ACC_FINAL = 0x0010
private const val ACC_INTERFACE = 0x0200
private const val ACC_ABSTRACT = 0x0400
private const val ACC_SYNTHETIC = 0x1000

/**
 * What of a compiled class other compiled code can come to depend on: its name, what it extends, and every member
 * that is not private. Read straight from the class file, which has kept one layout since there have been class files.
 */
internal class ClassSignature(
	val name: String,
	private val access: Int,
	private val superName: String?,
	private val interfaces: List<String>,
	private val members: List<String>,
) {

	/**
	 * Classes the compiler makes up for a lambda or a `when` are named by where they stand in a file, and nothing
	 * outside of that file is compiled against them.
	 */
	val isNameless: Boolean
		get() = access and ACC_SYNTHETIC != 0 || NAMELESS.containsMatchIn(name)

	fun describe(out: StringBuilder) {
		out.append(if (access and ACC_INTERFACE != 0) "interface " else "class ").append(name)
		out.append(' ').append(flags(access)).append('\n')
		if (superName != null && superName != "java/lang/Object") {
			out.append("  extends ").append(superName).append('\n')
		}
		for (i in interfaces) {
			out.append("  implements ").append(i).append('\n')
		}
		for (m in members) {
			out.append("  ").append(m).append('\n')
		}
	}

	companion object {

		private val NAMELESS = Regex("""\$\d""")

		fun read(file: File): ClassSignature = DataInputStream(file.inputStream().buffered()).use { data ->
			check(data.readInt() == 0xCAFEBABE.toInt()) { "$file is not a class file" }
			data.readUnsignedShort()
			data.readUnsignedShort()
			val count = data.readUnsignedShort()
			val strings = arrayOfNulls<String>(count)
			val classes = IntArray(count)
			var i = 1
			while (i < count) {
				when (val tag = data.readUnsignedByte()) {
					1 -> strings[i] = data.readUTF()
					3, 4, 9, 10, 11, 12, 17, 18 -> data.readInt()
					5, 6 -> {
						data.readLong()
						i++ // these two take up two places
					}

					7 -> classes[i] = data.readUnsignedShort()
					8, 16, 19, 20 -> data.readUnsignedShort()
					15 -> {
						data.readUnsignedByte()
						data.readUnsignedShort()
					}

					else -> error("Unknown constant $tag in $file")
				}
				i++
			}
			val access = data.readUnsignedShort()
			val name = strings[classes[data.readUnsignedShort()]]!!
			val superIndex = data.readUnsignedShort()
			val superName = if (superIndex == 0) null else strings[classes[superIndex]]
			val interfaces = List(data.readUnsignedShort()) { strings[classes[data.readUnsignedShort()]]!! }
			val members = ArrayList<String>()
			for (kind in arrayOf("field", "method")) {
				repeat(data.readUnsignedShort()) {
					val memberAccess = data.readUnsignedShort()
					val memberName = strings[data.readUnsignedShort()]
					val descriptor = strings[data.readUnsignedShort()]
					repeat(data.readUnsignedShort()) {
						data.readUnsignedShort()
						val length = data.readInt()
						check(data.skipBytes(length) == length) { "$file is cut short" }
					}
					if (memberAccess and ACC_PRIVATE == 0) {
						members += "$kind $memberName $descriptor ${flags(memberAccess)}"
					}
				}
			}
			ClassSignature(name, access, superName, interfaces.sorted(), members.sorted())
		}

		private fun flags(access: Int): String = buildList {
			add(
				when {
					access and ACC_PUBLIC != 0 -> "public"
					access and ACC_PROTECTED != 0 -> "protected"
					else -> "package"
				},
			)
			if (access and ACC_STATIC != 0) add("static")
			if (access and ACC_ABSTRACT != 0) add("abstract")
			if (access and ACC_FINAL != 0) add("final")
		}.joinToString(" ", "[", "]")
	}
}
