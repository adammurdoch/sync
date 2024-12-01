package net.rubygrapefruit.sync

import kotlinx.serialization.Serializable

@Serializable
class ElementDetails(
    val checksum: ByteArray
)
