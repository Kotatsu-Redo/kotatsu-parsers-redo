package org.koitharu.kotatsu.parsers.site.pt

import okhttp3.Headers
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.site.mangotheme.MangoThemeParser

@MangaSourceParser("IMPERIODABRITANNIA", "Imp\u00e9rio da Britannia", "pt")
internal class ImperiodaBritannia(context: MangaLoaderContext) : MangoThemeParser(
	context = context,
	source = MangaParserSource.IMPERIODABRITANNIA,
	domain = "imperiodabritannia.net",
	cdnUrl = "https://cdn.imperiodabritannia.net",
	encryptionKey = "mangotoons_encryption_key_2025",
	apiBaseUrl = "https://api.imperiodabritannia.net/api",
	webMangaPathSegment = "manga",
	availableTagsSet = linkedSetOf<MangaTag>().apply {
		addTag("48", "+18")
		addTag("2", "A\u00e7\u00e3o")
		addTag("108", "Adapta\u00e7\u00e3o")
		addTag("64", "Adulto")
		addTag("96", "Alien\u00edgenas")
		addTag("33", "Apocalipse")
		addTag("58", "Apocal\u00edptico")
		addTag("24", "Artes Marciais")
		addTag("3", "Aventura")
		addTag("60", "Bullying")
		addTag("65", "c")
		addTag("6", "Com\u00e9dia")
		addTag("51", "Crime")
		addTag("28", "Culinaria")
		addTag("112", "Culin\u00e1ria")
		addTag("23", "Cultivo")
		addTag("91", "Delinquente")
		addTag("114", "Dem\u00f4nios")
		addTag("39", "Dem\u00f4nios")
		addTag("9", "Doujin")
		addTag("101", "Dragon lan scan")
		addTag("92", "Drama")
		addTag("25", "Dungeon")
		addTag("42", "Ecchi")
		addTag("56", "Escolar")
		addTag("38", "Esportes")
		addTag("4", "Fantasia")
		addTag("41", "Fatia da Vida/Slice of Life")
		addTag("40", "Fic\u00e7\u00e3o Cient\u00edfica")
		addTag("55", "Finalizado")
		addTag("97", "Full Collor")
		addTag("93", "Full Color")
		addTag("68", "Garota Monstro")
		addTag("98", "Garota Monstro.")
		addTag("69", "Garotas M\u00e1gicas")
		addTag("70", "Gender Bender")
		addTag("71", "Genderswap")
		addTag("72", "Girl Love")
		addTag("59", "Gore")
		addTag("111", "Gore scan")
		addTag("44", "Har\u00e9m")
		addTag("73", "Hentai")
		addTag("74", "Her\u00f3i")
		addTag("11", "Hist\u00f3rico")
		addTag("75", "Horror")
		addTag("19", "Isekai")
		addTag("46", "Jogo")
		addTag("95", "Jogos")
		addTag("17", "Josei")
		addTag("5", "Loli")
		addTag("35", "Luta")
		addTag("36", "m\u00e1fia")
		addTag("29", "Magia")
		addTag("8", "Manga")
		addTag("99", "Mang\u00e1")
		addTag("45", "manhua")
		addTag("7", "Manhua")
		addTag("76", "Manhwa")
		addTag("77", "Mecha")
		addTag("105", "Med\u00edocre")
		addTag("107", "Mediocre scan")
		addTag("103", "Med\u00edocre scan")
		addTag("14", "Militar")
		addTag("43", "Mist\u00e9rio")
		addTag("54", "Moderno")
		addTag("100", "Monstro")
		addTag("37", "Monstros")
		addTag("31", "Murim")
		addTag("78", "M\u00fasica")
		addTag("32", "Necromante")
		addTag("113", "Norte scan")
		addTag("18", "One-shot")
		addTag("49", "Oneshot")
		addTag("110", "One Shot")
		addTag("52", "Policial")
		addTag("94", "P\u00f3s Apocal\u00edptico")
		addTag("27", "Psicol\u00f3gico")
		addTag("67", "Realidade Virtua")
		addTag("106", "Realidade Virtual")
		addTag("21", "Reencarna\u00e7\u00e3o")
		addTag("47", "Regress\u00e3o")
		addTag("20", "Retorno")
		addTag("79", "Romance")
		addTag("117", "Sagrado imp\u00e9rio da britannia")
		addTag("80", "Samurai")
		addTag("62", "Sci-Fi")
		addTag("34", "Seinen")
		addTag("109", "Sempre ao seu lado scan")
		addTag("16", "Shoujo")
		addTag("81", "Shoujo Ai")
		addTag("15", "Shounen")
		addTag("63", "Shounen Ai")
		addTag("22", "Sistema")
		addTag("116", "Skarla scan")
		addTag("61", "Slice of Life")
		addTag("13", "Sobrenatural")
		addTag("82", "Sobreviv\u00eancia")
		addTag("30", "SuperPoder")
		addTag("57", "Super Poderes")
		addTag("10", "Suspense")
		addTag("83", "Terror")
		addTag("1", "teste")
		addTag("66", "Teste99")
		addTag("84", "Thriller")
		addTag("26", "Trag\u00e9dia")
		addTag("102", "Updating")
		addTag("104", "Verdinha")
		addTag("53", "Viagem no Tempo")
		addTag("12", "Vida escolar")
		addTag("85", "Video Game")
		addTag("86", "Vingan\u00e7a")
		addTag("87", "Web Comic")
		addTag("88", "Webtoon")
		addTag("89", "Wuxia")
		addTag("90", "Xianxia")
		addTag("50", "Yuri")
		addTag("115", "Zero scan")
	},
	statusIdsByState = mapOf(
		MangaState.ONGOING to listOf("1", "6"),
		MangaState.FINISHED to listOf("3"),
		MangaState.PAUSED to listOf("2", "5"),
		MangaState.ABANDONED to listOf("4"),
	),
	formatIdsByType = mapOf(
		ContentType.COMICS to listOf("20"),
		ContentType.HENTAI to listOf("23"),
		ContentType.MANGA to listOf("24"),
		ContentType.MANHUA to listOf("17"),
		ContentType.MANHWA to listOf("25", "26"),
		ContentType.NOVEL to listOf("18"),
	),
	adultFormatIds = setOf("23"),
) {
	override fun getApiRequestHeaders(): Headers = super.getApiRequestHeaders().newBuilder()
		.add("X-Noencryptionbritta", "1")
		.add("X-API-Token", "bunker_api_token_secreto_2025")
		.build()
}

private fun MutableSet<MangaTag>.addTag(id: String, title: String) {
	add(MangaTag(key = id, title = title, source = MangaParserSource.IMPERIODABRITANNIA))
}
