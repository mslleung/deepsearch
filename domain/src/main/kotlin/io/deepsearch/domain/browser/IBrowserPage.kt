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

    /**
     * Human-oriented snapshot of the current page. Designed to describe the content and
     * the available affordances in terms a human (and an agent) would understand.
     *
     * Prefer enums for categories and keep the representation free of implementation
     * details (e.g., ARIA roles, locators) while capturing the essence of information
     * and actions visible to a human reader.
     *
     * @property url Normalized URL of the current document
     * @property title Document/page title if present
     * @property description Meta description (or best-effort alternative) if present
     * @property language Declared document language (e.g., "en", "fr") if present
     * @property canonicalUrl Canonical URL if provided by the page
     * @property headings Visible headings in document order with explicit levels
     * @property mainText Best-effort text content of the main/article region
     * @property images Images with their alternative text where available
     * @property breadcrumbs Ordered labels representing the current location within the site hierarchy
     * @property actionSpace Interactive elements that a user can act upon next
     */
    data class PageInformation(
        val url: String,
        val title: String?,
        val description: String?,
        val language: String?,
        val canonicalUrl: String?,
        val headings: List<Heading>,
        val mainText: String?,
        val images: List<Image>,
        val breadcrumbs: List<String>,
        val actionSpace: ActionSpace
    )

    /**
     * Set of next-step affordances available to the user on the current page.
     *
     * @property links Navigational anchors the user can follow
     * @property buttons Clickable buttons including submit/reset
     * @property inputs Fields the user can type/select values into
     * @property forms Forms the user can submit including their declared method and action
     */
    class ActionSpace(
        val links: List<NavigationLink>,
        val buttons: List<Button>,
        val inputs: List<InputField>,
        val forms: List<Form>
    )

    /**
     * Navigational link shown to the user.
     * @property text Visible label near or inside the anchor (may be null if purely iconographic)
     * @property href Absolute URL destination
     */
    data class NavigationLink(
        val text: String?,
        val href: String
    )

    /**
     * Visible heading with explicit level.
     * @property level Heading level H1..H6
     * @property text Text content of the heading
     */
    data class Heading(
        val level: HeadingLevel,
        val text: String
    )

    /**
     * Heading level, preserving semantic structure.
     */
    enum class HeadingLevel {
        H1, H2, H3, H4, H5, H6
    }

    /**
     * Image visible to the user.
     * @property alt Alternative text if provided (helps accessibility and summarization)
     * @property src Source URL (may be relative or absolute depending on page)
     */
    data class Image(
        val alt: String?,
        val src: String
    )

    /**
     * Clickable button.
     * @property type Categorization of button behavior (e.g., submit)
     * @property text Visible button label (if any)
     * @property name Name attribute (useful when tied to forms)
     * @property id Element id attribute if present
     */
    data class Button(
        val type: ButtonType,
        val text: String?,
        val name: String?,
        val id: String?
    )

    /**
     * Input field that accepts user text or selection.
     * @property type Input modality (text, select, number, etc.)
     * @property name Name attribute (submits with forms)
     * @property id Element id if present
     * @property placeholder Hint text if provided
     */
    data class InputField(
        val type: InputType,
        val name: String?,
        val id: String?,
        val placeholder: String?
    )

    /**
     * HTML form that can be submitted by the user.
     * @property method Declared HTTP method (default GET if missing)
     * @property action Declared action URL (may be relative)
     * @property inputNames Names of contained inputs that will be submitted
     */
    data class Form(
        val method: HttpMethod,
        val action: String?,
        val inputNames: List<String>
    )

    /**
     * Button behavior taxonomy.
     */
    enum class ButtonType {
        BUTTON,
        SUBMIT,
        RESET,
        UNKNOWN
    }

    /**
     * Input modality taxonomy.
     */
    enum class InputType {
        TEXT,
        PASSWORD,
        EMAIL,
        SEARCH,
        URL,
        NUMBER,
        CHECKBOX,
        RADIO,
        FILE,
        DATE,
        TIME,
        DATETIME_LOCAL,
        MONTH,
        WEEK,
        TEL,
        COLOR,
        RANGE,
        HIDDEN,
        TEXTAREA,
        SELECT,
        UNKNOWN
    }

    /**
     * HTTP method taxonomy for forms.
     */
    enum class HttpMethod {
        GET,
        POST,
        PUT,
        PATCH,
        DELETE,
        OPTIONS,
        HEAD,
        UNKNOWN
    }

    /**
     * Parse the webpage using semantic HTML and accessibility guidelines (ARIA).
     *
     * Most webpages do not follow these guidelines in the same way. Therefore, the result is given in a best effort
     * approach. No LLM is involved in the process.
     *
     * @return A human-oriented snapshot of the page content and available actions.
     */
    fun getPageInformation() : PageInformation

    // --- Interactions (V1) ---

    /** Click the first link matching the given absolute or relative href. */
    fun clickLinkByHref(href: String): Boolean

    /** Click an element by its visible text (best-effort). */
    fun clickByText(text: String): Boolean

    /** Click at absolute page coordinates within the viewport. */
    fun clickAt(x: Double, y: Double): Boolean

    /** Fill an input (text/textarea/select) by matching name or id. */
    fun fillInputByNameOrId(nameOrId: String, value: String): Boolean

    /** Submit a form by its action href if provided; otherwise best-effort submit. */
    fun submitFormByAction(actionHref: String?): Boolean

    /** Wait until navigation/network idle or timeout. */
    fun waitForNavigationOrIdle(timeoutMs: Long = 8000): Boolean

    /** Scroll vertically to y offset. */
    fun scrollToY(y: Double)

    data class ViewportSize(val width: Int, val height: Int)
    fun getViewportSize(): ViewportSize

}