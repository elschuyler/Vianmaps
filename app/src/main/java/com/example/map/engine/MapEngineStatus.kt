package com.example.map.engine

/**
 * Unambiguous lifecycle and rendering status model for the MapLibre engine.
 */
enum class MapEngineStatus(val label: String) {
    INITIALIZING("Initializing"),
    WAITING_FOR_MAP_VIEW("Waiting for MapView"),
    LOADING_STYLE("Loading Style"),
    STYLE_READY("Style Ready"),
    RENDERING("Rendering"),
    FULLY_RENDERED("Fully Rendered"),
    FAILED("Failed"),
    RETRYING("Retrying")
}
