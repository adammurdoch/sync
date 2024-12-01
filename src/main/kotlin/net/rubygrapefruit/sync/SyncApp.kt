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
            queue.add(Node(local))
            val executor = Executors.newCachedThreadPool()
            repeat(4) {
                executor.submit {
                    while (true) {
                        val node = queue.take() ?: break
                        Logger.info("Visit ${node.directory}")
                        for (entry in node.directory.listEntries()) {
                            when (entry.type) {
                                ElementType.Directory -> queue.add(Node(entry.toDir()))

                                else -> Logger.info("Ignore ${entry.path}")
                            }
                        }
                        queue.finished(node)
                    }
                }
            }

            queue.await()

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

        fun finished(node: Node) {
            stateLock.withLock {
                pending--
                condition.signalAll()
            }
        }

        fun await() {
            stateLock.withLock {
                while (pending > 0) {
                    condition.await()
                }
            }
        }
    }

    class Node(val directory: Directory) {

    }
}

fun main(args: Array<String>) = SyncApp().run(args)
