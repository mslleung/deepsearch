package io.deepsearch.domain.services

import io.deepsearch.domain.browser.IBrowserPage.DomBoundingBox
import io.deepsearch.domain.browser.IBrowserPage.DomElementInfo
import io.deepsearch.domain.browser.IBrowserPage.DomSnapshot

data class DomDiffResult(
    val added: List<DomElementInfo>,
    val removed: List<DomElementInfo>,
    val resized: List<Pair<DomElementInfo, DomElementInfo>>,
    val childCountChanged: List<Pair<DomElementInfo, DomElementInfo>>,
    val summary: String
)

interface IDomDiffService {
    fun diff(before: DomSnapshot, after: DomSnapshot): DomDiffResult
}

class DomDiffService : IDomDiffService {

    companion object {
        private const val RESIZE_THRESHOLD = 0.20
        private const val LARGE_ELEMENT_VP_RATIO = 0.50
    }

    override fun diff(before: DomSnapshot, after: DomSnapshot): DomDiffResult {
        val beforeById = before.elements.associateBy { it.stableId }
        val afterById = after.elements.associateBy { it.stableId }

        val added = after.elements.filter { it.stableId !in beforeById }
        val removed = before.elements.filter { it.stableId !in afterById }

        val resized = mutableListOf<Pair<DomElementInfo, DomElementInfo>>()
        val childCountChanged = mutableListOf<Pair<DomElementInfo, DomElementInfo>>()

        for ((id, afterEl) in afterById) {
            val beforeEl = beforeById[id] ?: continue
            if (areaChangedSignificantly(beforeEl.boundingBox, afterEl.boundingBox)) {
                resized.add(beforeEl to afterEl)
            }
            if (beforeEl.childCount != afterEl.childCount) {
                childCountChanged.add(beforeEl to afterEl)
            }
        }

        val summary = buildSummary(added, removed, resized, childCountChanged, after.viewportWidth, after.viewportHeight)
        return DomDiffResult(added, removed, resized, childCountChanged, summary)
    }

    private fun areaChangedSignificantly(before: DomBoundingBox, after: DomBoundingBox): Boolean {
        val beforeArea = before.width * before.height
        val afterArea = after.width * after.height
        if (beforeArea < 1.0 && afterArea < 1.0) return false
        val maxArea = maxOf(beforeArea, afterArea)
        return kotlin.math.abs(afterArea - beforeArea) / maxArea > RESIZE_THRESHOLD
    }

    private fun buildSummary(
        added: List<DomElementInfo>,
        removed: List<DomElementInfo>,
        resized: List<Pair<DomElementInfo, DomElementInfo>>,
        childCountChanged: List<Pair<DomElementInfo, DomElementInfo>>,
        vpWidth: Int,
        vpHeight: Int
    ): String {
        if (added.isEmpty() && removed.isEmpty() && resized.isEmpty() && childCountChanged.isEmpty()) {
            return "No structural DOM changes detected."
        }

        val vpArea = vpWidth.toDouble() * vpHeight
        val parts = mutableListOf<String>()

        if (added.isNotEmpty()) {
            val widthRatio = if (vpWidth > 0) added.maxOf { it.boundingBox.width } / vpWidth else 0.0
            if (widthRatio > LARGE_ELEMENT_VP_RATIO) {
                val el = added.maxByOrNull { it.boundingBox.width * it.boundingBox.height }!!
                parts.add("New large element appeared: ${describeElement(el)} (${dimStr(el.boundingBox)})")
            } else {
                parts.add("${added.size} new element(s) appeared: ${added.joinToString(", ") { describeElement(it) }}")
            }
        }

        if (removed.isNotEmpty()) {
            parts.add("${removed.size} element(s) removed: ${removed.joinToString(", ") { describeElement(it) }}")
        }

        if (resized.isNotEmpty()) {
            for ((before, after) in resized.take(3)) {
                val beforeArea = before.boundingBox.width * before.boundingBox.height
                val afterArea = after.boundingBox.width * after.boundingBox.height
                val direction = if (afterArea > beforeArea) "grew" else "shrank"
                parts.add("${describeElement(after)} $direction (${dimStr(before.boundingBox)} -> ${dimStr(after.boundingBox)})")
            }
        }

        val contentOnly = childCountChanged.filter { (_, after) ->
            !resized.any { it.second.stableId == after.stableId }
        }
        if (contentOnly.isNotEmpty()) {
            for ((before, after) in contentOnly.take(3)) {
                parts.add("${describeElement(after)} content updated (children: ${before.childCount} -> ${after.childCount})")
            }
        }

        return parts.joinToString(". ") + "."
    }

    private fun describeElement(el: DomElementInfo): String {
        return if (el.id.isNotEmpty()) "${el.tag}#${el.id}" else el.tag
    }

    private fun dimStr(box: DomBoundingBox): String {
        return "${box.width.toInt()}x${box.height.toInt()}"
    }
}
