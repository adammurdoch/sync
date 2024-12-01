package net.rubygrapefruit.sync

import kotlinx.serialization.Serializable

@Serializable
class FileHash(
    val checksum: ByteArray
)
