package io.deepsearch.domain.config

/**
 * Configuration for Vertex AI integration.
 *
 * @property projectId The Google Cloud project ID
 * @property location The Google Cloud location/region (e.g., "us-central1")
 */
data class VertexAiConfig(
    val projectId: String,
    val location: String
) {
    init {
        require(projectId.isNotBlank()) { "Vertex AI project ID cannot be blank" }
        require(location.isNotBlank()) { "Vertex AI location cannot be blank" }
    }
}
