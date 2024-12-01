package net.rubygrapefruit.sync

import net.rubygrapefruit.cli.app.CliApp

class SyncApp: CliApp("sync") {
    private val local by dir().parameter("local", help = "Local directory")

    override fun run() {
        Logger.info("Sync started for $local")
    }
}

fun main(args: Array<String>) = SyncApp().run(args)
