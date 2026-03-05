package io.deepsearch.domain.agents.googlegenaiimpl

import io.deepsearch.domain.agents.IMarkdownFormattingAgent
import io.deepsearch.domain.agents.MarkdownFormattingInput
import io.deepsearch.domain.config.domainTestModule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import io.deepsearch.domain.testing.IsolatedKoinExtension
import io.deepsearch.domain.testing.IsolatedKoinTest
import kotlin.test.assertTrue

class MarkdownFormattingAgentTest : IsolatedKoinTest() {

    @JvmField
    @RegisterExtension
    val koinTestExtension = IsolatedKoinExtension.create {
        modules(domainTestModule)
    }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val markdownFormattingAgent by inject<IMarkdownFormattingAgent>()

    @Test
    fun `formats raw text with multiple images into well-structured markdown`() = runTest(testCoroutineDispatcher) {
        val rawText = """
Contact our team
Salutation
Mr.
Ms.
Dr.
Prof.
First Name
*
Last Name
*
Business Email
*
Job Title
*
Company Name
*
What's the size of your company?
*
Please Select
1-50
50-100
100-500
500+
Do you have Health Insurance through work?
*
Please Select
Yes
No
Which of the OT&P Corporate Wellness services are you interested in?
*
Please Select
Vaccines
In-House Services e.g. Counseling
Wellness Weeks
Mental Health Wellness Programmes
Health & Lifestyle Programmes
Leadership Coaching
OT&P needs the contact information you provide to us to contact you about our products and services. You may unsubscribe from these communications at any time. For information on how to unsubscribe, as well as our privacy practices and commitment to protecting your privacy, please review our Privacy Policy.

![Low-light promotional image featuring a woman wearing a face mask speaking on a desk phone in a reception setting with OT&P Healthcare logo visible](#img-1)
What is THRIVE?
![Venn diagram with three overlapping circles representing Lifestyle & Health Management, Coaching, and Psychology centered around THRIVE](#img-2)
THRIVE is OT&P's all-encompassing mental health program. This optimisation program utilises the best evidence-based teaching of health, psychology and coaching to help employees feel and perform at their best — equipping your workforce with the tools to prosper while future-proofing your business in a constructive and positive manner.
THRIVE is based on the core belief that when employees feel their best, they perform at their best.
How does THRIVE work?
At the intersection of Lifestyle & Health Management, Psychology and Coaching you'll find THRIVE. The core offering crystallises the most important philosophies from these fields and offers companies a way to upgrade how their employees feel and function.
![Icon depicting two hands clasped in the center with diamond lattice pattern suggesting connection and community](#img-3)
Psychology
Our expertise in psychology provides businesses with the essential tools to help staff manage their mental health challenges. Our distinguished mental health counsellors from
MindWorX
visit your place of work to provide bespoke talks and trainings on various topics including resilience, burnout, stress management, sleep etc.
Lifestyle & Health Management
Lifestyle and health management is a modern, evidence-based and cost-effective approach that addresses common underlying lifestyle, socio-behavioural and nutritional causes of an employee's suboptimal health status. This includes looking at employees' wellbeing and current health, any presiding chronic conditions and preventative health measures for the future.
Coaching
This can be individual coaching or group coaching and tailored to client's needs. Coaches help the client explore issues, set goals and overcome challenges. Coaches use their ability to empathise with and challenge employees in order to jointly produce creative solutions that encourage efficiency and productivity.
Take our corporate quiz!
Why not take our quick free quiz to learn more about how your corporate wellness programs fare against global standards — and what you should focus on.
add message
Take the Quiz
Our program can provide assistance in the following areas:
| Category | Benefit Description |
|---|---|
| Teamwork | Enhancing productivity and encouraging emotional well-being by building strong cultures. |
| Health | Being physically healthy to maintain a high quality of life. |
| Resilience | Equipping employees with the ability to thrive in the face of difficulty and encouraging mental fortitude. |
| Innovation | Promoting value creation and collaboration through creativity. |
| Values | Nurturing a sense of purpose at work to advance positive images of a company. |
| Endurance | Sustaining a competitive advantage and promoting sustainable shifts in the workforce. |

> The structure appears to be a list of service blocks arranged in three rows and two columns implicitly based on the bounding boxes, representing the six categories of the THRIVE program. Since this is a list of benefits/features, the structure is best represented as a single-column table where the first column contains the category and the second column contains the description, inferring a header row from the provided column list.
> 
> The HTML block for 'Endurance' (`ds-bounding-box="789 317 1139 589"`) contains the text "endurance" outside the `<h3>` tag, which has been omitted in the final table structure as it seems redundant or extraneous.
How is THRIVE delivered?
| Assessment Phase | Delivery Phase | Self-enablement |
|---|---|---|
| Employee Surveys | Guided interactive talks or Workshops | Work with HR teams to implement resources that enable self-learning |
| Health Assessments | Medical Consults | Re-assessment |
| Review Health Plan | Therapy | |
| Evaluate Health Culture | 121 Coaching to explore issues, set goals, and overcome challenges through a series of one-on-one conversations | |
| Evaluate Organisational Culture | | |

> The table structure was inferred from blocks of content presented side-by-side, corresponding to the columns described in the auxiliary info: Assessment Phase, Delivery Phase, and Self-enablement. Content within each phase (list items) forms the rows under that respective column.
Key Benefits
| Increase Workplace Productivity | Improve Employee Health Behaviours |
|---|---|
| Reduce Elevated Health Risks | Decrease Absenteeism |

> The provided HTML structure represents a grid of benefits, not a traditional HTML table. It has been converted into a 2x2 markdown table structure where the content of each cell is the text associated with the benefit image/icon. The original auxiliary info described 4 columns, but the visual structure suggests 2 rows and 2 columns of content blocks.
Addressing wellbeing at work increases productivity by as much as 12%
Source:
https://www.mentalhealth.org.uk/publications/how-support-mental-health-work
For every US$ 1 invested in common mental health issues, there is a return of US$ 4 in improved health and productivity.
Source:
https://www.who.int/mental_health/in_the_workplace/en/
Addressing wellbeing at work increases productivity by as much as 12%
Source:
https://www.mentalhealth.org.uk/publications/how-support-mental-health-work
For every US$ 1 invested in common mental health issues, there is a return of US$ 4 in improved health and productivity.
Source:
https://www.who.int/mental_health/in_the_workplace/en/
Addressing wellbeing at work increases productivity by as much as 12%
Source:
https://www.mentalhealth.org.uk/publications/how-support-mental-health-work
THRIVE bespoke education & training topics
No matter which stage you are in your corporate health journey, our training programs are designed to be flexible and versatile. Whether you are just starting out or need a little extra support during turbulent times, we can help develop a bespoke educational and training program for your company. Examples of programs we have delivered are:
| Category | Details |
|---|---|
| 1. Mental health awareness [?] | - Introductions to Mental Health for all Employees
- Mental Health awareness when managing a team |

> The input is structured as an accordion, not a traditional HTML table, but it represents categorical data with associated details, which is being coerced into a Markdown table. The columns specified in the auxiliary info (Mental health awa..., Resilience in the..., Lifestyle & healt...) do not perfectly align with the structure of the provided HTML, which only details content for the first category ("1. Mental health awareness"). The table structure is inferred based on the header-content relationship shown in the accordion item.
![Circular icon with three stylized human figures and medical cross symbol representing group health services](#img-4)
2. Resilience in the face of changing work and social dynamics
question mark in a box
Introduction to Resilience
Resilience in the face of changing work and social dynamics for employees
Resilience in the face of changing work and social dynamics for managers
![Question mark icon in teal color within a speech bubble symbolizing FAQ or help](#img-5)
3. Lifestyle & health management
question mark in a box
Optimising performance through sleep and the evidence behind what works
Is your gut the key to better physical & mental health?
Maintaining fitness and losing weight to improve cardiovascular fitness and brain function
| Contact our team | Test your corporate wellness initiatives | Book a meeting |
|---|---|---|
| Get in touch with a member of our team by email to learn more about our corporate training programs, talks and workshops. | Take our free quick quiz to see how your employee wellbeing initiatives fare to global standards, which areas can be improved and how. | Schedule a free 15 or 30 minute discussion with one of our corporate representatives to learn more about how OT&P can help your business. |
| Submit Form | Take The Quiz | Schedule A Time |

> The provided HTML structure represents three distinct service blocks laid out horizontally (in a responsive design) rather than a strict HTML table. The columns are inferred from the auxiliary info: "Contact our team", "Test your corporate ...", and "Book a meeting". The content under each title (description and button) is treated as cell content.
More Corporate Health & Wellness Programs
| Program | Description | Included Programs/Services |
|---|---|---|
| **OT&P Health & Wellness Program** | Get bespoke access for your employees to OT&amp;P's doctors at your place of work, in one of our conveniently located clinics, or online. Programs can consist of any of the below: | - Vaccination Program
- Health Advisor
- Educational Training
- Bespoke Health Checks
- Pandemic Response and COVID Testing |

> The provided HTML structure does not strictly conform to a standard HTML table (<table>). It is a structured layout of divs that semantically represents a single entry in a list or a program description, rather than a multi-row/multi-column grid of data suitable for a standard Markdown table. 
> 
> Based on the Auxiliary Info ("Related corporate health and wellness programs." and "Columns: OT&PHealth & Wellness Pr..."), I will structure the output to represent the OT&P Health & Wellness Program details as one row entry.
> 
> *   The image description is included in the additional information as it provides context for the program entry.
> *   The button text 'Learn More' is captured as an action associated with this program.
Our Blog
| Long Covid Update 2025: Symptoms, Causes, and Latest Clinical Insights | Accelerated Brain Ageing Linked to COVID-19: Understanding the Long-Term Impacts | COVID-19 Traveling Restriction Update |
|---|---|---|
| OT&P Healthcare logo, QR code to follow Hong Kong OT&P Healthcare, modern reception area photo | Black button with Apple logo: "Download on the App Store" | Button with Google Play logo: "GET IT ON Google Play" |

> The HTML structure appears to represent a list of recent blog posts presented in three columns, which are interpreted as three distinct rows in the markdown table, with implied shared content or context based on the auxiliary info describing columns.
> 
> Since the HTML structure shows three items side-by-side, I am treating each item as a cell in a single row of content, matching the description of three columns: "Long Covid Update 20...", "Accelerated Brain Ag...", "COVID-19 Traveling R...".
> 
> Images are described in placeholder tags and are omitted from the table content itself, as they are not structured data points.
> 
> The bottom element is a CTA: "user profile Go to our blog", which is placed in 'additionalInfo' as it does not fit the main table structure.
        """.trimIndent()

        val input = MarkdownFormattingInput(
            rawText = rawText,
            url = "https://www.otandp.com/services/corporate-wellness/thrive-mental-health",
            title = "THRIVE Mental Health - OT&P Corporate Wellness Program",
            description = "Our bespoke mental health program supports corporate employees with burnout, bullying, lifestyle, relationships, and more. Prioritize their wellbeing today",
            popupText = null
        )

        val output = markdownFormattingAgent.generate(input)
        val markdown = output.markdown

        println("=== FORMATTED MARKDOWN OUTPUT ===")
        println(markdown)
        println("=== END OUTPUT ===")
        println()
        println("Token usage: ${output.tokenUsage}")

        // Verify all markdown image references are preserved
        assertTrue(
            markdown.contains("(#img-1)"),
            "First image reference should be preserved"
        )
        assertTrue(
            markdown.contains("(#img-2)"),
            "Second image reference should be preserved"
        )
        assertTrue(
            markdown.contains("(#img-3)"),
            "Third image reference should be preserved"
        )
        assertTrue(
            markdown.contains("(#img-4)"),
            "Fourth image reference should be preserved"
        )
        assertTrue(
            markdown.contains("(#img-5)"),
            "Fifth image reference should be preserved"
        )

        // Count that all 5 images are present using markdown image syntax
        val imageCount = "!\\[.*?\\]\\(#img-\\d+\\)".toRegex().findAll(markdown).count()
        assertTrue(imageCount >= 5, "All 5 image references should be preserved, found: $imageCount")

        // Verify some structure exists (headings)
        assertTrue(markdown.contains("#"), "Should contain markdown headings")
    }

    @Test
    fun `formats simple text without images`() = runTest(testCoroutineDispatcher) {
        val rawText = """
            Welcome to Our Company
            
            We provide excellent services to our customers.
            
            Our Services:
            - Web Development
            - Mobile Apps
            - Cloud Solutions
            
            Contact us today to learn more about how we can help your business grow.
        """.trimIndent()

        val input = MarkdownFormattingInput(
            rawText = rawText,
            url = "https://example.com/services",
            title = "Our Services - Example Company",
            description = "Professional software development services",
            popupText = null
        )

        val output = markdownFormattingAgent.generate(input)
        val markdown = output.markdown

        println("=== FORMATTED MARKDOWN OUTPUT ===")
        println(markdown)
        println("=== END OUTPUT ===")
        println()
        println("Token usage: ${output.tokenUsage}")

        // Verify structure
        assertTrue(markdown.contains("#"), "Should contain markdown headings")
        assertTrue(
            markdown.contains("Web Development") || markdown.contains("web development"),
            "Should preserve content"
        )
    }
}
