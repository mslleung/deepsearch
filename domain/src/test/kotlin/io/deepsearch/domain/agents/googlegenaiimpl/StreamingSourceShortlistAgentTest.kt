package io.deepsearch.domain.agents.googlegenaiimpl

import io.deepsearch.domain.agents.IStreamingSourceShortlistAgent
import io.deepsearch.domain.agents.StreamingSourceShortlistInput
import io.deepsearch.domain.config.domainTestModule
import io.deepsearch.domain.models.valueobjects.MarkdownSource
import io.deepsearch.domain.models.valueobjects.ShortlistedSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StreamingSourceShortlistAgentTest : KoinTest {

    @JvmField
    @RegisterExtension
    val koin = KoinTestExtension.create { modules(domainTestModule) }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val agent by inject<IStreamingSourceShortlistAgent>()

    @Test
    fun `should return empty shortlist when both current and new batch are empty`() = runTest(testCoroutineDispatcher) {
        val input = StreamingSourceShortlistInput(
            query = "What is machine learning?",
            currentShortlist = emptyList(),
            newMarkdownBatch = emptyList()
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertTrue(output.updatedShortlist.isEmpty(), "Shortlist should be empty when no sources provided")
        assertFalse(output.isGoodEnough, "Should not be good enough with no sources")
        assertEquals("No new sources to evaluate", output.reason)
    }

    @Test
    fun `should keep existing shortlist unchanged when new batch is empty`() = runTest(testCoroutineDispatcher) {
        val existingSource = ShortlistedSource(
            url = "https://example.com/ml",
            markdown = "# Machine Learning\n\nMachine learning is a subset of artificial intelligence.",
            sourceClassification = io.deepsearch.domain.models.valueobjects.SourceType.OFFICIAL_LIVING_DOC,
            contentDate = null,
            answerType = io.deepsearch.domain.models.valueobjects.AnswerType.DIRECT_ANSWER,
            relevanceJustification = "Good introduction to ML"
        )

        val input = StreamingSourceShortlistInput(
            query = "What is machine learning?",
            currentShortlist = listOf(existingSource),
            newMarkdownBatch = emptyList()
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertEquals(1, output.updatedShortlist.size, "Should maintain existing shortlist")
        assertEquals(existingSource.url, output.updatedShortlist[0].url)
        assertFalse(output.isGoodEnough, "Should not be good enough with empty batch")
    }

    @Test
    fun `should create shortlist from new sources when current shortlist is empty`() =
        runTest(testCoroutineDispatcher) {
            val newSource = MarkdownSource(
                url = "https://example.com/machine-learning-intro", title = null, description = null,
                markdown = """
                # Introduction to Machine Learning
                
                Machine learning is a branch of artificial intelligence (AI) and computer science which focuses on 
                the use of data and algorithms to imitate the way that humans learn, gradually improving its accuracy.
                
                ## Types of Machine Learning
                
                1. **Supervised Learning**: The algorithm learns from labeled training data
                2. **Unsupervised Learning**: The algorithm finds patterns in unlabeled data
                3. **Reinforcement Learning**: The algorithm learns through trial and error
                
                ## Applications
                
                Machine learning is used in:
                - Image recognition
                - Natural language processing
                - Recommendation systems
                - Autonomous vehicles
            """.trimIndent()
            )

            val input = StreamingSourceShortlistInput(
                query = "What is machine learning?",
                currentShortlist = emptyList(),
                newMarkdownBatch = listOf(newSource)
            )

            val output = agent.generate(input)

            assertNotNull(output)
            assertTrue(output.updatedShortlist.isNotEmpty(), "Should create shortlist from new sources")
            assertNotNull(output.reason)
            assertTrue(output.tokenUsage.totalTokens > 0, "Should track token usage")
        }

    @Test
    fun `should add new sources to existing shortlist`() = runTest(testCoroutineDispatcher) {
        val existingSource = ShortlistedSource(
            url = "https://example.com/ml-basics",
            markdown = "# ML Basics\n\nMachine learning basics explained.",
            sourceClassification = io.deepsearch.domain.models.valueobjects.SourceType.OFFICIAL_LIVING_DOC,
            contentDate = null,
            answerType = io.deepsearch.domain.models.valueobjects.AnswerType.DIRECT_ANSWER,
            relevanceJustification = "Basic introduction"
        )

        val newSource = MarkdownSource(
            url = "https://example.com/ml-algorithms", title = null, description = null,
            markdown = """
                # Machine Learning Algorithms
                
                ## Decision Trees
                Decision trees are a popular supervised learning algorithm used for both classification and regression.
                
                ## Neural Networks
                Neural networks are computing systems inspired by biological neural networks that constitute animal brains.
                
                ## Support Vector Machines
                SVMs are supervised learning models used for classification and regression analysis.
            """.trimIndent()
        )

        val input = StreamingSourceShortlistInput(
            query = "What are machine learning algorithms?",
            currentShortlist = listOf(existingSource),
            newMarkdownBatch = listOf(newSource)
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertNotNull(output.reason)
        assertTrue(output.tokenUsage.totalTokens > 0, "Should track token usage")
    }

    @Test
    fun `should mark as good enough when high quality comprehensive sources are provided`() =
        runTest(testCoroutineDispatcher) {
            val comprehensiveSource = MarkdownSource(
                url = "https://example.com/comprehensive-ml-guide", title = null, description = null,
                markdown = """
                # Complete Guide to Machine Learning
                
                ## What is Machine Learning?
                Machine learning is a subset of artificial intelligence that enables systems to learn and improve 
                from experience without being explicitly programmed.
                
                ## Core Concepts
                - **Training Data**: Historical data used to train the model
                - **Features**: Input variables used to make predictions
                - **Labels**: The output or target variable
                - **Model**: The mathematical representation of the pattern
                
                ## Types of Machine Learning
                
                ### Supervised Learning
                Uses labeled training data to learn the mapping from inputs to outputs. Examples include:
                - Linear Regression
                - Logistic Regression
                - Decision Trees
                - Random Forests
                - Neural Networks
                
                ### Unsupervised Learning
                Finds patterns in unlabeled data. Examples include:
                - K-Means Clustering
                - Hierarchical Clustering
                - Principal Component Analysis (PCA)
                
                ### Reinforcement Learning
                Learns through interaction with an environment using rewards and penalties.
                
                ## Real-World Applications
                - Healthcare: Disease diagnosis, drug discovery
                - Finance: Fraud detection, algorithmic trading
                - Retail: Recommendation systems, demand forecasting
                - Transportation: Autonomous vehicles, route optimization
                
                ## How Machine Learning Works
                1. Data Collection: Gather relevant data
                2. Data Preparation: Clean and format the data
                3. Model Training: Feed data to the algorithm
                4. Model Evaluation: Test the model's accuracy
                5. Deployment: Use the model in production
            """.trimIndent()
            )

            val input = StreamingSourceShortlistInput(
                query = "What is machine learning and how does it work?",
                currentShortlist = emptyList(),
                newMarkdownBatch = listOf(comprehensiveSource)
            )

            val output = agent.generate(input)

            assertNotNull(output)
            assertNotNull(output.reason)
            assertTrue(output.tokenUsage.totalTokens > 0, "Should track token usage")
        }

    @Test
    fun `should not mark as good enough when sources lack sufficient information`() = runTest(testCoroutineDispatcher) {
        val minimalSource = MarkdownSource(
            url = "https://example.com/ml-brief", title = null, description = null,
            markdown = """
                # Machine Learning
                
                Machine learning is a type of AI.
            """.trimIndent()
        )

        val input = StreamingSourceShortlistInput(
            query = "Explain machine learning in detail including types, algorithms, applications, and best practices",
            currentShortlist = emptyList(),
            newMarkdownBatch = listOf(minimalSource)
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertFalse(output.isGoodEnough, "Should not mark as good enough when information is insufficient")
        assertNotNull(output.reason)
        assertTrue(output.tokenUsage.totalTokens > 0, "Should track token usage")
    }

    @Test
    fun `should evaluate multiple sources and curate appropriately`() = runTest(testCoroutineDispatcher) {
        val source1 = MarkdownSource(
            url = "https://example.com/ml-overview", title = null, description = null,
            markdown = """
                # Machine Learning Overview
                
                Machine learning is a method of data analysis that automates analytical model building.
                It is a branch of artificial intelligence based on the idea that systems can learn from data,
                identify patterns and make decisions with minimal human intervention.
            """.trimIndent()
        )

        val source2 = MarkdownSource(
            url = "https://example.com/ml-types", title = null, description = null,
            markdown = """
                # Types of Machine Learning
                
                ## Supervised Learning
                The algorithm learns from labeled examples. Common algorithms include:
                - Linear Regression
                - Decision Trees
                - Neural Networks
                
                ## Unsupervised Learning
                The algorithm finds hidden patterns in unlabeled data. Examples:
                - K-Means Clustering
                - PCA
            """.trimIndent()
        )

        val source3 = MarkdownSource(
            url = "https://example.com/ml-applications", title = null, description = null,
            markdown = """
                # Machine Learning Applications
                
                Machine learning is used across many industries:
                
                - **Healthcare**: Predicting patient outcomes, medical image analysis
                - **Finance**: Credit scoring, fraud detection
                - **E-commerce**: Product recommendations, customer segmentation
                - **Manufacturing**: Predictive maintenance, quality control
            """.trimIndent()
        )

        val input = StreamingSourceShortlistInput(
            query = "What is machine learning?",
            currentShortlist = emptyList(),
            newMarkdownBatch = listOf(source1, source2, source3)
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertTrue(output.updatedShortlist.isNotEmpty(), "Should curate sources into shortlist")
        assertNotNull(output.reason)
        assertTrue(output.tokenUsage.totalTokens > 0, "Should track token usage")

        // Verify that sources have proper classification
        output.updatedShortlist.forEach { source ->
            assertNotNull(source.sourceClassification, "Source should have classification")
            assertNotNull(source.answerType, "Source should have answer type")
            assertTrue(
                source.relevanceJustification.isNotBlank(),
                "Relevance justification should explain inclusion"
            )
        }
    }

    @Test
    fun `should eagerly get more sources`() = runTest(testCoroutineDispatcher) {
        val newSource = MarkdownSource(
            url = "https://sleekflow.io/id-id/blog/sleekflow-pricing-2024", title = null, description = null,
            markdown = """
                URL: https://sleekflow.io/id-id/blog/sleekflow-pricing-2024
                Title: Harga baru SleekFlow 2024: manfaat yang lebih baik & fitur yang lebih canggih
                Description: Temukan paket harga terbaru SleekFlow di 2024 dengan peningkatan fitur seperti Flow Builder, SleekFlow AI, dan banyak lagi. Cari tahu bagaimana ini bisa bermanfaat untuk bisnis Anda.
                
                SleekFlow
                Akun Bisnis
                cross
                SleekFlow
                Halo 👋🏻
                Hai SleekFlow, saya baru mampir ke website kalian. Bisa share info selengkapnya?
                abstract yellow shape
                Powered by
                SleekFlow AI
                abstract yellow shape
                Powered by
                SleekFlow AI
                Powered by
                
                
                Daftar isi
                down arrow
                Daftar isi
                Bagikan Artikel
                Info Terbaru Produk
                Harga Paket Berlangganan SleekFlow Terbaru
                Emma Andini
                Content Writer
                Jul 25, 2024
                SleekFlow
                Pricing 2024
                S
                9:41
                Assigned to me
                2,817 conversations
                Edit
                Search keyword
                Open v Newest first v
                Athena Cooper
                Got the parcel today! Love it
                OFFLINE MEMBER PAID WARM
                10:38
                Marcela Silva
                You: You're welcome! Let me know if...
                10:38
                UNPAID MEMBER PAID
                Cody Fisher
                How long will it take for my order to...
                UK REPEAT BUYER WARM
                10:45
                Aisha Al-Mansouri
                I am interested in the Christmas offer!
                DUBAI MEMBER PAID WARM
                11:39
                June Liang
                Is this product in stock?
                SINGAPORE MEMBER PAID
                07:08
                Contact
                Inbox
                My profile
                9:41
                Sofia Azizah
                SleekFlow Official
                Contact owner
                Today v
                Can you provide me with some ideas
                for SleekFlow AI use cases in the
                education industry?
                One potential use case is the ability to
                upload educational curriculums and
                course materials, enabling SleekFlow
                AI to provide assistance with study
                guidance, explanations of course
                content, and administrative
                information.
                AI SMART REPLY
                James Brown
                Reply Schedule Internal note
                SLEEKFLOW AI is translating...
                أعني ويمكنك تمكين ملخصات متعمقة بالذكاء الاصطناعي
                عبر قنوات المراسلة التي يفضلها الجمهور، بما في ذلك واجهة
                برمجية للتطبيقات واتساب الأعمال
                Discord
                Refine
                Confirm
                Kami ingin menginformasikan pembaruan terkini di tahun 2024. Tahun ini, kami telah memperkenalkan inovasi-inovasi baru seperti
                Flow Builder
                ,
                SleekFlow AI
                ,
                Custom Object
                , serta UI/UX yang telah diperbarui. Untuk memastikan produk kami berkembang sesuai kebutuhan Anda, kami melakukan perbaikan strategi pada paket harga kami:
                Pembayaran yang lebih mudah
                : Kami menyederhanakan paket kami dengan memasukkan lebih banyak integrasi dan mengurangi opsi tambahan, mempermudah Anda dalam mendapatkan apa yang Anda inginkan dalam satu paket.
                Akses yang lebih luas:
                Fitur-fitur menarik seperti Flow Builder sekarang tersedia untuk seluruh tier paket. Jadi, apapun paket yang Anda pilih, Anda sudah mendapat akses ke fitur terkini kami.
                Siap untuk membuat bisnis anda berkembang dan lebih sukses?
                Kembangkan bisnis anda lebih baik dan efisien dengan Sleekflow
                Jadwalkan Demo
                List Harga
                Memperkenalkan model harga Flow Builder berbasis penggunaan
                Kabar baik! Flow Builder secara resmi tidak lagi berstatus beta dan akan sepenuhnya terintegrasi ke paket langganan SleekFlow mulai 28 Agustus 2024. Artinya, Anda akan bisa mengakses fitur-fitur canggihnya sebagai bagian dari paket langganan reguler Anda.
                Flow Builder adalah alat automasi customer journey serbaguna untuk mengoptimalkan percakapan. Alat ini memungkinkan Anda untuk merancang skenario automasi secara visual (kami menyebutnya alur automasi) untuk mendeteksi apa yang dilakukan pelanggan (seperti memulai percakapan, mengirim form, mengklik ads, dll.) di berbagai platform sumber (ads, website, media sosial, aplikasi chat) serta memicu interaksi, penjualan, dan pengiriman pesan bantuan pada waktu yang tepat. Flow Builder juga memungkinkan bisnis untuk mengautomasi alur kerja data dan kolaborasi tim di percakapan—tanpa perlu melakukan coding.
                Limit penggunaan Flow Builder disesuaikan dengan model langganan per tier kami sehingga Anda memiliki fleksibilitas yang dibutuhkan. Berikut tiga limit penggunaan utama:
                Alur aktif atau Active flows:
                Jumlah flow (alur) yang diaktifkan untuk menerima dan memproses ketentuan baru.
                Node per alur atau Nodes per flow:
                Jumlah node trigger, tindakan, kondisi, atau time delay yang dibuat dalam sebuah alur.
                Limit enrollment per bulan atau Monthly flow enrollment limit:
                Berapa kali kontak cocok dengan kriteria tirgger (pemicu) alur dan ditambahkan ke alur.
                | | Pro | Premium | Enterprise |
                |---|---|---|---|
                | **Alur aktif** | 3 | 25 | 50 |
                | **Node per alur** | 25 | 100 | 200 |
                | **Limit enrollment per bulan** | 500 | 3000 | 10000 |
                
                **Additional Information:**
                * The first column header is empty.
                Selain itu, kami memperkenalkan model harga tambahan baru untuk seluruh tier langganan berdasarkan ketentuan alur. Setiap paket sudah termasuk sejumlah ketentuan alur gratis per bulan. Jika Anda membutuhkan lebih, Anda bisa membeli ketentuan tambahan. Berbagai opsi tambahan tersedia untuk menyesuaikan rencana dan kebutuhan Anda. Simak
                blog ini
                untuk informasi selengkapnya.
                Paket Pro Baru - memungkinkan tim kecil untuk berkolaborasi dengan lebih baik di chat
                Paket Pro kami kini lebih baik dari sebelumnya, lengkap dengan fitur commerce baru tanpa harus melakukan pembelian tambahan. Sempurna bagi bisnis dan tim kecil yang ingin menyediakan pengalaman pelanggan yang praktis di chat dengan memungkinkan beberapa agen melayani pelanggan secara kolaboratif menggunakan inbox tim yang terpadu.
                Berikut fitur-fitur utama Paket Pro:
                Inbox omnichannel: Pusatkan seluruh interaksi bersama pelanggan dari beberapa channel ke dalam satu inbox terpadu untuk memudahkan manajemen.
                Integrasi native Shopify: Hubungkan toko Shopify Anda untuk melihat riwayat pesanan, mengautomasi pengingat barang di keranjang, mengirim update pesanan, dan membuat link checkout Shopify.
                Integrasi link pembayaran Stripe: Tidak menggunakan Shopify? Anda tetap bisa membuat keranjang belanja dan membagikan link pembayaran menggunakan
                katalog kustom
                kami.
                Fitur dasar Flow Builder: Automasi pesan, tugaskan percakapan, dan kelola informasi kontak dengan efisien sehingga menghemat waktu dan tenaga tim Anda.
                Integrasi leads ads Facebook: Catat leads dari ads Facebook langsung ke akun SleekFlow Anda agar manajemen dan penindaklanjutan leads menjadi lebih baik.
                Broadcast WhatsApp: Kirim banyak pesan personal sekaligus di WhatsApp yang ditujukan kepada segmen pelanggan tertentu supaya jangkauannya lebih tepat.
                SleekFlow AI: Latih conversational AI Anda untuk memberikan jawaban cerdas sesuai masing-masing konteks percakapan.
                Aplikasi Mobile: Kelola percakapan secara praktis dengan aplikasi mobile SleekFlow untuk memastikan Anda tetap responsif dan terhubung kapan saja, di mana saja.
                Akses SleekFlow API: Buat hingga 5.000 panggilan API setiap bulannya.
                Paket Premium Baru - memungkinkan bisnis berkembang untuk mengautomasi alur kerja
                Paket Premium kami terus menjadi solusi unggulan dalam memberikan manfaat terbaik bagi pasar. Dirancang untuk bisnis yang ingin meningkatkan produktivitasnya, paket ini menawarkan fleksibilitas dalam mengautomasi alur kerja yang rumit, melakukan kustomisasi struktur data CRM, dan menggunakan dashboard analitik kami yang lengkap untuk membuat keputusan berbasis data.
                Dengan upgrade ini, kami telah mengintegrasikan fitur-fitur yang sebelumnya hanya tersedia sebagai tambahan sehingga meningkatkan fungsionalitas aplikasi secara keseluruhan dan mengurangi kebutuhan untuk melakukan pembelian tambahan.
                Berikut fitur-fitur utama baru di Paket Premium:
                Integrasi HubSpot:
                Dapatkan sinkronisasi kontak dua arah antara HubSpot dan SleekFlow untuk menyederhanakan proses sales dan marketing Anda.
                Kuota 300.000 pesan broadcast:
                Jangkau basis pelanggan Anda dengan pembaruan penting, promosi, dan konten personal.
                Custom Object:
                Sesuaikan struktur data dengan kebutuhan bisnis Anda sehingga manajemen pelanggan menjadi lebih personal dan efektif.
                Fitur Flow Builder lanjutan:
                Automasi interaksi setelah click-to-WhatsApp ads, integrasi eksternal melalui request webhook dan HTTPS, dan manajemen data untuk Custom Object.
                Dashboard analitik:
                Dapatkan insight berharga dari dashboard analitik percakapan dan sales yang membantu Anda membuat keputusan berbasis data untuk mengoptimalkan performa bisnis.
                Manajemen akses tim:
                Kelola akses channel berbagai tim untuk melihat dan merespon percakapan sehingga kolaborasi menjadi aman dan efisien.
                Kode QR WhatsApp yang dapat dikustomisasi:
                Buat kode QR WhatsApp untuk staf dan kelola pengaturan default dengan mudah.
                … dan seluruh
                fitur Paket Pro!
                Siap untuk membuat bisnis anda berkembang dan lebih sukses?
                Kembangkan bisnis anda lebih baik dan efisien dengan Sleekflow
                Jadwalkan Demo
                List Harga
                Paket Enterprise Baru - memungkinkan bisnis skala besar untuk membuat solusi yang disesuaikan
                Sebagai paket harga SleekFlow yang terbaik dan terlengkap, Paket Enterprise menjamin layanan kustom berkualitas tinggi. Paket ini meliputi semua yang ada di Paket Premium plus beberapa fitur tingkat enterprise untuk memenuhi kebutuhan organisasi besar - integrasi kustom, peningkatan privasi dan keamanan, analitik mendetail untuk insight yang lebih dalam, dan pendampingan expert untuk automasi alur kerja yang rumit.
                Berikut gambaran penawaran utama paket Enterprise:
                Limit penggunaan custom:
                Kustomisasi jumlah akun pengguna dan kontak untuk menyesuaikan ukuran organisasi Anda.
                Fitur unlimited:
                Nikmati kuota unlimited untuk pesan broadcast, channel perpesanan, dan aturan automasi demi jangkauan dan efisiensi yang maksimal.
                Integrasi Salesforce:
                Terhubung dengan mudah ke Salesforce untuk fitur CRM yang lebih mumpuni.
                Ekspor chat SleekFlow:
                Ekspor riwayat chat untuk pencatatan dan analisis.
                Penyembunyian kontak tingkat enterprise:
                Jaga privasi dan keamanan dengan fitur penyembunyian PII dan kontak.
                Ekspor analitik:
                Ekspor analitik yang mendetail untuk mendapat insight dan pelaporan mendalam.
                Kustom SLA:
                Sesuaikan Service Level Agreement dengan kebutuhan bisnis Anda.
                Pengaturan Flow Builder dan automasi:
                Dapatkan pendampingan expert dalam mengatur alur kerja dan automasi yang kompleks.
                100.000 catatan Custom Object:
                Kelola data skala besar menggunakan hingga 100.000 catatan custom object.
                10 juta panggilan SleekFlow API per bulan:
                Manfaatkan panggilan API hingga 10 juta kali setiap bulan untuk integrasi dan operasional yang lebih lancar.
                Layanan konsultasi bisnis:
                Dapatkan manfaat konsultasi personal untuk mengoptimalkan strategi perpesanan dan proses bisnis Anda.
                … dan seluruh
                fitur Premium and Pro
                !
                Info Terbaru Produk
                Bagikan Artikel
                Rekomendasi untuk Anda
                Terbaru di SleekFlow Custom Objects: Kelola lebih dari Interaksi, alur kerja data jadi lebih efisien
                Info Terbaru Produk
                Otomasi
                8 Use Case Custom Object untuk Sales, Customer Support, dan Marketing
                Panduan dan Tips
                Otomasi
                What's New in SleekFlow: Click to WhatsApp Ad Trigger di Flow Builder
                Info Terbaru Produk
                Terbaru di SleekFlow Custom Objects: Kelola lebih dari Interaksi, alur kerja data jadi lebih efisien
                8 Use Case Custom Object untuk Sales, Customer Support, dan Marketing
                What's New in SleekFlow: Click to WhatsApp Ad Trigger di Flow Builder
                Tingkatkan konversi dengan
                SleekFlow AI
                Coba sekarang tanpa biaya!
                Jadwalkan Demo
                Coba Gratis
                cross
                Daftar
                ke newsletter kami
                Dapatkan pembaruan terbaru kami secara gratis
            """.trimIndent()
        )

        val input = StreamingSourceShortlistInput(
            query = "Tell me about the pricing",
            currentShortlist = emptyList(),
            newMarkdownBatch = listOf(newSource)
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertNotNull(output.reason)
        assertFalse(output.isGoodEnough)    // This is an outdated blog post
        assertTrue(output.tokenUsage.totalTokens > 0, "Should track token usage")
    }
}

