package tasks.bundle

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.File
import javax.xml.parsers.SAXParserFactory

/**
 * In which version of Android every class, method and field of the platform first appeared, as the SDK writes it in
 * its `api-versions.xml`.
 */
internal class PlatformLevels(file: File) {

	private class Parent(val name: String, val since: Double)

	private class ApiClass(val since: Double) {
		val parents = ArrayList<Parent>()

		/** Methods by name and descriptor, fields by name; a member that says nothing has the level of its class. */
		val members = HashMap<String, Double>()
	}

	private val classes = HashMap<String, ApiClass>()

	init {
		val factory = SAXParserFactory.newInstance()
		factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
		factory.newSAXParser().parse(
			file,
			object : DefaultHandler() {

				private var current: ApiClass? = null

				override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
					val since = attributes.getValue("since")?.toDoubleOrNull()
					when (qName) {
						"class" -> {
							val created = ApiClass(since ?: 1.0)
							classes[attributes.getValue("name")] = created
							current = created
						}

						"extends", "implements" -> current?.parents?.add(
							Parent(attributes.getValue("name"), since ?: 0.0),
						)

						"method", "field" -> current?.let {
							it.members[attributes.getValue("name")] = since ?: it.since
						}
					}
				}

				override fun endElement(uri: String?, localName: String?, qName: String) {
					if (qName == "class") {
						current = null
					}
				}
			},
		)
	}

	/** @return the level of Android a class came with, or null if the platform does not list it */
	fun classLevel(name: String): Double? = classes[name]?.since

	/** Every class and interface above [name], however far up. */
	fun ancestors(name: String): Set<String> {
		val result = HashSet<String>()
		fun climb(current: String) {
			for (parent in classes[current]?.parents.orEmpty()) {
				if (result.add(parent.name)) {
					climb(parent.name)
				}
			}
		}
		climb(name)
		return result
	}

	/**
	 * @param member a method as `name(descriptor)`, or a field by its name
	 * @return the level of Android the member came with when looked for in [owner] and above it, or null if it is
	 * not listed anywhere there
	 */
	fun memberLevel(owner: String, member: String): Double? {
		val apiClass = classes[owner] ?: return null
		apiClass.members[member]?.let { return it }
		var best: Double? = null
		for (parent in apiClass.parents) {
			val found = memberLevel(parent.name, member) ?: continue
			// Only there from the day the class was given that parent
			val level = maxOf(found, parent.since)
			if (best == null || level < best) {
				best = level
			}
		}
		return best
	}

	companion object {

		fun describe(level: Double): String =
			if (level == Math.floor(level)) level.toInt().toString() else level.toString()

		/** The signature of a member of a keep rule, in the form [memberLevel] asks for. */
		fun memberOf(rule: String): String? {
			val line = rule.trim().removeSuffix(";").trim()
			val open = line.indexOf('(')
			if (open < 0) {
				return line.substringAfterLast(' ')
			}
			val head = line.substring(0, open).trim().split(' ').filter { it.isNotEmpty() }
			val name = head.lastOrNull() ?: return null
			val returns = if (name == "<init>") "V" else descriptorOf(head.getOrNull(head.size - 2) ?: return null)
			val close = line.lastIndexOf(')')
			val parameters = line.substring(open + 1, close).split(',').map { it.trim() }.filter { it.isNotEmpty() }
			return name + "(" + parameters.joinToString("") { descriptorOf(it) } + ")" + returns
		}

		private fun descriptorOf(type: String): String {
			var base = type
			var dimensions = 0
			while (base.endsWith("[]")) {
				dimensions++
				base = base.removeSuffix("[]")
			}
			val descriptor = when (base) {
				"int" -> "I"
				"boolean" -> "Z"
				"byte" -> "B"
				"char" -> "C"
				"short" -> "S"
				"long" -> "J"
				"float" -> "F"
				"double" -> "D"
				"void" -> "V"
				else -> "L" + base.replace('.', '/') + ";"
			}
			return "[".repeat(dimensions) + descriptor
		}
	}
}
