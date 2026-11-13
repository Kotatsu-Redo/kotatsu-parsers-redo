package org.koitharu.kotatsu.parsers.model

import java.io.Serializable

/**
 * A source served by one of the parsers of this library.
 *
 * Which sources there are is not fixed when an application is built: a newer set of parsers, see
 * [org.koitharu.kotatsu.parsers.ParsersBundle], can bring some the application was not compiled with. So a source is
 * told from another by its [name] alone, and two objects standing for the same source are equal even when they come
 * from two different sets of parsers.
 */
public abstract class ParserMangaSource(
	final override val name: String,
	public val title: String,
	public val locale: String,
	public val contentType: ContentType,
	public val isBroken: Boolean,
) : MangaSource, Serializable {

	final override fun equals(other: Any?): Boolean {
		return this === other || (other is ParserMangaSource && other.name == name)
	}

	final override fun hashCode(): Int = name.hashCode()

	final override fun toString(): String = name

	/**
	 * A source can be written out, as the entry of an enum it used to be could: errors carry one, and an application
	 * keeps errors across its own restarts to show or to report them.
	 *
	 * What is written is this class and never the one of the parsers the source came from, which whoever reads it
	 * back may not have, or may have another version of. What is read back says the same of the source and is equal
	 * to it, without being the object the parsers in use hold.
	 */
	protected fun writeReplace(): Any = if (this is Detached) this else Detached(name, title, locale, contentType, isBroken)

	private class Detached(
		name: String,
		title: String,
		locale: String,
		contentType: ContentType,
		isBroken: Boolean,
	) : ParserMangaSource(name, title, locale, contentType, isBroken) {

		private companion object {

			private const val serialVersionUID = 1L
		}
	}

	private companion object {

		private const val serialVersionUID = 1L
	}
}
