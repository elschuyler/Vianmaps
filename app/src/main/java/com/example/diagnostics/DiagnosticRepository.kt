package com.example.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

enum class LogSeverity {
    DEBUG, INFO, WARNING, ERROR
}

data class DiagnosticEvent(
    val id: Long,
    val timestamp: Long,
    val formattedTime: String,
    val severity: LogSeverity,
    val source: String,
    val description: String,
    val stage: String? = null,
    val errorDetails: String? = null
)

/**
 * Centralized, bounded, thread-safe in-memory diagnostic repository.
 * Bounded to [MAX_CAPACITY] entries with automated ring rotation.
 * All sensitive information (passwords, tokens, GPS coordinates) is strictly sanitized.
 */
object DiagnosticRepository {
    private const val MAX_CAPACITY = 250
    private var eventSequence = 0L

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val buffer = ConcurrentLinkedDeque<DiagnosticEvent>()

    private val _eventsFlow = MutableStateFlow<List<DiagnosticEvent>>(emptyList())
    val eventsFlow: StateFlow<List<DiagnosticEvent>> = _eventsFlow.asStateFlow()

    // Bounded record of the last fatal or critical error
    private val _lastFatalError = MutableStateFlow<String?>(null)
    val lastFatalError: StateFlow<String?> = _lastFatalError.asStateFlow()

    init {
        log(
            severity = LogSeverity.INFO,
            source = "DiagnosticRepository",
            description = "Centralized Diagnostic Logging initialized (Capacity: $MAX_CAPACITY)"
        )
    }

    @Synchronized
    fun log(
        severity: LogSeverity,
        source: String,
        description: String,
        stage: String? = null,
        throwable: Throwable? = null
    ) {
        val sanitizedDesc = sanitize(description)
        val errorText = throwable?.let {
            "${it.javaClass.simpleName}: ${sanitize(it.message ?: "No error message")}"
        }

        val event = DiagnosticEvent(
            id = ++eventSequence,
            timestamp = System.currentTimeMillis(),
            formattedTime = timeFormat.format(Date()),
            severity = severity,
            source = source,
            description = sanitizedDesc,
            stage = stage,
            errorDetails = errorText
        )

        buffer.addLast(event)
        while (buffer.size > MAX_CAPACITY) {
            buffer.pollFirst()
        }

        if (severity == LogSeverity.ERROR && errorText != null) {
            _lastFatalError.value = "[${event.formattedTime}] $source: $errorText"
        }

        _eventsFlow.value = buffer.toList()
    }

    fun clear(logNotice: Boolean = false) {
        buffer.clear()
        _eventsFlow.value = emptyList()
        if (logNotice) {
            log(LogSeverity.INFO, "DiagnosticRepository", "Diagnostic event history cleared")
        }
    }

    fun getExportReport(currentFilter: LogSeverity? = null): String {
        val filtered = if (currentFilter != null) {
            buffer.filter { it.severity == currentFilter }
        } else {
            buffer.toList()
        }

        val sb = StringBuilder()
        sb.append("=== VIAN MAPS DIAGNOSTIC REPORT ===\n")
        sb.append("Export Time: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append("\n")
        sb.append("Total Events: ").append(filtered.size).append("\n")
        sb.append("Last Error: ").append(_lastFatalError.value ?: "None").append("\n")
        sb.append("===================================\n\n")

        for (event in filtered) {
            sb.append("[${event.formattedTime}] [${event.severity.name}] [${event.source}] ")
            event.stage?.let { sb.append("($it) ") }
            sb.append(event.description)
            event.errorDetails?.let { sb.append("\n  Exception: ").append(it) }
            sb.append("\n")
        }
        return sb.toString()
    }

    enum class Severity {
        DEBUG, INFO, WARNING, ERROR;

        fun toLogSeverity(): LogSeverity = when (this) {
            DEBUG -> LogSeverity.DEBUG
            INFO -> LogSeverity.INFO
            WARNING -> LogSeverity.WARNING
            ERROR -> LogSeverity.ERROR
        }
    }

    @Synchronized
    fun record(
        severity: Severity,
        source: String,
        description: String,
        stage: String? = null,
        throwable: Throwable? = null
    ) {
        log(severity.toLogSeverity(), source, description, stage, throwable)
    }

    @Synchronized
    fun record(
        severity: LogSeverity,
        source: String,
        description: String,
        stage: String? = null,
        throwable: Throwable? = null
    ) {
        log(severity, source, description, stage, throwable)
    }

    fun getSnapshot(): List<DiagnosticEvent> {
        return buffer.toList()
    }

    fun getFiltered(severity: Severity?): List<DiagnosticEvent> {
        return if (severity != null) {
            val target = severity.toLogSeverity()
            buffer.filter { it.severity == target }
        } else {
            buffer.toList()
        }
    }

    fun getFiltered(severity: LogSeverity?): List<DiagnosticEvent> {
        return if (severity != null) {
            buffer.filter { it.severity == severity }
        } else {
            buffer.toList()
        }
    }

    fun exportDiagnosticReport(
        currentStage: String? = null,
        lastError: String? = null,
        isDeviceOnline: Boolean? = null,
        connectionType: String? = null
    ): String {
        val sb = StringBuilder()
        sb.append("=== VIAN MAPS — SYSTEM DIAGNOSTIC REPORT ===\n")
        sb.append("Export Time: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append("\n")
        currentStage?.let { sb.append("Engine Stage: ").append(it).append("\n") }
        if (isDeviceOnline != null) {
            sb.append("Device Online: ").append(isDeviceOnline)
            connectionType?.let { sb.append(" ($it)") }
            sb.append("\n")
        }
        sb.append("Buffer Capacity: ").append(MAX_CAPACITY).append("\n")
        sb.append("Total Events: ").append(buffer.size).append("\n")
        sb.append("Last Error: ").append(lastError ?: _lastFatalError.value ?: "None").append("\n")
        sb.append("===========================================\n\n")

        for (event in buffer) {
            sb.append("[${event.formattedTime}] [${event.severity.name}] [${event.source}] ")
            event.stage?.let { sb.append("($it) ") }
            sb.append(event.description)
            event.errorDetails?.let { sb.append("\n  Exception: ").append(it) }
            sb.append("\n")
        }
        return sb.toString()
    }

    /**
     * Sanitizes strings to prevent sensitive credentials, tokens, or coordinates
     * from being stored in logs or exported.
     */
    fun sanitize(raw: String): String {
        var s = raw
        // Mask API keys / tokens (e.g. key=..., token=..., secret=...)
        s = s.replace(Regex("(?i)(api[_-]?key|token|password|secret|auth|key)=([^&\\s]+)"), "$1=[REDACTED]")
        // Mask exact high-precision decimal coordinate pairs like (37.7749292, -122.4194155)
        s = s.replace(Regex("-?\\d+\\.\\d{5,},\\s*-?\\d+\\.\\d{5,}"), "[LAT/LNG_REDACTED]")
        return s
    }
}
