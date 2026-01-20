package io.deepsearch.application.services.batch

import io.deepsearch.domain.services.CssSelectorReplacement
import io.deepsearch.domain.services.MediaFileData

/**
 * Result of building media replacements, including the imageMapping for cache storage.
 */
data class MediaReplacementResult(
    /** CSS selector replacements for icons and images */
    val replacements: List<CssSelectorReplacement>,
    /** Mapping of sequential image numbers to hash-based IDs: {"1": "img-abc123"} */
    val imageMapping: Map<String, String>
)

/**
 * Utility for building CSS selector replacements from icon and image interpretations.
 * Shared between TableInterpretationBatchHandler and ParallelEmbeddingAndKgHandler.
 */
object MediaReplacementBuilder {

    /**
     * Build CSS selector replacements from icons and images with their interpretations.
     *
     * Icons are wrapped in curly braces: `{label}`
     * Images are wrapped in Markdown image syntax: `![text](#img-{number})`
     * This matches the interactive pipeline's behavior.
     *
     * Returns both replacements and imageMapping for consistent behavior with interactive pipeline.
     *
     * @param icons List of icon file data with CSS selectors
     * @param images List of image file data with CSS selectors
     * @param iconInterpretations Map of icon hash -> label
     * @param imageTexts Map of image hash -> extracted text
     * @return MediaReplacementResult with replacements and imageMapping
     */
    fun buildFromIconsAndImages(
        icons: List<MediaFileData>,
        images: List<MediaFileData>,
        iconInterpretations: Map<String, String?>?,
        imageTexts: Map<String, String?>?
    ): MediaReplacementResult {
        val replacements = mutableListOf<CssSelectorReplacement>()
        val imageMapping = mutableMapOf<String, String>()
        var imageCounter = 0

        // Icon replacements - wrapped in curly braces for markdown safety
        icons.forEach { iconData ->
            val label = iconInterpretations?.get(iconData.hash)
            if (label != null) {
                val wrappedLabel = wrapIconTextAsMarkdown(label)
                iconData.cssSelectors.forEach { selector ->
                    replacements.add(CssSelectorReplacement(selector, wrappedLabel))
                }
            }
        }

        // Image replacements - Markdown image syntax with sequential numbers (matches interactive pipeline)
        images.forEach { imageData ->
            val text = imageTexts?.get(imageData.hash)
            if (text != null) {
                imageCounter++
                val imageNumber = imageCounter.toString()
                val hashBasedId = generateImageId(imageData.hash)
                
                // Store mapping: number -> hash-based ID (for source eval agent)
                imageMapping[imageNumber] = hashBasedId
                
                // Use number in markdown (matches interactive pipeline)
                val wrappedText = wrapImageTextAsMarkdown(text, imageNumber)
                imageData.cssSelectors.forEach { selector ->
                    replacements.add(CssSelectorReplacement(selector, wrappedText))
                }
            }
        }

        return MediaReplacementResult(replacements, imageMapping)
    }

    /**
     * Wrap icon label text in curly braces for markdown output.
     * Uses curly braces because they are markdown-safe (won't trigger links, emphasis, etc.)
     * Matches the interactive pipeline's WebpageExtractionService.wrapIconTextAsMarkdown behavior.
     *
     * @param label The icon label text
     * @return Icon wrapped in curly braces: {label}
     */
    private fun wrapIconTextAsMarkdown(label: String): String = "{$label}"

    /**
     * Wrap image text as Markdown image syntax.
     * Matches the interactive pipeline's WebpageExtractionService.wrapImageTextAsMarkdown behavior.
     *
     * @param text The extracted text from the image
     * @param imageNumber The sequential image number
     * @return Markdown image syntax: ![condensed text](#img-{number})
     */
    private fun wrapImageTextAsMarkdown(text: String, imageNumber: String): String {
        // Condense multiline text to single line for markdown alt text
        val condensedText = text.replace('\n', ' ').replace("\\s+".toRegex(), " ").trim()
        return "![$condensedText](#img-$imageNumber)"
    }

    /**
     * Generate a URL-safe image ID from a hash.
     * Replaces Base64 special characters and removes padding.
     *
     * @param hash Base64 encoded hash string
     * @return URL-safe image ID prefixed with "img-"
     */
    fun generateImageId(hash: String): String {
        val urlSafeHash = hash.replace("+", "-").replace("/", "_").trimEnd('=')
        return "img-$urlSafeHash"
    }
}
