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
            val stateLock = ReentrantLock()
            val condition = stateLock.newCondition()
            var pending = 0
            val queue = ArrayList<Node>()
            stateLock.withLock {
                queue.add(Node(local))
                pending++
                condition.signalAll()
            }
            val executor = Executors.newCachedThreadPool()
            (1..4).forEach {
                executor.submit {
                    while (true) {
                        val node = stateLock.withLock {
                            while (queue.isEmpty()) {
                                if (pending == 0) {
                                    Logger.info("Worker finished")
                                    return@submit
                                }
                                condition.await()
                            }
                            queue.removeFirst()
                        }
                        Logger.info("Visit ${node.directory}")
                        for (entry in node.directory.listEntries()) {
                            when (entry.type) {
                                ElementType.Directory -> stateLock.withLock {
                                    Logger.info("Push ${entry.path}")
                                    pending++
                                    queue.add(Node(entry.toDir()))
                                    condition.signalAll()
                                }

                                else -> Logger.info("Ignore ${entry.path}")
                            }
                        }
                        stateLock.withLock {
                            pending--
                            Logger.info("Finish ${node.directory}, pending: $pending")
                            condition.signalAll()
                        }
                    }
                }
            }

            stateLock.withLock {
                while (pending > 0) {
                    Logger.info("Pending: $pending")
                    condition.await()
                }
            }

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

    class Node(val directory: Directory) {

    }
}

fun main(args: Array<String>) = SyncApp().run(args)
