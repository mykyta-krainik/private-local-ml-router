package com.example.privacyrouter.pipeline.stage2

import android.content.Context
import android.os.Build
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextClassifier
import android.view.textclassifier.TextLinks
import androidx.annotation.RequiresApi
import com.example.privacyrouter.model.DetectionTier
import com.example.privacyrouter.model.PiiEntity
import com.example.privacyrouter.model.PiiType

/**
 * Tier 0 PII detector. Delegates to Android's system TextClassifier which natively
 * recognizes addresses, phone numbers, emails, URLs, and date-time expressions.
 */
class TextClassifierDetector(private val context: Context) {

    private val classifier: TextClassifier? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.getSystemService(TextClassificationManager::class.java)
                ?.textClassifier
        } else null
    }

    fun detect(query: String): List<PiiEntity> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return emptyList()
        return detectApi28(query)
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun detectApi28(query: String): List<PiiEntity> {
        val tc = classifier ?: return emptyList()
        val request = TextLinks.Request.Builder(query).build()
        val result = tc.generateLinks(request)
        return result.links.mapNotNull { link ->
            val piiType = bestType(link) ?: return@mapNotNull null
            PiiEntity(
                span = link.start until link.end,
                text = query.substring(link.start, link.end),
                type = piiType,
                confidence = link.getConfidenceScore(bestEntityKey(link) ?: return@mapNotNull null),
                source = DetectionTier.TIER_0,
            )
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun bestEntityKey(link: TextLinks.TextLink): String? {
        var best: String? = null
        var bestScore = 0f
        for (i in 0 until link.entityCount) {
            val entity = link.getEntity(i)
            val score = link.getConfidenceScore(entity)
            if (score > bestScore) {
                bestScore = score
                best = entity
            }
        }
        return best
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun bestType(link: TextLinks.TextLink): PiiType? =
        when (bestEntityKey(link)) {
            TextClassifier.TYPE_ADDRESS -> PiiType.ADDRESS
            TextClassifier.TYPE_PHONE -> PiiType.PHONE
            TextClassifier.TYPE_EMAIL -> PiiType.EMAIL
            TextClassifier.TYPE_DATE,
            TextClassifier.TYPE_DATE_TIME -> PiiType.DATE_TIME
            TextClassifier.TYPE_URL -> PiiType.MISC
            else -> null
        }
}
