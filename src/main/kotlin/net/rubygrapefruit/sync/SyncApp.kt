package net.rubygrapefruit.sync

import net.rubygrapefruit.cli.app.CliApp

class SyncApp: CliApp("sync") {
    override fun run() {
        println("sync started")
    }
}

fun main(args: Array<String>) = SyncApp().run(args)
