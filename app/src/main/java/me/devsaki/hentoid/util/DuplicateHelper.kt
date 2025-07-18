package me.devsaki.hentoid.util

import android.content.Context
import android.graphics.Bitmap
import androidx.core.net.toUri
import androidx.core.util.Consumer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.devsaki.hentoid.core.BiConsumer
import me.devsaki.hentoid.database.CollectionDAO
import me.devsaki.hentoid.database.domains.Content
import me.devsaki.hentoid.database.domains.DuplicateEntry
import me.devsaki.hentoid.enums.AttributeType
import me.devsaki.hentoid.util.file.getDocumentFromTreeUriString
import me.devsaki.hentoid.util.file.getInputStream
import me.devsaki.hentoid.util.image.ImagePHash
import me.devsaki.hentoid.util.image.decodeSampledBitmapFromStream
import me.devsaki.hentoid.util.image.similarity
import me.devsaki.hentoid.util.string_similarity.StringSimilarity
import timber.log.Timber
import java.io.IOException
import java.io.InputStream
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

// Thresholds according to the "sensitivity" setting
// @48-bit resolution, according to calibration tests
private val COVER_THRESHOLDS = doubleArrayOf(0.8, 0.85, 0.9)
private val TEXT_THRESHOLDS = doubleArrayOf(0.78, 0.8, 0.85)
private val TOTAL_THRESHOLDS = doubleArrayOf(0.8, 0.85, 0.9)
private const val COVER_WORK_RESOLUTION = 48

private val TITLE_CHAPTER_WORDS = listOf(
    "chapter",
    "chap",
    "case",
    "after",
    "before",
    "prologue",
    "prelude",
    "final",
    "part",
    "update",
    "gaiden",
    "issue",
    "volume",
    "vol",
    "first",
    "second",
    "third",
    "fourth",
    "fifth",
    "1st",
    "2nd",
    "3rd",
    "4th",
    "5th",
    "zenpen",
    "全編",
    "chuuhen",
    "中編",
    "kouhen",
    "後編",
    "ex",
    // Circled numerals (yes, some books do use them)
    "①",
    "②",
    "③",
    "④",
    "⑤",
    // Roman numerals (yes, some books do use them)
    "i",
    "v",
    "x",
)


fun getHashEngine(): ImagePHash {
    return getHashEngine(COVER_WORK_RESOLUTION)
}

private fun getHashEngine(resolution: Int = COVER_WORK_RESOLUTION): ImagePHash {
    return ImagePHash(resolution, 8)
}

suspend fun indexCovers(
    context: Context,
    dao: CollectionDAO,
    stopped: AtomicBoolean,
    info: Consumer<Content>,
    progress: BiConsumer<Int, Int>,
    error: Consumer<Throwable>
) {
    withContext(Dispatchers.IO) {
        val hashEngine = getHashEngine()
        val contentToIndex = dao.selectContentWithUnhashedCovers()
        val nbContent = contentToIndex.size

        for ((index, c) in contentToIndex.withIndex()) {
            try {
                info.accept(c)
                indexContent(context, dao, c, hashEngine)
                progress.invoke(index + 1, nbContent)
            } catch (t: Throwable) {
                // Don't break the loop
                error.accept(t)
            }
            if (stopped.get()) break
        }
        progress.invoke(nbContent, nbContent)
    }
}

private fun indexContent(
    context: Context,
    dao: CollectionDAO,
    content: Content,
    hashEngine: ImagePHash,
) {
    val bitmap = getCoverBitmapFromContent(context, content)
    val pHash = calcPhash(hashEngine, bitmap)
    bitmap?.recycle()
    savePhash(context, dao, content, pHash)
}

fun getCoverBitmapFromContent(context: Context, content: Content): Bitmap? {
    if (content.cover.fileUri.isEmpty()) return null

    try {
        getInputStream(context, content.cover.fileUri.toUri())
            .use {
                return getCoverBitmapFromStream(it)
            }
    } catch (e: IOException) {
        Timber.w(e) // Doesn't break the loop
        return null
    }
}

fun getCoverBitmapFromStream(stream: InputStream): Bitmap? {
    return decodeSampledBitmapFromStream(
        stream,
        COVER_WORK_RESOLUTION,
        COVER_WORK_RESOLUTION
    )
}

fun calcPhash(hashEngine: ImagePHash, bitmap: Bitmap?): Long {
    return if (null == bitmap) Long.MIN_VALUE
    else hashEngine.calcPHash(bitmap)
}

private fun savePhash(context: Context, dao: CollectionDAO, content: Content, pHash: Long) {
    content.cover.imageHash = pHash
    // Update the picture in DB
    dao.insertImageFile(content.cover)
    // The following block has to be abandoned if the cost of retaining all Content in memory is too high
    try {
        // Update the book JSON if the book folder still exists
        if (content.storageUri.isNotEmpty()) {
            val folder =
                getDocumentFromTreeUriString(context, content.storageUri)
            if (folder != null) {
                if (content.jsonUri.isNotEmpty()) updateJson(context, content)
                else createJson(context, content)
            }
        }
    } catch (e: IOException) {
        Timber.w(e) // Doesn't break the loop
    }
}

fun processContent(
    reference: DuplicateCandidate,
    candidate: DuplicateCandidate,
    useTitle: Boolean,
    useCover: Boolean,
    useSameArtist: Boolean,
    useSameLanguage: Boolean,
    ignoreChapters: Boolean,
    sensitivity: Int,
    textComparator: StringSimilarity
): DuplicateEntry? {
    var titleScore = -1f
    var coverScore = -1f
    var artistScore = -1f

    // Remove if not same language
    if (useSameLanguage && !containsSameLanguage(
            reference.countryCodes,
            candidate.countryCodes
        )
    ) return null
    if (useCover) {
        coverScore = computeCoverScore(
            reference.coverHash, candidate.coverHash,
            sensitivity
        )
        // Ignored cover
        if (coverScore == -2f) return null
    }
    if (useTitle) titleScore = computeTitleScore(
        textComparator,
        reference,
        candidate,
        ignoreChapters,
        sensitivity
    )
    if (useSameArtist) artistScore =
        computeArtistScore(reference.artistsCleanup, candidate.artistsCleanup)
    val result = DuplicateEntry(
        reference.id,
        reference.size,
        candidate.id,
        candidate.size,
        titleScore,
        coverScore,
        artistScore,
        0
    )
    return if (result.calcTotalScore() >= TOTAL_THRESHOLDS[sensitivity]) result else null
}


private fun containsSameLanguage(
    referenceCodes: List<String>?,
    candidateCodes: List<String>?
): Boolean {
    if (!referenceCodes.isNullOrEmpty() && !candidateCodes.isNullOrEmpty()) {
        for (refCode in referenceCodes) {
            if (candidateCodes.contains(refCode)) return true
        }
        return false
    }
    return true
}

private fun computeCoverScore(
    referenceHash: Long,
    candidateHash: Long,
    sensitivity: Int
): Float {
    // Don't analyze anything if covers have not been hashed (will be done on next iteration)
    if (0L == referenceHash || 0L == candidateHash) return -2f
    // Ignore unhashable covers
    if (Long.MIN_VALUE == referenceHash || Long.MIN_VALUE == candidateHash) return -1f

    val preCoverScore = similarity(referenceHash, candidateHash)
    return if (preCoverScore >= COVER_THRESHOLDS[sensitivity]) preCoverScore else 0f
}

fun computeTitleScore(
    textComparator: StringSimilarity,
    reference: DuplicateCandidate,
    candidate: DuplicateCandidate,
    ignoreChapters: Boolean,
    sensitivity: Int
): Float {
    val similarity1 =
        textComparator.similarity(reference.titleCleanup, candidate.titleCleanup)
    if (ignoreChapters) {
        // Perfect match
        if (similarity1 > 0.995) return similarity1.toFloat()
        // Other cases : check if both titles are chapters or sequels
        return if (similarity1 > TEXT_THRESHOLDS[sensitivity]) {
            val similarity2 =
                textComparator.similarity(reference.titleNoDigits, candidate.titleNoDigits)
            // Cleaned up versions are identical
            // => most probably a chapter variant
            if (similarity2 > similarity1 && similarity2 > 0.995)
                return processChapterVariants(reference, candidate, similarity1.toFloat())
            // Very little difference between cleaned up and original version
            // => not a chapter variant
            if (similarity2 - similarity1 < 0.01) {
                similarity1.toFloat()
            } else { // Most probably a chapter variant
                return processChapterVariants(reference, candidate, similarity1.toFloat())
            }
        } else {
            0f // Below threshold
        }
    } else return if (similarity1 >= TEXT_THRESHOLDS[sensitivity]) similarity1.toFloat() else 0f
}

private fun processChapterVariants(
    reference: DuplicateCandidate,
    candidate: DuplicateCandidate,
    similarity: Float
): Float {
    // No numbers to compare (e.g. "gaiden" / "ex")
    if (-1 == reference.maxChapterBound || -1 == candidate.maxChapterBound) return 0f

    // Chapter numbers overlap (two variants) => don't ignore it, that's an actual duplicate
    if (reference.minChapterBound >= candidate.minChapterBound && reference.minChapterBound <= candidate.maxChapterBound) return similarity
    if (candidate.minChapterBound >= reference.minChapterBound && candidate.minChapterBound <= reference.maxChapterBound) return similarity

    return 0f
}

fun sanitizeTitle(title: String): Triple<String, Int, Int> {
    // Compute min and max chapter value
    // These are to be :
    //  - Located in the last 20% of the title
    //  - Separated by at most 4 characters
    var minChapter: Triple<Int, Int, Int>? = null
    var maxChapter: Triple<Int, Int, Int>? = null
    val digitsMap = locateDigits(title).reversed()
    digitsMap.forEach {
        if (it.second >= title.length * 0.8 && null == maxChapter) maxChapter = it
        else maxChapter?.let { max ->
            if (it.second >= max.first - 5) minChapter = it
        }
    }
    if (maxChapter != null && null == minChapter) minChapter = maxChapter
    val minChapterValue = minChapter?.third ?: -1
    val maxChapterValue = maxChapter?.third ?: -1

    // Sanitize the title
    var result = removeDigits(title)
    for (s in TITLE_CHAPTER_WORDS) result = result.replace(s, "")
    return Triple(result, minChapterValue, maxChapterValue)
}

private fun computeArtistScore(
    referenceArtistsCleanup: List<String>?,
    candidateArtistsCleanup: List<String>?
): Float {
    if (!candidateArtistsCleanup.isNullOrEmpty() && !referenceArtistsCleanup.isNullOrEmpty()) {
        for (candidateArtist in candidateArtistsCleanup) {
            for (refArtist in referenceArtistsCleanup) {
                if (refArtist == candidateArtist) return 1f
                if (isTransposition(refArtist, candidateArtist)) return 1f
            }
        }
        return 0f // No match
    }
    return -1f // Nothing to match against
}

/**
 * Find if the given Content exists in the database, based on its URL and the URL of its images
 * A duplicate is found when all URLs match
 * Returns found duplicate or null if none is found
 */
fun findDuplicateContentByUrl(content: Content, dao: CollectionDAO): Content? {
    val candidates = dao.selectContentsByUrl(content.site, content.url)
    candidates.forEach {
        if (isAllPagesMatch(content, it)) return it
    }
    return null
}

/**
 * Find if the given Content exists in the database, based on its URL and the URL of its images
 * A duplicate is found when all URLs match
 * Returns found duplicate or null if none is found
 */
fun findDuplicateContentByQtyPageAndSize(content: Content, dao: CollectionDAO): Content? {
    val candidates = dao.selectContentsByQtyPageAndSize(content.qtyPages, content.size)
    candidates.forEach {
        if (isAllPagesMatch(content, it)) return it
    }
    return null
}

private fun isAllPagesMatch(c1: Content, c2: Content): Boolean {
    val sourceReadablePages = c1.imageList.filter { it.isReadable }
    val readablePages = c2.imageList.filter { it.isReadable }
    if (readablePages.size != sourceReadablePages.size) return false
    for (i in readablePages.indices) {
        if (sourceReadablePages[i].url != readablePages[i].url) return false
    }
    return true
}

class DuplicateCandidate(
    content: Content,
    useTitle: Boolean,
    useArtist: Boolean,
    useLanguage: Boolean,
    useCover: Boolean,
    ignoreChapters: Boolean,
    forceCoverHash: Long = Long.MIN_VALUE
) {
    val id = content.id
    val coverHash =
        if (!useCover) Long.MIN_VALUE else if (Long.MIN_VALUE == forceCoverHash) content.cover.imageHash else forceCoverHash
    val size = content.size
    val titleCleanup: String = if (useTitle) simplify(content.title) else ""
    val artistsCleanup: List<String>? =
        if (useArtist) content.attributeMap[AttributeType.ARTIST]?.map {
            simplify(it.name)
        } else Collections.emptyList()
    val countryCodes = if (useLanguage) content.attributeMap[AttributeType.LANGUAGE]?.map {
        LanguageHelper.getCountryCodeFromLanguage(it.name)
    } else Collections.emptyList()
    val titleNoDigits: String
    val minChapterBound: Int
    val maxChapterBound: Int

    init {
        if (useTitle && ignoreChapters) {
            val sanitizeResult = sanitizeTitle(titleCleanup)
            titleNoDigits = sanitizeResult.first
            minChapterBound = sanitizeResult.second
            maxChapterBound = sanitizeResult.third
        } else {
            titleNoDigits = ""
            minChapterBound = -1
            maxChapterBound = -1
        }
    }
}