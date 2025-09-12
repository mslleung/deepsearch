package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.models.entities.WebpageIconRecord
import io.deepsearch.domain.repositories.IWebpageIconRepository
import io.deepsearch.infrastructure.database.WebpageIconTable
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import java.util.Base64

class ExposedWebpageIconRepository : IWebpageIconRepository {

    override suspend fun upsert(pageUrl: String, icon: WebpageIconRecord) {
        suspendTransaction {
            val existing = WebpageIconTable
                .selectAll()
                .where {
                    (WebpageIconTable.pageUrl eq pageUrl) and
                        (WebpageIconTable.selector eq icon.selector) and
                        (WebpageIconTable.imageHash eq icon.imageBytesHash)
                }
                .singleOrNull()

            val encoded = Base64.getEncoder().encodeToString(icon.jpegBytes)
            val nowUpdated = System.currentTimeMillis()

            if (existing == null) {
                WebpageIconTable.insert {
                    it[WebpageIconTable.pageUrl] = pageUrl
                    it[selector] = icon.selector
                    it[imageHash] = icon.imageBytesHash
                    it[mimeType] = icon.mimeType.value
                    it[jpegBase64] = encoded
                    it[hintsJson] = encodeHints(icon.hints)
                    it[label] = icon.label
                    it[confidence] = icon.confidence
                    it[createdAtEpochMs] = icon.createdAtEpochMs
                    it[updatedAtEpochMs] = nowUpdated
                }
            } else {
                WebpageIconTable.update({
                    (WebpageIconTable.pageUrl eq pageUrl) and
                        (WebpageIconTable.selector eq icon.selector) and
                        (WebpageIconTable.imageHash eq icon.imageBytesHash)
                }) {
                    it[mimeType] = icon.mimeType.value
                    it[jpegBase64] = encoded
                    it[hintsJson] = encodeHints(icon.hints)
                    it[label] = icon.label
                    it[confidence] = icon.confidence
                    it[updatedAtEpochMs] = nowUpdated
                }
            }
        }
    }

    override suspend fun findByUrlAndSelector(pageUrl: String, selector: String): List<WebpageIconRecord> = suspendTransaction {
        WebpageIconTable
            .selectAll()
            .where { (WebpageIconTable.pageUrl eq pageUrl) and (WebpageIconTable.selector eq selector) }
            .map { mapRow(it) }
            .toList()
    }

    override suspend fun findByUrlAndHash(pageUrl: String, imageBytesHash: String): WebpageIconRecord? = suspendTransaction {
        WebpageIconTable
            .selectAll()
            .where { (WebpageIconTable.pageUrl eq pageUrl) and (WebpageIconTable.imageHash eq imageBytesHash) }
            .map { mapRow(it) }
            .singleOrNull()
    }

    override suspend fun listByUrl(pageUrl: String): List<WebpageIconRecord> = suspendTransaction {
        WebpageIconTable
            .selectAll()
            .where { WebpageIconTable.pageUrl eq pageUrl }
            .map { mapRow(it) }
            .toList()
    }

    private fun mapRow(row: ResultRow): WebpageIconRecord {
        val bytes = Base64.getDecoder().decode(row[WebpageIconTable.jpegBase64])
        return WebpageIconRecord(
            selector = row[WebpageIconTable.selector],
            imageBytesHash = row[WebpageIconTable.imageHash],
            mimeType = io.deepsearch.domain.constants.ImageMimeType.values().first { it.value == row[WebpageIconTable.mimeType] },
            jpegBytes = bytes,
            label = row[WebpageIconTable.label],
            confidence = row[WebpageIconTable.confidence],
            hints = decodeHints(row[WebpageIconTable.hintsJson]),
            createdAtEpochMs = row[WebpageIconTable.createdAtEpochMs],
            updatedAtEpochMs = row[WebpageIconTable.updatedAtEpochMs]
        )
    }

    private fun encodeHints(hints: List<String>): String {
        // Minimal JSON array encoding for list of strings
        return hints.joinToString(prefix = "[", postfix = "]") { s ->
            "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }
    }

    private fun decodeHints(json: String): List<String> {
        // Very small parser for simple JSON string arrays: ["a","b"]
        val trimmed = json.trim()
        if (trimmed.length < 2 || trimmed.first() != '[' || trimmed.last() != ']') return emptyList()
        val inner = trimmed.substring(1, trimmed.length - 1).trim()
        if (inner.isEmpty()) return emptyList()
        // split on commas not inside quotes - simple approach since hints content is simple and encoded above
        val parts = mutableListOf<String>()
        var i = 0
        var inString = false
        var current = StringBuilder()
        while (i < inner.length) {
            val c = inner[i]
            if (c == '\\') {
                if (i + 1 < inner.length) {
                    val next = inner[i + 1]
                    current.append(
                        when (next) {
                            '\\' -> '\\'
                            '"' -> '"'
                            'n' -> '\n'
                            't' -> '\t'
                            'r' -> '\r'
                            else -> next
                        }
                    )
                    i += 2
                    continue
                }
            } else if (c == '"') {
                inString = !inString
                i++
                continue
            } else if (c == ',' && !inString) {
                parts.add(current.toString())
                current = StringBuilder()
                i++
                continue
            }
            current.append(c)
            i++
        }
        if (current.isNotEmpty()) {
            parts.add(current.toString())
        }
        // Remove any surrounding quotes artifacts (shouldn't be needed with state machine above)
        return parts.map { it }
    }
}