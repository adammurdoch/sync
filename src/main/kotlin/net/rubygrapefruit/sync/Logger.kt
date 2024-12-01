package net.rubygrapefruit.sync

class Logger {
    companion object {
        fun info(message: String) {
            println("[${Thread.currentThread()}] $message")
        }
    }
}