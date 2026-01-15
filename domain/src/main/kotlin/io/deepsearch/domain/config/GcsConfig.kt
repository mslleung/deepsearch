package io.deepsearch.domain.config

/**
 * Configuration for Google Cloud Storage.
 *
 * @property tempBucketName The GCS bucket for temporary files during batch processing
 * @property imageBucketName The GCS bucket for permanent image storage
 */
data class GcsConfig(
    val tempBucketName: String,
    val imageBucketName: String
) {
    init {
        require(tempBucketName.isNotBlank()) { "GCS temp bucket name must not be blank" }
        require(imageBucketName.isNotBlank()) { "GCS image bucket name must not be blank" }
    }
}
