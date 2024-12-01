package net.rubygrapefruit.sync

import net.rubygrapefruit.cli.app.CliApp
import net.rubygrapefruit.file.fileSystem
import net.rubygrapefruit.store.Store
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock

class SyncApp: CliApp("sync") {
    private val local by dir().parameter("local", help = "Local directory")

    override fun run() {
        Logger.info("Syncing $local")
        val storeDir = fileSystem.userHomeDirectory.dir(".dir-sync")
        Store.open(storeDir).use { store ->
            val index = store.map<String, FileHash>("details")
            val stateLock = ReentrantLock()
            val executor = Executors.newFixedThreadPool(4)
            val result = executor.submit {
                DirTree(local)
            }
            result.get()
        }
        Logger.info("Sync finished")
    }
}

fun main(args: Array<String>) = SyncApp().run(args)
