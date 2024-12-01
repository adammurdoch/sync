package net.rubygrapefruit.sync

import net.rubygrapefruit.file.Directory

sealed class TreeEntry {
    abstract val name: String
    abstract val count: Int
}

class DirTree(val dir: Directory, val entries: List<TreeEntry>) : TreeEntry() {
    override val name: String
        get() = dir.name

    override val count: Int
        get() {
            return 1 + entries.sumOf { entry -> entry.count }
        }
}

class RegularFileEntry(override val name: String) : TreeEntry() {
    override val count: Int
        get() = 1
}
