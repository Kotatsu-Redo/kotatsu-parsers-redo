package org.koitharu.kotatsu.parsers.model.search

import androidx.collection.ArraySet
import org.koitharu.kotatsu.parsers.InternalParsersApi
import org.koitharu.kotatsu.parsers.model.search.QueryCriteria.*

@Deprecated("Too complex. Use MangaListFilterCapabilities instead")
@ExposedCopyVisibility
public data class MangaSearchQueryCapabilities internal constructor(
	public val capabilities: Set<SearchCapability>,
) {

	public constructor(vararg capabilities: SearchCapability) : this(ArraySet(capabilities))

	@InternalParsersApi
	public fun validate(query: MangaSearchQuery) {
		val strictFields = capabilities.filter { it.isExclusive }.mapTo(HashSet()) { it.field }
		val usedStrictFields = query.criteria.mapTo(HashSet()) { it.field }.intersect(strictFields)

		require(usedStrictFields.isEmpty() || query.criteria.size <= 1) {
			"Query contains multiple criteria, but at least one field (${usedStrictFields.joinToString()}) does not support multiple criteria."
		}
		for (criterion in query.criteria) {
			val capability = requireNotNull(capabilities.find { it.field == criterion.field }) {
				"Unsupported search field: ${criterion.field}"
			}

			require(criterion::class in capability.criteriaTypes) {
				"Unsupported search criterion: ${criterion::class.simpleName} for field ${criterion.field}"
			}

			// Ensure single value per criterion if supportMultiValue is false
			if (!capability.isMultiple) {
				when (criterion) {
					is Include<*> -> require(criterion.values.size <= 1) {
						"Multiple values are not allowed for field ${criterion.field}"
					}

					is Exclude<*> -> require(criterion.values.size <= 1) {
						"Multiple values are not allowed for field ${criterion.field}"
					}

					is Range<*> -> Unit // Range is always valid (from, to)
					is Match<*> -> Unit // Match always has a single value
				}
			}
		}
	}
}
