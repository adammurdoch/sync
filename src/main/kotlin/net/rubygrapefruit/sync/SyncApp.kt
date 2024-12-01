package net.rubygrapefruit.sync

import net.rubygrapefruit.cli.app.CliApp
import net.rubygrapefruit.file.fileSystem
import net.rubygrapefruit.store.Store

class SyncApp: CliApp("sync") {
    private val local by dir().parameter("local", help = "Local directory")

    override fun run() {
        Logger.info("Syncing $local")
        val storeDir = fileSystem.userHomeDirectory.dir(".dir-sync")
        Store.open(storeDir).use { store ->
            val index = store.map<String, ElementDetails>("details")
        }
        Logger.info("Sync finished")
    }
}

fun main(args: Array<String>) = SyncApp().run(args)
