package net.rubygrapefruit.sync

import net.rubygrapefruit.cli.app.CliApp
import net.rubygrapefruit.file.Directory
import net.rubygrapefruit.file.ElementType
import net.rubygrapefruit.file.fileSystem
import net.rubygrapefruit.store.Store
import java.util.concurrent.ExecutorService
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
            val queue = Queue()
            val rootNode = RootNode(local)
            queue.add(rootNode)
            val executor = Executors.newCachedThreadPool()
            repeat(4) {
                executor.submit {
                    while (true) {
                        val node = queue.take()
                        if (node == null) {
                            Logger.info("Worker finished")
                            break
                        }
                        Logger.info("Visit ${node.directory}")
                        for (entry in node.directory.listEntries()) {
                            when (entry.type) {
                                ElementType.Directory -> queue.add(node.dir(entry.toDir()))

                                else -> continue
                            }
                        }
                        queue.visited(node)
                    }
                }
            }

            queue.await(rootNode)

//            val result = executor.submit<DirTree> {
//                visitDir(local, executor)
//            }
//            val tree = result.get()
//            Logger.info("Found entries: ${tree.count}")
        }
        Logger.info("Sync finished")
    }

    private fun visitDir(directory: Directory, executor: ExecutorService): DirTree {
        val entries = directory.listEntries().map { entry ->
            executor.submit<TreeEntry> {
                when (entry.type) {
                    ElementType.Directory -> visitDir(entry.toDir(), executor)
                    ElementType.RegularFile -> RegularFileEntry(entry.name)
                    ElementType.SymLink -> SymlinkEntry(entry.name)
                    else -> throw UnsupportedOperationException("Unsupported type ${entry.type} for $entry")
                }
            }
        }.map { it.get() }
        return DirTree(directory, entries)
    }

    class Queue {
        private val stateLock = ReentrantLock()
        private val condition = stateLock.newCondition()
        private var pending = 0
        private val queue = ArrayList<Node>()

        fun add(node: Node) {
            stateLock.withLock {
                pending++
                queue.add(node)
                condition.signalAll()
            }
        }

        fun take(): Node? {
            return stateLock.withLock {
                while (queue.isEmpty()) {
                    if (pending == 0) {
                        return null
                    }
                    condition.await()
                }
                queue.removeFirst()
            }
        }

        fun visited(node: Node) {
            stateLock.withLock {
                node.visited()
                pending--
                condition.signalAll()
            }
        }

        fun await(node: RootNode) {
            stateLock.withLock {
                while (node.waiting) {
                    Logger.info("Waiting for $node")
                    condition.await()
                }
            }
        }
    }

    sealed class Node(val directory: Directory) {
        private var visited = false
        private var waitingForDirs = 0

        val waiting: Boolean get() = !visited || waitingForDirs > 0

        val finished: Boolean get() = visited && waitingForDirs == 0

        override fun toString(): String {
            return "$directory visited: $visited, waitingFor: $waitingForDirs"
        }

        fun dir(directory: Directory): Node {
            waitingForDirs++
            return ChildNode(directory, this)
        }

        fun visited() {
            require(!visited)
            Logger.info("Visited $directory")
            visited = true
            if (finished) {
                finished()
            }
        }

        fun childFinished() {
            require(visited && waitingForDirs > 0)
            waitingForDirs--
            if (finished) {
                finished()
            }
        }

        open fun finished() {
            Logger.info("Finished $directory")
        }
    }

    class RootNode(directory: Directory) : Node(directory)

    class ChildNode(directory: Directory, private val parent: Node) : Node(directory) {
        override fun finished() {
            super.finished()
            parent.childFinished()
        }
    }
}

fun main(args: Array<String>) = SyncApp().run(args)
