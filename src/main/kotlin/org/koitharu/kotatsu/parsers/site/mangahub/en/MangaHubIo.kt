package org.koitharu.kotatsu.parsers.site.mangahub.en

import org.koitharu.kotatsu.parsers.Broken
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.mangahub.MangaHubParser

@Broken("BLOCKED BY CLOUDFARE")

@MangaSourceParser("MANGAHUB_IO", "MangaHubIO", "en")
internal class MangaHubIo(context: MangaLoaderContext) :
	MangaHubParser(context, MangaParserSource.MANGAHUB_IO, "mangahub.io", "m01") {
    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(ConfigKey.InterceptCloudflare(defaultValue = true))
    }
}
