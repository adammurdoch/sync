package net.rubygrapefruit.sync

import net.rubygrapefruit.cli.app.CliApp
import net.rubygrapefruit.file.Directory
import net.rubygrapefruit.file.ElementType
import net.rubygrapefruit.file.fileSystem
import net.rubygrapefruit.store.Store
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
            val rootNode = RootNode(local)
            val queue = Queue(rootNode)
            val executor = Executors.newCachedThreadPool()
            repeat(4) {
                executor.submit {
                    worker(queue)
                }
            }

            val tree = queue.await(rootNode)
            Logger.info("Found entries: ${tree.count}")
        }
        Logger.info("Sync finished")
    }

    private fun worker(queue: Queue) {
        while (true) {
            val node = queue.take()
            if (node == null) {
                return
            }
            val entries = node.directory.listEntries()
            queue.visiting(node) {
                for (entry in entries) {
                    when (entry.type) {
                        ElementType.Directory -> queue.add(node.dir(entry.toDir()))
                        ElementType.RegularFile -> node.regularFile(entry.name)
                        ElementType.SymLink -> node.symLink(entry.name)
                        else -> throw UnsupportedOperationException("Unsupported type ${entry.type} for $entry")
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

    sealed class Node(val directory: Directory) {
        private var visited = false
        private var waitingForDirs = 0
        private val entries = mutableListOf<TreeEntry>()

        val finished: Boolean get() = visited && waitingForDirs == 0

        fun dir(directory: Directory): Node {
            waitingForDirs++
            return ChildNode(directory, this)
        }

        fun regularFile(name: String) {
            entries += RegularFileEntry(name)
        }

        fun symLink(name: String) {
            entries += SymlinkEntry(name)
        }

        fun visited() {
            require(!visited)
            visited = true
            if (finished) {
                finished()
            }
        }

        fun childFinished(entry: DirTree) {
            require(visited && waitingForDirs > 0)
            waitingForDirs--
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

    class RootNode(directory: Directory) : Node(directory) {
        var result: DirTree? = null

        override fun finished(tree: DirTree) {
            result = tree
        }
    }

    class ChildNode(directory: Directory, private val parent: Node) : Node(directory) {
        override fun finished(tree: DirTree) {
            parent.childFinished(tree)
        }
    }
}

fun main(args: Array<String>) = SyncApp().run(args)
