package org.koitharu.kotatsu.parsers.site.mangareader.en

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser

@MangaSourceParser("ATHREASCANS", "AthreaScans", "en")
internal class AthreaScans(context: MangaLoaderContext) :
    MangaReaderParser(context, MangaParserSource.ATHREASCANS, "athreascans.com", pageSize = 20, searchPageSize = 10) {

    // The default "ts_reader" also matches an earlier inline script that uses ts_reader_control.
    override val selectTestScript = "script:containsData(ts_reader.run)"
}
