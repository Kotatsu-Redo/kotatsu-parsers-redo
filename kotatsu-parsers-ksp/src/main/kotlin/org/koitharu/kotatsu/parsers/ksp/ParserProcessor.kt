package org.koitharu.kotatsu.parsers.ksp

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.isAbstract
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSVisitorVoid
import com.google.devtools.ksp.validate
import java.io.File
import java.io.Writer
import java.util.*

class ParserProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val options: Map<String, String>,
) : SymbolProcessor {
    private val availableLocales = Locale.getAvailableLocales().toSet()
    private val sourceNamePattern = Regex("[A-Z_][A-Z0-9_]{3,}")

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols = resolver.getSymbolsWithAnnotation("org.koitharu.kotatsu.parsers.MangaSourceParser")
        val ret = symbols.filterNot { it.validate() }.toList()
        if (!symbols.iterator().hasNext()) {
            return ret
        }
        val dependencies = Dependencies.ALL_FILES
        val factoryFile =
            try {
                codeGenerator.createNewFile(
                    dependencies = dependencies,
                    packageName = "org.koitharu.kotatsu.parsers",
                    fileName = "MangaParserFactory",
                )
            } catch (e: FileAlreadyExistsException) {
                logger.warn(e.toString(), null)
                null
            }
        val sourcesFile =
            try {
                codeGenerator.createNewFile(
                    dependencies = dependencies,
                    packageName = "org.koitharu.kotatsu.parsers.model",
                    fileName = "MangaParserSource",
                )
            } catch (e: FileAlreadyExistsException) {
                logger.warn(e.toString(), null)
                null
            }
        val visitor = ParserVisitor()
        symbols
            .filter { it is KSClassDeclaration && it.validate() }
            .forEach { it.accept(visitor, Unit) }
        val entries = visitor.entries
        sourcesFile?.writer()?.use { writeSources(it, entries) }
        factoryFile?.writer()?.use { writeFactory(it, entries) }
        if (sourcesFile != null || factoryFile != null) {
            writeSummary(entries.size)
            // Only a round that generated has seen every source: a later one may be shown some of them
            writeSourcesMap(entries)
        }
        return ret
    }

    /**
     * The sources as a class with one object of it for each, written `MangaParserSource.NAME` the way the entries of
     * an enum are. An enum is what this used to be, and cannot be any more: its entries are fixed in whatever is
     * compiled against it, and an application has to be able to load parsers with sources it was not compiled with.
     */
    private fun writeSources(writer: Writer, entries: List<Entry>) {
        val chunks = entries.chunked(CHUNK_SIZE)
        writer.write(
            """
			package org.koitharu.kotatsu.parsers.model

			import org.koitharu.kotatsu.parsers.DirectParsersAccess
			import java.util.Collections

			@DirectParsersAccess
			public class MangaParserSource private constructor(
				name: String,
				title: String,
				locale: String,
				contentType: ContentType,
				isBroken: Boolean,
			) : ParserMangaSource(name, title, locale, contentType, isBroken) {

				public companion object {

					private val all: Array<MangaParserSource> = arrayOf(

            """.trimIndent(),
        )
        for (i in chunks.indices) {
            writer.write("\t\t\t*sources$i(),\n")
        }
        writer.write("\t\t)\n\n")
        writer.write(
            "\t\tprivate val byName: Map<String, MangaParserSource> = " +
                "all.associateByTo(HashMap(all.size * 2)) { it.name }\n\n",
        )
        writer.write(
            "\t\tpublic val entries: List<MangaParserSource> = Collections.unmodifiableList(all.asList())\n\n",
        )
        writer.write("\t\tpublic fun find(name: String): MangaParserSource? = byName[name]\n\n")
        writer.write("\t\tpublic fun valueOf(name: String): MangaParserSource {\n")
        writer.write(
            "\t\t\treturn byName[name] ?: throw IllegalArgumentException(\"No manga source named \$name\")\n",
        )
        writer.write("\t\t}\n\n")
        for ((index, entry) in entries.withIndex()) {
            writer.write("\t\t${entry.deprecation}public val ${entry.name}: MangaParserSource get() = all[$index]\n")
        }
        for ((i, chunk) in chunks.withIndex()) {
            writer.write("\n\t\tprivate fun sources$i(): Array<MangaParserSource> = arrayOf(\n")
            for (entry in chunk) {
                writer.write(
                    "\t\t\tMangaParserSource(\"${entry.name}\", \"${entry.title}\", " +
                        "\"${entry.locale}\"${entry.localeComment}, ${entry.type}, ${entry.isBroken}),\n",
                )
            }
            writer.write("\t\t)\n")
        }
        writer.write("\t}\n}\n")
    }

    /**
     * Looked up by name, a few hundred sources to a function: one function for all of them would be past what a method
     * may hold before long.
     */
    private fun writeFactory(writer: Writer, entries: List<Entry>) {
        val chunks = entries.chunked(CHUNK_SIZE)
        writer.write(
            """
			package org.koitharu.kotatsu.parsers

			import org.koitharu.kotatsu.parsers.model.MangaParserSource
			import org.koitharu.kotatsu.parsers.core.MangaParserWrapper

			/** The parser of a source as its class makes it, with nothing put around it. */
			internal fun MangaParserSource.newRawParser(context: MangaLoaderContext): MangaParser {
				val parser = ${chunks.indices.joinToString(" ?: ") { "newParser$it(name, context)" }}
					?: throw IllegalArgumentException("No parser for manga source ${'$'}name")
				require(parser.source == this) {
					"Cannot instantiate manga parser: ${'$'}name mapped to ${'$'}{parser.source}"
				}
				return parser
			}

			internal fun MangaParserSource.newParser(context: MangaLoaderContext): MangaParser {
				return MangaParserWrapper(newRawParser(context))
			}

            """.trimIndent(),
        )
        for ((i, chunk) in chunks.withIndex()) {
            writer.write(
                "\nprivate fun newParser$i(name: String, context: MangaLoaderContext): MangaParser? = when (name) {\n",
            )
            for (entry in chunk) {
                writer.write("\t\"${entry.name}\" -> ${entry.className}(context)\n")
            }
            writer.write("\telse -> null\n}\n")
        }
    }

    private fun writeSummary(totalCount: Int) {
        val file = File(options["summaryOutputDir"] ?: return, "summary.yaml")
        file.writeText("total: $totalCount")
    }

    /**
     * Which file each source is made of, for deciding which sources a change to the code can have touched. It is a side
     * file and not a generated one, so that it stays out of the jar.
     */
    private fun writeSourcesMap(entries: List<Entry>) {
        val file = File(options["sourcesMapFile"] ?: return)
        file.parentFile?.mkdirs()
        file.writeText(
            entries.joinToString(separator = "") { entry ->
                val broken = if (entry.isBroken) 1 else 0
                "${entry.name}\t$broken\t${entry.ownFile}\t${entry.superFiles.joinToString(";")}\n"
            },
        )
    }

    private fun relativePath(path: String): String {
        val normalized = path.replace('\\', '/')
        val root = options["projectDir"]?.replace('\\', '/')?.trimEnd('/') ?: return normalized
        return normalized.removePrefix("$root/")
    }

    private class Entry(
        val name: String,
        val title: String,
        val locale: String,
        val localeComment: String,
        val type: Any?,
        val isBroken: Boolean,
        val deprecation: String,
        val className: String,
        val ownFile: String,
        val superFiles: List<String>,
    )

    private inner class ParserVisitor : KSVisitorVoid() {
        private val titles = HashMap<String, String>()
        private val names = HashMap<String, String>()

        val entries = ArrayList<Entry>()

        override fun visitClassDeclaration(
            classDeclaration: KSClassDeclaration,
            data: Unit,
        ) {
            if (classDeclaration.classKind != ClassKind.CLASS || classDeclaration.isAbstract()) {
                logger.error("Only non-abstract can be annotated with @MangaSourceParser", classDeclaration)
            }
            val annotation = classDeclaration.annotations.single { it.shortName.asString() == "MangaSourceParser" }
            val deprecation = classDeclaration.annotations.singleOrNull { it.shortName.asString() == "Deprecated" }
            val isBroken = classDeclaration.annotations.any { it.shortName.asString() == "Broken" }
            val name = annotation.arguments.single { it.name?.asString() == "name" }.value as String
            val title = annotation.arguments.single { it.name?.asString() == "title" }.value as String
            val locale = annotation.arguments.single { it.name?.asString() == "locale" }.value as String
            val type = annotation.arguments.single { it.name?.asString() == "type" }.value
            val localeObj = if (locale.isEmpty()) null else Locale(locale)
            val localeTitle = localeObj?.getDisplayLanguage(localeObj)
            if (localeObj != null && localeObj !in availableLocales) {
                logger.error("Manga source $name has wrong locale: $localeTitle")
            }

            if (!sourceNamePattern.matches(name)) {
                logger.error("Manga source name must be uppercase: $name")
            }

            val constructor = classDeclaration.primaryConstructor
            if (constructor == null || constructor.parameters.count { !it.hasDefault } != 1) {
                logger.error(
                    "Class with @MangaSourceParser must have a primary constructor with one parameter",
                    classDeclaration,
                )
            }
            val className = checkNotNull(classDeclaration.qualifiedName?.asString()) { "Class name is null" }

            // An enum used to refuse two entries of one name. Nothing would now, and the second would never be reached
            val prevNameClass = names.put(name, className)
            if (prevNameClass != null) {
                logger.error("Manga source $name is declared by both $prevNameClass and $className", classDeclaration)
            }
            val prevTitleClass = titles.put(title, className)
            if (prevTitleClass != null) {
                logger.warn("Source title duplication: \"$title\" is assigned to both $prevTitleClass and $className")
            }

            val deprecationString =
                if (deprecation != null) {
                    val reason =
                        deprecation.arguments
                            .find { it.name?.asString() == "message" }
                            ?.value
                            ?.toString() ?: "Unknown reason"
                    "@Deprecated(\"$reason\") "
                } else {
                    ""
                }
            entries += Entry(
                name = name,
                title = title,
                locale = locale,
                localeComment = localeTitle?.toTitleCase(localeObj)?.let { " /* $it */" }.orEmpty(),
                type = type,
                isBroken = isBroken,
                deprecation = deprecationString,
                className = className,
                ownFile = classDeclaration.containingFile?.let { relativePath(it.filePath) }.orEmpty(),
                // Classes of libraries and of the separately compiled contract have no file here and are left out
                superFiles = classDeclaration.getAllSuperTypes()
                    .mapNotNull { type -> type.declaration.containingFile?.let { relativePath(it.filePath) } }
                    .distinct()
                    .toList(),
            )
        }
    }

    private companion object {

        const val CHUNK_SIZE = 200
    }
}
