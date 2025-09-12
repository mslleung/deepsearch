package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.models.entities.WebpageIcon
import io.deepsearch.domain.repositories.IWebpageIconRepository
import io.deepsearch.infrastructure.database.WebpageIconTable
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

class ExposedWebpageIconRepository : IWebpageIconRepository {

    override suspend fun upsert(icon: WebpageIcon) = suspendTransaction {
        val hashHex = icon.imageBytesHash.toHexString()

        // Try update first; if nothing updated, insert
        val updated = WebpageIconTable.update({ WebpageIconTable.imageHash eq hashHex }) {
            it[label] = icon.label
            it[updatedAtEpochMs] = icon.updatedAtEpochMs
        }
        if (updated == 0) {
            WebpageIconTable.insert {
                it[imageHash] = hashHex
                it[label] = icon.label
                it[createdAtEpochMs] = icon.createdAtEpochMs
                it[updatedAtEpochMs] = icon.updatedAtEpochMs
            }
        }
    }

    override suspend fun findByHash(imageBytesHash: ByteArray): WebpageIcon? = suspendTransaction {
        val hashHex = imageBytesHash.toHexString()
        WebpageIconTable.selectAll()
            .where { WebpageIconTable.imageHash eq hashHex }
            .map { mapRowToWebpageIcon(it) }
            .singleOrNull()
    }

    private fun mapRowToWebpageIcon(row: ResultRow): WebpageIcon {
        return WebpageIcon(
            imageBytesHash = row[WebpageIconTable.imageHash].hexToBytes(),
            label = row[WebpageIconTable.label],
            createdAtEpochMs = row[WebpageIconTable.createdAtEpochMs],
            updatedAtEpochMs = row[WebpageIconTable.updatedAtEpochMs],
        )
    }
}

private fun ByteArray.toHexString(): String = joinToString("") { byte ->
    val i = (byte.toInt() and 0xFF)
    val hi = i ushr 4
    val lo = i and 0x0F
    HEX_CHARS[hi].toString() + HEX_CHARS[lo]
}

private fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "Hex string must have even length" }
    val out = ByteArray(length / 2)
    var j = 0
    var i = 0
    while (i < length) {
        val hi = this[i].hexNibble()
        val lo = this[i + 1].hexNibble()
        out[j++] = ((hi shl 4) or lo).toByte()
        i += 2
    }
    return out
}

private fun Char.hexNibble(): Int {
    if (this in '0'..'9') return this.code - '0'.code
    val c = this.lowercaseChar()
    return when (c) {
        in 'a'..'f' -> 10 + (c.code - 'a'.code)
        else -> error("Invalid hex char: $this")
    }
}

private val HEX_CHARS = charArrayOf('0','1','2','3','4','5','6','7','8','9','a','b','c','d','e','f')