package net.rubygrapefruit.sync

import net.rubygrapefruit.cli.app.CliApp
import net.rubygrapefruit.file.Directory
import net.rubygrapefruit.file.ElementType
import net.rubygrapefruit.file.fileSystem
import net.rubygrapefruit.store.Store
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock

class SyncApp : CliApp("sync") {
    private val local by dir().parameter("local", help = "Local directory")

    override fun run() {
        Logger.info("Syncing $local")
        val storeDir = fileSystem.userHomeDirectory.dir(".dir-sync")
        Store.open(storeDir).use { store ->
            val index = store.map<String, FileHash>("details")
            val stateLock = ReentrantLock()
            val executor = Executors.newCachedThreadPool()
            val result = executor.submit<DirTree> {
                visitDir(local, executor)
            }
            val tree = result.get()
            Logger.info("Found entries: ${tree.count}")
        }
        Logger.info("Sync finished")
    }

    private fun visitDir(directory: Directory, executor: ExecutorService): DirTree {
        val entries = directory.listEntries().map { entry ->
            executor.submit<TreeEntry> {
                when (entry.type) {
                    ElementType.RegularFile -> RegularFileEntry(entry.name)
                    ElementType.Directory -> visitDir(entry.toDir(), executor)
                    else -> throw UnsupportedOperationException("Unsupported type ${entry.type} for $entry")
                }
            }
        }.map { it.get() }
        return DirTree(directory, entries)
    }
}

fun main(args: Array<String>) = SyncApp().run(args)
