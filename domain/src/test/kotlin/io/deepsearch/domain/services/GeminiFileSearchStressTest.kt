package io.deepsearch.domain.services

import io.deepsearch.domain.config.domainBenchmarkTestModule
import io.deepsearch.domain.models.valueobjects.FileSearchStoreInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.extension.RegisterExtension
import io.deepsearch.domain.testing.IsolatedKoinExtension
import io.deepsearch.domain.testing.IsolatedKoinTest
import io.deepsearch.domain.config.IApplicationCoroutineScope
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.assertTrue
import kotlin.time.measureTimedValue

/**
 * Stress tests for Gemini File Search with realistic, complex documents.
 * 
 * Tests:
 * 1. Large multi-section technical manual (50+ pages simulated)
 * 2. Financial report with complex tables and numbers
 * 3. Multi-hop reasoning queries (info spread across sections)
 * 4. Aggregation queries (counting, summing)
 * 5. Cross-reference queries (Section A references Section B)
 * 6. Contradiction detection (conflicting information)
 * 7. Temporal queries (dates, timelines)
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class GeminiFileSearchStressTest : IsolatedKoinTest() {

    @JvmField
    @RegisterExtension
    val koinTestExtension = IsolatedKoinExtension.create {
        modules(domainBenchmarkTestModule)
    }

    private val fileSearchService by inject<IGeminiFileSearchService>()
    private val applicationScope by inject<IApplicationCoroutineScope>()

    private val testDomain = "stress-test-${System.currentTimeMillis()}.example.com"
    
    @AfterAll
    fun cleanup() {
        // Clean up application scope to cancel background coroutines
        applicationScope.close()
    }
    private var testStoreInfo: FileSearchStoreInfo? = null

    // ==================== TEST DOCUMENT 1: Technical Manual ====================

    @Test
    @Order(1)
    fun `01 - setup and upload technical manual`() = runBlocking {
        println("\n" + "=".repeat(80))
        println("GEMINI FILE SEARCH STRESS TEST")
        println("=".repeat(80))
        
        testStoreInfo = fileSearchService.getOrCreateStore(testDomain)
        println("Store created: ${testStoreInfo?.name}")
        
        println("\n--- Uploading: Technical Manual (Kubernetes Administration) ---")
        
        val technicalManual = createTechnicalManual()
        println("Document size: ${technicalManual.size} bytes")
        
        val (fileInfo, uploadTime) = measureTimedValue {
            fileSearchService.uploadFile(
                storeName = testStoreInfo!!.name,
                fileBytes = technicalManual,
                mimeType = "application/pdf",
                sourceUrl = "https://$testDomain/docs/k8s-admin-guide.pdf",
                fileHash = calculateHash(technicalManual)
            )
        }
        
        println("✅ Uploaded: ${fileInfo.name}")
        println("   Upload time: ${uploadTime.inWholeMilliseconds}ms")
        
        // Wait for indexing
        delay(3000)
    }

    @Test
    @Order(2)
    fun `02 - simple fact retrieval from technical manual`() = runBlocking {
        println("\n--- Test: Simple Fact Retrieval ---")
        
        val query = "What is the default memory limit for a Kubernetes pod?"
        println("Query: $query")
        
        val (result, time) = measureTimedValue {
            fileSearchService.queryStore(testStoreInfo!!.name, query)
        }
        
        val content = result.chunks.joinToString("\n") { it.content }
        println("\nResponse:\n${content.take(500)}")
        println("\nQuery time: ${time.inWholeMilliseconds}ms")
        println("Chunks returned: ${result.chunks.size}")
        
        // Expected: Should find "512Mi" from the technical manual
        val foundAnswer = content.contains("512") || content.contains("Mi") || content.contains("memory")
        println("Found relevant info: $foundAnswer")
    }

    @Test
    @Order(3)
    fun `03 - multi-hop reasoning query`() = runBlocking {
        println("\n--- Test: Multi-Hop Reasoning ---")
        println("(Requires connecting info from different sections)")
        
        val query = """
            If a Node fails in Kubernetes, what happens to the Pods running on it, 
            and how does this affect Services that were routing traffic to those Pods?
        """.trimIndent()
        println("Query: $query")
        
        val (result, time) = measureTimedValue {
            fileSearchService.queryStore(testStoreInfo!!.name, query)
        }
        
        val content = result.chunks.joinToString("\n") { it.content }
        println("\nResponse:\n${content.take(800)}")
        println("\nQuery time: ${time.inWholeMilliseconds}ms")
        
        // Should mention: Pod rescheduling, Service endpoint updates, traffic redirection
        val mentionsPodReschedule = content.contains("reschedul", ignoreCase = true)
        val mentionsService = content.contains("service", ignoreCase = true) || content.contains("endpoint", ignoreCase = true)
        
        println("\n📊 Multi-hop analysis:")
        println("   Mentions pod rescheduling: $mentionsPodReschedule")
        println("   Mentions service/endpoints: $mentionsService")
        println("   Multi-hop success: ${mentionsPodReschedule && mentionsService}")
    }

    @Test
    @Order(4)
    fun `04 - cross-reference query`() = runBlocking {
        println("\n--- Test: Cross-Reference Query ---")
        println("(Section references another section)")
        
        val query = "The Pod configuration section mentions resource limits. What are the recommended CPU limits mentioned in the Resource Management section?"
        println("Query: $query")
        
        val (result, time) = measureTimedValue {
            fileSearchService.queryStore(testStoreInfo!!.name, query)
        }
        
        val content = result.chunks.joinToString("\n") { it.content }
        println("\nResponse:\n${content.take(600)}")
        println("\nQuery time: ${time.inWholeMilliseconds}ms")
        
        val mentionsCPU = content.contains("CPU", ignoreCase = true) || content.contains("500m") || content.contains("millicores")
        println("Found CPU limits info: $mentionsCPU")
    }

    // ==================== TEST DOCUMENT 2: Financial Report ====================

    @Test
    @Order(10)
    fun `10 - upload financial report`() = runBlocking {
        println("\n--- Uploading: Financial Report (Q4 2025) ---")
        
        val financialReport = createFinancialReport()
        println("Document size: ${financialReport.size} bytes")
        
        val (fileInfo, uploadTime) = measureTimedValue {
            fileSearchService.uploadFile(
                storeName = testStoreInfo!!.name,
                fileBytes = financialReport,
                mimeType = "application/pdf",
                sourceUrl = "https://$testDomain/reports/q4-2025-financial.pdf",
                fileHash = calculateHash(financialReport)
            )
        }
        
        println("✅ Uploaded: ${fileInfo.name}")
        println("   Upload time: ${uploadTime.inWholeMilliseconds}ms")
        
        delay(3000)
    }

    @Test
    @Order(11)
    fun `11 - precise number retrieval`() = runBlocking {
        println("\n--- Test: Precise Number Retrieval ---")
        
        val query = "What was the exact Q4 2025 revenue for the Cloud Services division?"
        println("Query: $query")
        
        val (result, time) = measureTimedValue {
            fileSearchService.queryStore(testStoreInfo!!.name, query)
        }
        
        val content = result.chunks.joinToString("\n") { it.content }
        println("\nResponse:\n${content.take(500)}")
        println("\nQuery time: ${time.inWholeMilliseconds}ms")
        
        // Should find $847.3M
        val foundExact = content.contains("847") || content.contains("Cloud Services")
        println("Found precise number: $foundExact")
    }

    @Test
    @Order(12)
    fun `12 - aggregation query - counting`() = runBlocking {
        println("\n--- Test: Aggregation - Counting ---")
        
        val query = "How many acquisitions did the company make in 2025? List them."
        println("Query: $query")
        
        val (result, time) = measureTimedValue {
            fileSearchService.queryStore(testStoreInfo!!.name, query)
        }
        
        val content = result.chunks.joinToString("\n") { it.content }
        println("\nResponse:\n${content.take(600)}")
        println("\nQuery time: ${time.inWholeMilliseconds}ms")
        
        // Should find: TechStartup ($2.3B), DataFlow Inc ($890M), CloudNative Systems ($1.1B)
        val mentionsMultiple = listOf("TechStartup", "DataFlow", "CloudNative").count { 
            content.contains(it, ignoreCase = true) 
        }
        println("Acquisitions mentioned: $mentionsMultiple of 3")
        println("Aggregation success: ${mentionsMultiple >= 2}")
    }

    @Test
    @Order(13)
    fun `13 - aggregation query - summing`() = runBlocking {
        println("\n--- Test: Aggregation - Summing ---")
        
        val query = "What was the total amount spent on all acquisitions in 2025?"
        println("Query: $query")
        
        val (result, time) = measureTimedValue {
            fileSearchService.queryStore(testStoreInfo!!.name, query)
        }
        
        val content = result.chunks.joinToString("\n") { it.content }
        println("\nResponse:\n${content.take(600)}")
        println("\nQuery time: ${time.inWholeMilliseconds}ms")
        
        // Total should be: $2.3B + $890M + $1.1B = $4.29B
        // Check if it calculates or lists the numbers
        val mentionsTotal = content.contains("4.29") || content.contains("4.3") || 
                           (content.contains("2.3") && content.contains("890") && content.contains("1.1"))
        println("Provides total or components: $mentionsTotal")
    }

    @Test
    @Order(14)
    fun `14 - comparison query`() = runBlocking {
        println("\n--- Test: Comparison Query ---")
        
        val query = "Compare Q3 and Q4 2025 revenue. Which quarter performed better and by how much?"
        println("Query: $query")
        
        val (result, time) = measureTimedValue {
            fileSearchService.queryStore(testStoreInfo!!.name, query)
        }
        
        val content = result.chunks.joinToString("\n") { it.content }
        println("\nResponse:\n${content.take(600)}")
        println("\nQuery time: ${time.inWholeMilliseconds}ms")
        
        val mentionsComparison = content.contains("Q3", ignoreCase = true) && 
                                 content.contains("Q4", ignoreCase = true)
        println("Provides comparison: $mentionsComparison")
    }

    @Test
    @Order(15)
    fun `15 - temporal reasoning query`() = runBlocking {
        println("\n--- Test: Temporal Reasoning ---")
        
        val query = "What events happened between March and June 2025 according to the report?"
        println("Query: $query")
        
        val (result, time) = measureTimedValue {
            fileSearchService.queryStore(testStoreInfo!!.name, query)
        }
        
        val content = result.chunks.joinToString("\n") { it.content }
        println("\nResponse:\n${content.take(600)}")
        println("\nQuery time: ${time.inWholeMilliseconds}ms")
        
        // Should mention: TechStartup acquisition (March), Product launch (April), DataFlow acquisition (June)
        val temporalItems = listOf("March", "April", "May", "June").count {
            content.contains(it, ignoreCase = true)
        }
        println("Temporal items found: $temporalItems")
    }

    // ==================== TEST DOCUMENT 3: Legal Contract ====================

    @Test
    @Order(20)
    fun `20 - upload legal contract`() = runBlocking {
        println("\n--- Uploading: Legal Contract (Software License Agreement) ---")
        
        val legalContract = createLegalContract()
        println("Document size: ${legalContract.size} bytes")
        
        val (fileInfo, uploadTime) = measureTimedValue {
            fileSearchService.uploadFile(
                storeName = testStoreInfo!!.name,
                fileBytes = legalContract,
                mimeType = "application/pdf",
                sourceUrl = "https://$testDomain/legal/software-license-v2.pdf",
                fileHash = calculateHash(legalContract)
            )
        }
        
        println("✅ Uploaded: ${fileInfo.name}")
        println("   Upload time: ${uploadTime.inWholeMilliseconds}ms")
        
        delay(3000)
    }

    @Test
    @Order(21)
    fun `21 - clause identification`() = runBlocking {
        println("\n--- Test: Clause Identification ---")
        
        val query = "What are the termination conditions in the contract? Under what circumstances can either party terminate?"
        println("Query: $query")
        
        val (result, time) = measureTimedValue {
            fileSearchService.queryStore(testStoreInfo!!.name, query)
        }
        
        val content = result.chunks.joinToString("\n") { it.content }
        println("\nResponse:\n${content.take(700)}")
        println("\nQuery time: ${time.inWholeMilliseconds}ms")
        
        val mentionsTermination = content.contains("terminat", ignoreCase = true)
        val mentionsConditions = content.contains("30 days") || content.contains("breach") || content.contains("notice")
        println("Found termination clause: $mentionsTermination")
        println("Found specific conditions: $mentionsConditions")
    }

    @Test
    @Order(22)
    fun `22 - liability limits query`() = runBlocking {
        println("\n--- Test: Liability Limits ---")
        
        val query = "What is the maximum liability cap and what types of damages are excluded?"
        println("Query: $query")
        
        val (result, time) = measureTimedValue {
            fileSearchService.queryStore(testStoreInfo!!.name, query)
        }
        
        val content = result.chunks.joinToString("\n") { it.content }
        println("\nResponse:\n${content.take(600)}")
        println("\nQuery time: ${time.inWholeMilliseconds}ms")
        
        // Should find: $5M cap, excludes consequential damages
        val mentionsLiability = content.contains("liability", ignoreCase = true) || content.contains("5") 
        println("Found liability info: $mentionsLiability")
    }

    @Test
    @Order(23)
    fun `23 - conditional clause query`() = runBlocking {
        println("\n--- Test: Conditional Clause Understanding ---")
        
        val query = "If the licensee fails to pay within 30 days, what happens according to the contract?"
        println("Query: $query")
        
        val (result, time) = measureTimedValue {
            fileSearchService.queryStore(testStoreInfo!!.name, query)
        }
        
        val content = result.chunks.joinToString("\n") { it.content }
        println("\nResponse:\n${content.take(600)}")
        println("\nQuery time: ${time.inWholeMilliseconds}ms")
        
        val mentionsConsequence = content.contains("suspend") || content.contains("terminat") || 
                                  content.contains("interest") || content.contains("fee")
        println("Found consequence: $mentionsConsequence")
    }

    // ==================== TEST: Cross-Document Queries ====================

    @Test
    @Order(30)
    fun `30 - cross-document query`() = runBlocking {
        println("\n--- Test: Cross-Document Query ---")
        println("(Query spans multiple uploaded documents)")
        
        val query = "What resource limits are mentioned across all documents? Compare Kubernetes limits with financial budget limits."
        println("Query: $query")
        
        val (result, time) = measureTimedValue {
            fileSearchService.queryStore(testStoreInfo!!.name, query)
        }
        
        val content = result.chunks.joinToString("\n") { it.content }
        println("\nResponse:\n${content.take(800)}")
        println("\nQuery time: ${time.inWholeMilliseconds}ms")
        println("Chunks from different docs: ${result.chunks.map { it.sourceUrl }.distinct().size}")
    }

    @Test
    @Order(31)
    fun `31 - ambiguous entity query`() = runBlocking {
        println("\n--- Test: Ambiguous Entity Resolution ---")
        println("(Same term appears in different contexts)")
        
        val query = "What are the 'limits' mentioned in the documents? Distinguish between technical limits, financial limits, and legal limits."
        println("Query: $query")
        
        val (result, time) = measureTimedValue {
            fileSearchService.queryStore(testStoreInfo!!.name, query)
        }
        
        val content = result.chunks.joinToString("\n") { it.content }
        println("\nResponse:\n${content.take(800)}")
        println("\nQuery time: ${time.inWholeMilliseconds}ms")
        
        // Check if it distinguishes different types
        val mentionsTechnical = content.contains("memory", ignoreCase = true) || content.contains("CPU", ignoreCase = true)
        val mentionsFinancial = content.contains("budget", ignoreCase = true) || content.contains("revenue", ignoreCase = true)
        val mentionsLegal = content.contains("liability", ignoreCase = true) || content.contains("cap", ignoreCase = true)
        
        println("Distinguishes technical: $mentionsTechnical")
        println("Distinguishes financial: $mentionsFinancial")
        println("Distinguishes legal: $mentionsLegal")
    }

    // ==================== TEST: Edge Cases ====================

    @Test
    @Order(40)
    fun `40 - negation query`() = runBlocking {
        println("\n--- Test: Negation Understanding ---")
        
        val query = "What is NOT covered by the software license? What are the exclusions?"
        println("Query: $query")
        
        val (result, time) = measureTimedValue {
            fileSearchService.queryStore(testStoreInfo!!.name, query)
        }
        
        val content = result.chunks.joinToString("\n") { it.content }
        println("\nResponse:\n${content.take(600)}")
        println("\nQuery time: ${time.inWholeMilliseconds}ms")
        
        val mentionsExclusion = content.contains("not", ignoreCase = true) || 
                               content.contains("exclud", ignoreCase = true) ||
                               content.contains("except", ignoreCase = true)
        println("Understands negation: $mentionsExclusion")
    }

    @Test
    @Order(41)
    fun `41 - hypothetical query`() = runBlocking {
        println("\n--- Test: Hypothetical Scenario ---")
        
        val query = "If a company wanted to deploy 1000 pods on Kubernetes, based on the resource limits in the manual, how much total memory would be required?"
        println("Query: $query")
        
        val (result, time) = measureTimedValue {
            fileSearchService.queryStore(testStoreInfo!!.name, query)
        }
        
        val content = result.chunks.joinToString("\n") { it.content }
        println("\nResponse:\n${content.take(600)}")
        println("\nQuery time: ${time.inWholeMilliseconds}ms")
        
        // Should calculate: 1000 pods × 512Mi = 512Gi
        val mentionsCalculation = content.contains("512") || content.contains("memory") || content.contains("1000")
        println("Attempts calculation: $mentionsCalculation")
    }

    @Test
    @Order(42)
    fun `42 - very specific detail query`() = runBlocking {
        println("\n--- Test: Very Specific Detail ---")
        
        val query = "In Section 7.2 of the legal contract, what is the exact notice period for material breach?"
        println("Query: $query")
        
        val (result, time) = measureTimedValue {
            fileSearchService.queryStore(testStoreInfo!!.name, query)
        }
        
        val content = result.chunks.joinToString("\n") { it.content }
        println("\nResponse:\n${content.take(500)}")
        println("\nQuery time: ${time.inWholeMilliseconds}ms")
        
        // Should find: 30 days
        val foundSpecific = content.contains("30") || content.contains("days") || content.contains("notice")
        println("Found specific detail: $foundSpecific")
    }

    // ==================== Performance Summary ====================

    @Test
    @Order(99)
    fun `99 - performance summary`() = runBlocking {
        println("\n" + "=".repeat(80))
        println("STRESS TEST COMPLETE")
        println("=".repeat(80))
        
        val files = fileSearchService.listFiles(testStoreInfo!!.name)
        println("\nDocuments indexed: ${files.size}")
        files.forEach { println("  - ${it.displayName}") }
        
        println("\n📊 Test Summary:")
        println("  • Technical manual queries: Multi-hop reasoning, cross-references")
        println("  • Financial report queries: Precise numbers, aggregation, comparison")
        println("  • Legal contract queries: Clause identification, conditionals")
        println("  • Cross-document queries: Entity disambiguation, context switching")
        println("  • Edge cases: Negation, hypotheticals, specific details")
    }

    // ==================== Document Generators ====================

    private fun createTechnicalManual(): ByteArray {
        val content = """
KUBERNETES ADMINISTRATION GUIDE
Version 2.5 - Technical Reference Manual

TABLE OF CONTENTS
1. Introduction to Kubernetes
2. Pod Configuration
3. Node Management
4. Service Networking
5. Resource Management
6. High Availability
7. Troubleshooting

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

CHAPTER 1: INTRODUCTION TO KUBERNETES

Kubernetes is a container orchestration platform that automates deployment,
scaling, and management of containerized applications. This manual provides
comprehensive guidance for cluster administrators.

Key Concepts:
• Pods: The smallest deployable units in Kubernetes
• Nodes: Worker machines that run Pods
• Services: Network abstractions for Pod communication
• Deployments: Declarative updates for Pods

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

CHAPTER 2: POD CONFIGURATION

2.1 Basic Pod Specification

A Pod is a group of one or more containers with shared storage and network
resources. For resource limits, see Chapter 5: Resource Management.

Default Configuration:
  Name: application-pod
  Containers: 1
  Restart Policy: Always
  
2.2 Pod Lifecycle

Pods go through several phases:
• Pending: Pod accepted but containers not created
• Running: Pod bound to node, all containers created
• Succeeded: All containers terminated successfully
• Failed: All containers terminated, at least one failed
• Unknown: Pod state cannot be determined

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

CHAPTER 3: NODE MANAGEMENT

3.1 Node Architecture

Nodes are worker machines managed by the control plane. Each node runs:
• kubelet: Agent that ensures containers are running
• kube-proxy: Network proxy for Service implementation
• Container runtime: Software for running containers

3.2 Node Failure Handling

When a Node fails, the following sequence occurs:
1. Node controller detects node is unreachable (default: 40 seconds)
2. Node is marked as "NotReady"
3. After pod-eviction-timeout (default: 5 minutes), Pods are scheduled for deletion
4. Pods are rescheduled to healthy nodes by the scheduler
5. Services automatically update endpoints to remove failed Pod IPs

IMPORTANT: Service traffic is automatically redirected to healthy Pods on other
nodes. This ensures high availability of applications.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

CHAPTER 4: SERVICE NETWORKING

4.1 Service Types

• ClusterIP: Internal cluster IP (default)
• NodePort: Exposes service on each node's IP
• LoadBalancer: External load balancer provisioning
• ExternalName: Maps service to external DNS name

4.2 Endpoint Management

Services maintain a list of endpoints (Pod IPs) that receive traffic.
When Pods are created or destroyed, endpoints are automatically updated.
This is managed by the Endpoint Controller.

Reference: For Pod lifecycle events that trigger endpoint updates, see
Chapter 3, Section 3.2 (Node Failure Handling).

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

CHAPTER 5: RESOURCE MANAGEMENT

5.1 Resource Requests and Limits

Every Pod should specify resource requests and limits:

Memory:
  Default Request: 256Mi
  Default Limit: 512Mi
  Maximum Recommended: 8Gi per container

CPU:
  Default Request: 100m (100 millicores = 0.1 CPU)
  Default Limit: 500m (500 millicores = 0.5 CPU)
  Maximum Recommended: 4 cores per container

5.2 Quality of Service Classes

Based on resource configuration, Pods receive QoS classes:
• Guaranteed: Requests equal limits for all resources
• Burstable: At least one container has request < limit
• BestEffort: No requests or limits specified

5.3 Resource Quotas

Administrators can limit total resources per namespace:
  Total Memory: 64Gi maximum
  Total CPU: 32 cores maximum
  Total Pods: 100 maximum

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

CHAPTER 6: HIGH AVAILABILITY

6.1 Multi-Node Clusters

For production, deploy across multiple availability zones:
• Minimum 3 control plane nodes
• Minimum 3 worker nodes
• Use Pod anti-affinity rules

6.2 Backup and Recovery

Regular etcd backups should be performed:
• Frequency: Every 30 minutes
• Retention: 7 days
• Storage: Off-cluster location

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

CHAPTER 7: TROUBLESHOOTING

7.1 Common Issues

Issue: Pods stuck in Pending state
Cause: Insufficient resources or node selector mismatch
Solution: Check node resources, review Pod specifications

Issue: Pods in CrashLoopBackOff
Cause: Application crashes repeatedly
Solution: Check container logs, verify configuration

7.2 Diagnostic Commands

kubectl get pods -o wide
kubectl describe pod <pod-name>
kubectl logs <pod-name> -c <container-name>
kubectl top nodes
kubectl top pods

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

END OF MANUAL
        """.trimIndent()
        
        return createPdfFromText(content, "Kubernetes Administration Guide")
    }

    private fun createFinancialReport(): ByteArray {
        val content = """
ACME CORPORATION
Q4 2025 FINANCIAL REPORT

Prepared by: Finance Department
Date: January 15, 2026
Classification: Internal - Confidential

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

EXECUTIVE SUMMARY

Q4 2025 marked another strong quarter for ACME Corporation. Total revenue
reached $2.47 billion, representing a 23% increase year-over-year. This 
growth was primarily driven by our Cloud Services division and strategic
acquisitions completed throughout the year.

Key Highlights:
• Total Revenue: $2.47B (Q4 2025) vs $2.01B (Q4 2024)
• Operating Margin: 34.2%
• Net Income: $612M
• Cash Position: $8.9B

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

REVENUE BY DIVISION

                    Q3 2025     Q4 2025     Change
Cloud Services      $756.2M     $847.3M     +12.0%
Enterprise Software $523.1M     $589.4M     +12.7%
Hardware Sales      $412.8M     $456.2M     +10.5%
Professional Svcs   $298.4M     $321.7M     +7.8%
Licensing           $187.3M     $255.4M     +36.4%

TOTAL              $2,177.8M   $2,470.0M    +13.4%

Cloud Services continues to be our fastest-growing segment, with $847.3M
in Q4 2025 revenue. This represents 34% of total company revenue.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

2025 ACQUISITIONS

ACME Corporation completed three strategic acquisitions in 2025:

1. TechStartup Inc. (March 2025)
   Purchase Price: $2.3 billion
   Business: AI/ML platform for enterprise automation
   Integration Status: Complete
   Revenue Contribution: $180M (Q3-Q4 combined)

2. DataFlow Inc. (June 2025)
   Purchase Price: $890 million
   Business: Real-time data streaming infrastructure
   Integration Status: In progress (85% complete)
   Revenue Contribution: $67M (Q4 only)

3. CloudNative Systems (September 2025)
   Purchase Price: $1.1 billion
   Business: Kubernetes management platform
   Integration Status: Early stage (40% complete)
   Revenue Contribution: $23M (Q4 only)

Total Acquisition Spend: $4.29 billion
Combined Revenue Contribution: $270M

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

QUARTERLY COMPARISON

                Q1 2025    Q2 2025    Q3 2025    Q4 2025
Revenue         $1,892M    $2,034M    $2,178M    $2,470M
Operating Cost  $1,203M    $1,287M    $1,398M    $1,626M
Operating Inc.  $689M      $747M      $780M      $844M
Net Income      $478M      $523M      $561M      $612M

Full Year 2025:
• Total Revenue: $8,574M
• Total Net Income: $2,174M
• Operating Margin: 35.7%

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

KEY EVENTS TIMELINE - 2025

January 2025:
• Launched Enterprise AI Suite v3.0
• Opened new data center in Singapore

March 2025:
• Acquired TechStartup Inc. for $2.3B
• Dr. Jane Smith joined as CTO

April 2025:
• Released CloudConnect Platform 2.0
• Expanded European sales team by 45%

June 2025:
• Acquired DataFlow Inc. for $890M
• Achieved SOC 2 Type II certification

September 2025:
• Acquired CloudNative Systems for $1.1B
• Announced $500M stock buyback program

December 2025:
• Reached 10,000 enterprise customers milestone
• Launched partner program in APAC region

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

BUDGET AND LIMITS

Operating Budget Limits for FY 2026:
• R&D Spending: Maximum $1.2B (15% of revenue)
• Marketing: Maximum $400M (5% of revenue)
• Capital Expenditure: Maximum $600M
• Acquisition Budget: $3B remaining authorization

Cash Flow Targets:
• Operating Cash Flow: Minimum $2.4B
• Free Cash Flow: Minimum $1.8B

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

FORWARD LOOKING STATEMENTS

This report contains forward-looking statements. Actual results may differ
materially from those projected due to various risk factors.

END OF REPORT
        """.trimIndent()
        
        return createPdfFromText(content, "Q4 2025 Financial Report")
    }

    private fun createLegalContract(): ByteArray {
        val content = """
SOFTWARE LICENSE AGREEMENT
Version 2.0

This Software License Agreement ("Agreement") is entered into as of
January 1, 2026 ("Effective Date") by and between:

LICENSOR: ACME Software Corporation
Address: 100 Technology Drive, San Francisco, CA 94105

LICENSEE: [Customer Name]
Address: [Customer Address]

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

SECTION 1: DEFINITIONS

1.1 "Software" means the ACME Enterprise Platform, including all modules,
    updates, and documentation provided by Licensor.

1.2 "License Term" means the period from Effective Date until terminated
    in accordance with Section 7.

1.3 "Authorized Users" means employees and contractors of Licensee who
    are authorized to access the Software, not to exceed the quantity
    specified in the Order Form.

1.4 "Confidential Information" means any non-public information disclosed
    by either party, including but not limited to source code, business
    plans, and customer data.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

SECTION 2: GRANT OF LICENSE

2.1 License Grant. Subject to the terms of this Agreement, Licensor grants
    Licensee a non-exclusive, non-transferable license to:
    (a) Install and use the Software on Licensee's systems
    (b) Permit Authorized Users to access the Software
    (c) Create backup copies for archival purposes only

2.2 Restrictions. Licensee shall NOT:
    (a) Modify, adapt, or create derivative works of the Software
    (b) Reverse engineer, decompile, or disassemble the Software
    (c) Sublicense, rent, or lease the Software to third parties
    (d) Remove or alter any proprietary notices
    (e) Use the Software for illegal purposes

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

SECTION 3: FEES AND PAYMENT

3.1 License Fees. Licensee shall pay the license fees specified in the
    Order Form. Annual license fees are due within 30 days of invoice.

3.2 Late Payment. Payments not received within 30 days shall accrue
    interest at 1.5% per month (18% annually). If payment is not received
    within 60 days, Licensor may suspend access to the Software.

3.3 Taxes. All fees are exclusive of taxes. Licensee is responsible for
    all applicable taxes except Licensor's income taxes.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

SECTION 4: SUPPORT AND MAINTENANCE

4.1 Support Services. During the License Term, Licensor shall provide:
    (a) Email support with 24-hour response time
    (b) Phone support during business hours (9 AM - 6 PM PST)
    (c) Access to online documentation and knowledge base
    (d) Software updates and security patches

4.2 Excluded Services. Support does NOT include:
    (a) Customization or integration services
    (b) Support for modified or altered Software
    (c) Hardware support
    (d) Training beyond initial onboarding

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

SECTION 5: CONFIDENTIALITY

5.1 Obligations. Each party agrees to:
    (a) Protect Confidential Information with reasonable care
    (b) Use Confidential Information only for Agreement purposes
    (c) Not disclose Confidential Information to third parties

5.2 Exceptions. Confidential Information does not include information that:
    (a) Is publicly available without breach
    (b) Was known prior to disclosure
    (c) Is independently developed without reference to disclosed info
    (d) Is required to be disclosed by law

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

SECTION 6: WARRANTIES AND DISCLAIMERS

6.1 Limited Warranty. Licensor warrants that:
    (a) Software will perform substantially as documented for 90 days
    (b) Software does not infringe third-party intellectual property

6.2 Warranty Disclaimer. EXCEPT AS EXPRESSLY STATED, THE SOFTWARE IS
    PROVIDED "AS IS" WITHOUT WARRANTIES OF ANY KIND. LICENSOR DISCLAIMS
    ALL IMPLIED WARRANTIES INCLUDING MERCHANTABILITY AND FITNESS FOR A
    PARTICULAR PURPOSE.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

SECTION 7: TERMINATION

7.1 Term. This Agreement remains in effect until terminated.

7.2 Termination for Convenience. Either party may terminate with 90 days
    written notice, effective at the end of the current annual term.

7.3 Termination for Material Breach. Either party may terminate if:
    (a) The other party commits a material breach
    (b) Written notice of breach is provided
    (c) The breaching party fails to cure within 30 days of notice

7.4 Effect of Termination. Upon termination:
    (a) All licenses granted hereunder terminate
    (b) Licensee must cease using and destroy all copies of Software
    (c) Licensee must certify destruction in writing within 10 days
    (d) Sections 5, 6, 8, and 9 survive termination

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

SECTION 8: LIMITATION OF LIABILITY

8.1 Liability Cap. LICENSOR'S TOTAL LIABILITY SHALL NOT EXCEED $5,000,000
    (FIVE MILLION DOLLARS) OR THE FEES PAID IN THE 12 MONTHS PRECEDING
    THE CLAIM, WHICHEVER IS GREATER.

8.2 Exclusion of Damages. IN NO EVENT SHALL EITHER PARTY BE LIABLE FOR:
    (a) Consequential damages
    (b) Incidental damages
    (c) Special damages
    (d) Punitive damages
    (e) Loss of profits, revenue, or data
    
    EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGES.

8.3 Exceptions. The limitations in 8.1 and 8.2 do not apply to:
    (a) Breach of Section 2.2 (License Restrictions)
    (b) Breach of Section 5 (Confidentiality)
    (c) Indemnification obligations under Section 9
    (d) Gross negligence or willful misconduct

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

SECTION 9: INDEMNIFICATION

9.1 By Licensor. Licensor shall defend and indemnify Licensee against
    claims that the Software infringes any third-party patent, copyright,
    or trade secret.

9.2 By Licensee. Licensee shall defend and indemnify Licensor against
    claims arising from Licensee's use of the Software in violation of
    this Agreement or applicable law.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

SECTION 10: GENERAL PROVISIONS

10.1 Governing Law. This Agreement is governed by California law.

10.2 Dispute Resolution. Disputes shall be resolved by binding arbitration
     in San Francisco, California, under AAA Commercial Arbitration Rules.

10.3 Entire Agreement. This Agreement constitutes the entire agreement
     between the parties and supersedes all prior agreements.

10.4 Amendment. This Agreement may only be modified by written agreement
     signed by both parties.

10.5 Severability. If any provision is unenforceable, remaining provisions
     shall continue in effect.

10.6 Waiver. Failure to enforce any provision does not waive future rights.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

IN WITNESS WHEREOF, the parties have executed this Agreement.

ACME Software Corporation           Licensee
_________________________          _________________________
Signature                          Signature

_________________________          _________________________
Name/Title                         Name/Title

_________________________          _________________________
Date                               Date

END OF AGREEMENT
        """.trimIndent()
        
        return createPdfFromText(content, "Software License Agreement v2.0")
    }

    private fun createPdfFromText(text: String, title: String): ByteArray {
        val document = PDDocument()
        try {
            val font = PDType1Font(Standard14Fonts.FontName.COURIER)
            val fontSize = 10f
            val margin = 50f
            val leading = 14f
            
            val lines = text.split("\n")
            var currentPage: PDPage? = null
            var contentStream: PDPageContentStream? = null
            var yPosition = 0f
            val pageHeight = PDRectangle.A4.height
            val pageWidth = PDRectangle.A4.width
            val maxWidth = pageWidth - 2 * margin
            
            for (line in lines) {
                // Start new page if needed
                if (currentPage == null || yPosition < margin + 50) {
                    contentStream?.endText()
                    contentStream?.close()
                    
                    currentPage = PDPage(PDRectangle.A4)
                    document.addPage(currentPage)
                    contentStream = PDPageContentStream(document, currentPage)
                    contentStream.beginText()
                    contentStream.setFont(font, fontSize)
                    contentStream.setLeading(leading)
                    yPosition = pageHeight - margin
                    contentStream.newLineAtOffset(margin, yPosition)
                }
                
                // Handle long lines by truncating (simple approach)
                val displayLine = if (line.length > 80) line.take(77) + "..." else line
                
                try {
                    contentStream?.showText(displayLine)
                    contentStream?.newLine()
                    yPosition -= leading
                } catch (e: Exception) {
                    // Skip problematic characters
                    contentStream?.showText(displayLine.replace(Regex("[^\\x00-\\x7F]"), ""))
                    contentStream?.newLine()
                    yPosition -= leading
                }
            }
            
            contentStream?.endText()
            contentStream?.close()
            
            val baos = ByteArrayOutputStream()
            document.save(baos)
            return baos.toByteArray()
        } finally {
            document.close()
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun calculateHash(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(bytes)
        return Base64.encode(hashBytes)
    }
}
