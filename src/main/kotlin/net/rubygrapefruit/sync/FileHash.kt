package net.rubygrapefruit.sync

import kotlinx.serialization.Serializable

@Serializable
class FileHash(
    val size: Long,
    val lastModified: Long,
    val checksum: ByteArray
) {
    override fun toString(): String {
        return checksum.joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
    }
}
