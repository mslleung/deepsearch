package io.deepsearch.domain.browser

/**
 * Abstraction over a single browser page/tab that can be navigated and inspected.
 *
 * The page provides a human-oriented snapshot of what a user can see and do on the
 * current document. This snapshot is the "eye" for LLM agents to reason over.
 */
interface IBrowserPage {

    /**
     * Navigates the current page to the given URL and waits for the default load state.
     * @param url Absolute or relative URL to open.
     */
    fun navigate(url: String)

    /**
     * Takes a screenshot of the current viewport and returns the image bytes.
     */
    fun takeScreenshot(): Screenshot

    /**
     * Takes a screenshot of the current viewport and returns the image bytes.
     */
    fun takeFullPageScreenshot(): Screenshot

    /**
     * Image screenshot payload and format information.
     */
    data class Screenshot(
        val bytes: ByteArray,
        val mimeType: ImageMimeType
    )

    /**
     * Supported image MIME types for screenshots.
     */
    enum class ImageMimeType(val value: String) {
        JPEG("image/jpeg"),
        PNG("image/png"),
        WEBP("image/webp")
    }

    data class PageInformation(
        val url: String,    // the webpage url
        val title: String?, // the webpage title
        val description: String?,// the webpage description
        val textContentForExtraction: String, // The webpage html converted to a better format for llm understanding and extracting information for answering user query
    )

    /**
     * Parse the webpage into a structured snapshot.
     */
    suspend fun parse(): PageInformation

}