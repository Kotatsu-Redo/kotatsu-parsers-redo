package org.koitharu.kotatsu.parsers

import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.ArgumentsProvider
import org.junit.jupiter.params.provider.ArgumentsSource
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import java.util.stream.Stream

// Change 'names' to test specified parsers
private val names = arrayOf<String>()

@ArgumentsSource(MangaSourcesProvider::class)
internal annotation class MangaSources

internal class MangaSourcesProvider : ArgumentsProvider {

	override fun provideArguments(context: ExtensionContext): Stream<out Arguments> {
		// CI passes the sources to test this way (-PtestSources), which wins over the array above
		val requested = System.getProperty("parsers.test.sources")
			?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
			?: names.toList()
		// valueOf throws for an unknown name, which is wanted: a misspelt source must not quietly go untested
		val sources = if (requested.isEmpty()) {
			MangaParserSource.entries
		} else {
			requested.map(MangaParserSource::valueOf)
		}
		return sources.stream().map { Arguments.of(it) }
	}
}
