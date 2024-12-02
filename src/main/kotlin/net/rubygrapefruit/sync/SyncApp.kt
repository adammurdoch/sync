package net.rubygrapefruit.sync

import net.rubygrapefruit.cli.app.CliApp
import net.rubygrapefruit.file.Directory
import net.rubygrapefruit.file.ElementType
import net.rubygrapefruit.file.RegularFile
import net.rubygrapefruit.file.fileSystem
import net.rubygrapefruit.store.Store
import net.rubygrapefruit.store.StoredMap
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class SyncApp : CliApp("sync") {
    private val local by dir().parameter("local", help = "Local directory")

    override fun run() {
        Logger.info("Syncing $local")
        val storeDir = fileSystem.userHomeDirectory.dir(".dir-sync")
        Store.open(storeDir).use { store ->
            val index = store.map<String, FileHash>("details")
            val indexLock = ReentrantLock()
            val rootNode = RootNode(local)
            val queue = Queue(rootNode)
            val executor = Executors.newCachedThreadPool()
            repeat(4) {
                executor.submit {
                    worker(queue, index, indexLock)
                }
            }

            val tree = queue.await(rootNode)
            Logger.info("Found entries: ${tree.count}")
        }
        Logger.info("Sync finished")
    }

    private fun worker(queue: Queue, index: StoredMap<String, FileHash>, indexLock: ReentrantLock) {
        while (true) {
            val node = queue.take()
            if (node == null) {
                return
            }
            when (node) {
                is RegularFileNode -> {
                    val cached = indexLock.withLock {
                        index.get(node.file.path.absolutePath)
                    }
                    if (cached == null) {
                        indexLock.withLock {
                            index.set(node.file.path.absolutePath, FileHash(ByteArray(0)))
                        }
                    }
                    queue.visiting(node) {}
                }

                is DirectoryNode -> {
                    val entries = node.directory.listEntries()
                    queue.visiting(node) {
                        for (entry in entries) {
                            when (entry.type) {
                                ElementType.Directory -> queue.add(node.dir(entry.toDir()))
                                ElementType.RegularFile -> queue.add(node.regularFile(entry.toFile()))
                                ElementType.SymLink -> node.symLink(entry.name)
                                else -> throw UnsupportedOperationException("Unsupported type ${entry.type} for $entry")
                            }
                        }
                    }
                }
            }
        }
    }

    class Queue(
        private val rootNode: RootNode
    ) {
        private val stateLock = ReentrantLock()
        private val condition = stateLock.newCondition()
        private val queue = ArrayList<Node>()

        init {
            add(rootNode)
        }

        fun add(node: Node) {
            stateLock.withLock {
                queue.add(node)
                condition.signalAll()
            }
        }

        fun take(): Node? {
            return stateLock.withLock {
                while (queue.isEmpty()) {
                    if (rootNode.finished) {
                        return null
                    }
                    condition.await()
                }
                queue.removeFirst()
            }
        }

        fun visiting(node: Node, action: () -> Unit) {
            stateLock.withLock {
                action()
                node.visited()
                condition.signalAll()
            }
        }

        fun await(node: RootNode): DirTree {
            stateLock.withLock {
                while (true) {
                    val tree = node.result
                    if (tree != null) {
                        return tree
                    }
                    condition.await()
                }
            }
        }
    }

    sealed class Node {
        abstract fun visited()
    }

    class RegularFileNode(val file: RegularFile, val parent: DirectoryNode) : Node() {
        override fun visited() {
            parent.childFinished(RegularFileEntry(file.name))
        }
    }

    sealed class DirectoryNode(val directory: Directory) : Node() {
        private var visited = false
        private var waitingForChildren = 0
        private val entries = mutableListOf<TreeEntry>()

        val finished: Boolean get() = visited && waitingForChildren == 0

        fun dir(directory: Directory): Node {
            waitingForChildren++
            return ChildNode(directory, this)
        }

        fun regularFile(file: RegularFile): RegularFileNode {
            waitingForChildren++
            return RegularFileNode(file, this)
        }

        fun symLink(name: String) {
            entries += SymlinkEntry(name)
        }

        override fun visited() {
            require(!visited)
            visited = true
            if (finished) {
                finished()
            }
        }

        fun childFinished(entry: TreeEntry) {
            require(visited && waitingForChildren > 0)
            waitingForChildren--
            entries += entry
            if (finished) {
                finished()
            }
        }

        private fun finished() {
            finished(DirTree(directory, entries.toList()))
        }

        abstract fun finished(tree: DirTree)
    }

    class RootNode(directory: Directory) : DirectoryNode(directory) {
        var result: DirTree? = null

        override fun finished(tree: DirTree) {
            result = tree
        }
    }

    class ChildNode(directory: Directory, private val parent: DirectoryNode) : DirectoryNode(directory) {
        override fun finished(tree: DirTree) {
            parent.childFinished(tree)
        }
    }
}

fun main(args: Array<String>) = SyncApp().run(args)
