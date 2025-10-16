# 🔍 DeepSearch

> **Fast, accurate, configurable web search pipeline for LLM grounding**

DeepSearch is a production-ready web intelligence system that grounds Large Language Model (LLM) responses using real-time website information. Built for speed, accuracy, and flexibility, it provides a complete pipeline from web crawling to answer generation—all highly configurable for your specific use case.

**Give it a URL and a query → Get an accurate, sourced answer in seconds.**

[![Kotlin](https://img.shields.io/badge/Kotlin-2.2.0-7F52FF?logo=kotlin)](https://kotlinlang.org/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

### Why DeepSearch?

Traditional web scraping misses the full picture. DeepSearch goes beyond simple HTML parsing:

- ✅ **Understands modern web apps** (JavaScript-rendered content, dynamic elements)
- ✅ **Extracts from images and tables** (not just text)
- ✅ **Follows relevant links intelligently** (AI-guided, not blind crawling)
- ✅ **Scales with parallelism** (process multiple pages simultaneously)
- ✅ **Provides verifiable answers** (every claim linked to source URLs)

---

## 🎯 Three Core Pillars

### ⚡ Fast
- **Parallel processing**: Multiple pages crawled simultaneously with concurrent browser contexts
- **Breadth-first exploration**: Efficient wave-based traversal minimizes latency
- **Async-first architecture**: Kotlin coroutines power non-blocking operations throughout
- **Smart caching**: URL normalization prevents redundant processing

### 🎯 Accurate
- **High-fidelity extraction**: Multi-modal processing captures text, images, tables, and UI elements
- **AI-powered reasoning**: Google Gemini 2.5 agents ensure semantic understanding
- **Source attribution**: Every answer linked to specific URLs for verification
- **Multi-lingual OCR**: Supports English, Chinese (Simplified & Traditional) text extraction

### ⚙️ Configurable
- **Pluggable search strategies**: Choose between agentic crawling or Google Search benchmarking
- **Flexible extraction pipeline**: Enable/disable extractors based on content type
- **Customizable crawl depth**: Balance thoroughness vs. speed for your use case
- **DDD architecture**: Clean separation of concerns makes extending functionality straightforward

---

## ✨ Key Features

### 🚀 High-Performance Crawling

- **Parallel Page Processing**: Process multiple pages simultaneously using independent browser contexts
- **Recursive Link Discovery**: AI agents identify and follow only relevant links, reducing waste
- **Breadth-First Traversal**: Wave-based exploration optimizes for quick discovery of key content
- **URL Deduplication**: Smart normalization prevents redundant crawls of equivalent URLs
- **Async Operations**: Non-blocking I/O throughout the entire pipeline

### 🎯 Multi-Modal Content Extraction

Extract meaning from every element of modern web pages:

- **Text & Structure**: Markdown conversion preserving semantic hierarchy
- **Tables**: AI-powered identification and structured data extraction
- **Images**: Vision model analysis + OCR for embedded text
- **Icons & UI Elements**: Semantic interpretation of visual components
- **Navigation**: Automatic detection and filtering of nav elements
- **Popups**: Identification and handling of modal overlays

**Technologies**: Playwright (browser automation), Tesseract (OCR), OpenCV (image processing), Accessibility tree analysis

### ⚙️ Flexible Configuration

- **Multiple Search Strategies**:
  - **Agentic Browser Search**: Autonomous crawling with full control
  - **Google Search Integration**: Leverage Google's index for benchmarking
  
- **Pluggable Architecture**: DDD-based design allows easy customization
- **Configurable Extraction**: Enable/disable extractors based on your content needs
- **Request-Scoped Components**: Efficient resource management with Koin DI

## 🚀 The Search Pipeline

DeepSearch implements a **three-stage pipeline** optimized for speed and accuracy:

### Stage 1: Parallel Discovery ⚡
**Speed optimization: Concurrent execution**

```
Input URL + Query
    ├─→ [Thread 1] Extract initial page content
    └─→ [Thread 2] Google search for related pages
            ↓
    Combined link pool (deduplicated)
```

### Stage 2: Recursive Exploration 🔍
**Accuracy optimization: AI-guided traversal**

```
Wave 1: Process initial links in parallel
  ├─→ [Browser Context 1] Page A → Extract + Discover links
  ├─→ [Browser Context 2] Page B → Extract + Discover links
  └─→ [Browser Context N] Page N → Extract + Discover links
        ↓
Wave 2: Process newly discovered links
  ├─→ [Browser Context 1] New Page 1 → ...
  └─→ [Browser Context N] New Page N → ...
        ↓
Continue until no new relevant links (AI-filtered)

### Stage 3: Answer Synthesis 🎯
**Accuracy optimization: Structured generation**

```
All Extracted Content
    ↓
[Aggregate Markdown]
    ↓
[GenerateAnswerAgent (Gemini 2.5)]
    ↓
Comprehensive Answer + Source Citations
```

### Content Extraction Pipeline (Per Page)
**Configuration: Enable/disable extractors as needed**

```
Web Page (Playwright Browser)
    ↓
[Accessibility Tree + DOM Analysis]
    ↓
[Parallel Multi-Modal Extraction] ⚡
    ├─→ Text → Markdown Conversion
    ├─→ Tables → AI Identification → Structured Data
    ├─→ Images → OCR + Vision Analysis → Text Content
    ├─→ Icons → Semantic Interpretation
    └─→ Links → AI Relevance Filtering → Navigation Queue
    ↓
[Unified Markdown] + [Discovered Links]
```

**Configurable extractors** allow you to optimize for your content type and performance requirements.

---

## 📦 Getting Started

### Prerequisites

- JDK 21 or higher
- Gradle 8.x (wrapper included)
- Google ADK API credentials (for Gemini access)

### Installation

```bash
# Clone the repository
git clone https://github.com/yourusername/deepsearch.git
cd deepsearch

# Build the project
./gradlew build

# Run tests
./gradlew test

# Start the server
./gradlew :presentation:run
```

### Quick Start

**1. Query a website:**
```bash
curl -X POST http://localhost:8080/api/search \
  -H "Content-Type: application/json" \
  -d '{
    "query": "What are the pricing plans?",
    "url": "https://example.com"
  }'
```

**2. Get structured results:**
```json
{
  "originalQuery": {
    "query": "What are the pricing plans?",
    "url": "https://example.com"
  },
  "answer": "Based on the website content, there are three pricing plans: Basic ($9/month), Professional ($29/month), and Enterprise (custom pricing). The Basic plan includes up to 10 users and 50GB storage...",
  "content": "# Pricing\n\n## Basic Plan\n$9/month\n- Up to 10 users...",
  "sources": [
    "https://example.com/pricing",
    "https://example.com/plans/basic",
    "https://example.com/plans/pro"
  ]
}
```

**That's it!** DeepSearch handles:
- Finding relevant pages on the target site
- Extracting text, tables, and images
- Following links to gather comprehensive information
- Synthesizing an accurate, grounded answer

---

## 🗺️ Roadmap

### ✅ Current Capabilities (v0.1)
- ⚡ **Fast**: Parallel crawling with async operations
- 🎯 **Accurate**: Multi-modal extraction with AI reasoning
- ⚙️ **Configurable**: Multiple strategies and pluggable architecture
- 🏗️ **Production-ready**: DDD architecture with comprehensive testing

### 🚧 Near Term (v0.2 - Performance & Scale)
- [ ] **Performance Metrics Dashboard**: Real-time monitoring of latency, token usage, and throughput
- [ ] **Smart Caching**: Persistent cache layer for extracted content (TTL-based invalidation)
- [ ] **Rate Limiting & Throttling**: Configurable delays and concurrent request limits
- [ ] **Crawl Budget Configuration**: Max pages, max depth, timeout controls
- [ ] **Batch Processing API**: Process multiple queries efficiently

### 🔮 Future (v0.3 - Advanced Configuration)
- [ ] **Strategy Composition**: Combine strategies dynamically based on content type
- [ ] **Custom Extractor Plugins**: SDK for domain-specific extractors
- [ ] **Selective Extraction Config**: Fine-grained control per query (e.g., "tables only")
- [ ] **Cost Optimization Modes**: Balance quality vs. cost with preset profiles
- [ ] **Distributed Crawling**: Horizontal scaling across multiple nodes

### 🌟 Vision (v1.0+)
- [ ] **Multi-LLM Support**: Configure different models per agent
- [ ] **Streaming Responses**: Real-time answer generation as pages are crawled
- [ ] **Incremental Updates**: Monitor and re-crawl changed content
- [ ] **GraphQL API**: Flexible querying of search capabilities
- [ ] **Web UI Dashboard**: Visual configuration and monitoring

---

## 🤝 Contributing

DeepSearch is built to be extended and customized. We welcome contributions that enhance **speed**, **accuracy**, or **configurability**!

### Priority Areas
- **Performance optimizations**: Faster crawling, smarter caching, parallel extraction improvements
- **New extractors**: Specialized content types (PDFs, videos, audio transcripts, etc.)
- **Configuration options**: More knobs to tune behavior for specific use cases
- **Additional strategies**: Alternative approaches to crawling and extraction
- **Benchmarking**: Real-world comparisons and performance metrics

---

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

---

## 🙏 Acknowledgments

- **Google Gemini 2.5**: Powering our AI agent orchestra
- **Playwright**: Enabling robust browser automation
- **Domain-Driven Design**: Framework by Eric Evans and Vaughn Vernon
- **Kotlin & Coroutines**: Making concurrent code elegant

---

## 📧 Contact

For questions, suggestions, or collaborations, please open an issue or reach out through GitHub.

---

<div align="center">

### Ready to ground your LLMs with real web data?

**DeepSearch: Fast • Accurate • Configurable**

Built with 💙 using Kotlin | Powered by Google Gemini 2.5 | Production-ready architecture

</div>
