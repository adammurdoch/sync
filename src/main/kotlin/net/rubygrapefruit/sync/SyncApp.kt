package net.rubygrapefruit.sync

import net.rubygrapefruit.cli.app.CliApp
import net.rubygrapefruit.file.Directory
import net.rubygrapefruit.file.ElementType
import net.rubygrapefruit.file.RegularFile
import net.rubygrapefruit.file.fileSystem
import net.rubygrapefruit.store.Store
import net.rubygrapefruit.store.StoredMap
import java.security.MessageDigest
import java.time.Instant
import java.util.*
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
            val node = queue.take() ?: return
            when (node) {
                is RegularFileNode -> {
                    val cached = indexLock.withLock {
                        index.get(node.file.path.absolutePath)
                    }
                    val hash = if (cached == null) {
                        val digest = MessageDigest.getInstance("SHA-256")
                        val hash = node.file.read { source ->
                            val buffer = ByteArray(4096)
                            while (true) {
                                val nread = source.readAtMostTo(buffer)
                                if (nread < 0) {
                                    break
                                }
                                digest.update(buffer, 0, nread)
                            }
                            FileHash(digest.digest())
                        }
                        Logger.info("${node.file} Hash: $hash")
                        indexLock.withLock {
                            index.set(node.file.path.absolutePath, hash)
                        }
                        hash
                    } else {
                        cached
                    }
                    queue.visiting(node) {
                        node.entry = RegularFileEntry(node.file.name, hash)
                    }
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
        private var visited = 0L

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
                visited++
                condition.signalAll()
            }
        }

        fun await(node: RootNode): DirTree {
            val updatePeriod = 2L
            var nextUpdate = Instant.now().plusSeconds(updatePeriod)
            stateLock.withLock {
                while (true) {
                    val tree = node.result
                    if (tree != null) {
                        return tree
                    }
                    condition.awaitUntil(Date.from(nextUpdate))
                    if (Instant.now() > nextUpdate) {
                        Logger.info("Seen $visited entries")
                        nextUpdate = Instant.now().plusSeconds(updatePeriod)
                    }
                }
            }
        }
    }

    sealed class Node {
        abstract fun visited()
    }

    class RegularFileNode(val file: RegularFile, private val parent: DirectoryNode) : Node() {
        lateinit var entry: RegularFileEntry

        override fun visited() {
            parent.childFinished(entry)
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
            entries.add(SymlinkEntry(name))
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
