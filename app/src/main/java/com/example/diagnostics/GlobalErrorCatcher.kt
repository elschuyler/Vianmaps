package com.example.diagnostics

/**
 * Global uncaught crash handler that logs fatal exceptions to [DiagnosticRepository]
 * before delegating to the pre-existing system handler.
 * Never silences crashes; never creates background services or loops.
 */
object GlobalErrorCatcher {
    private var isInstalled = false

    fun install() {
        if (isInstalled) return
        isInstalled = true

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                DiagnosticRepository.log(
                    severity = LogSeverity.ERROR,
                    source = "FatalCrashHandler",
                    description = "Uncaught fatal exception on thread '${thread.name}'",
                    throwable = throwable
                )
            } catch (_: Throwable) {
                // Ignore any secondary failure in crash handler
            } finally {
                // Always delegate to previous handler to ensure standard Android crash behavior
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }

        DiagnosticRepository.log(
            severity = LogSeverity.INFO,
            source = "FatalCrashHandler",
            description = "Global uncaught exception handler registered"
        )
    }
}
