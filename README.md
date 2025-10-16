# 🔍 DeepSearch

> **Fast, accurate, configurable web search pipeline for LLM grounding**

DeepSearch is a production-ready web intelligence system that grounds Large Language Model (LLM) responses using real-time website information. Built for speed, accuracy, and flexibility, it provides a complete pipeline from web crawling to answer generation—all highly configurable for your specific use case.

**Give it a URL and a query → Get an accurate, sourced answer in seconds.**

[![Kotlin](https://img.shields.io/badge/Kotlin-2.2.0-7F52FF?logo=kotlin)](https://kotlinlang.org/)

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

## 📧 Contact

For questions, suggestions, or collaborations, please open an issue or reach out through GitHub.

---

<div align="center">

### Ready to ground your LLMs with real web data?

**DeepSearch: Fast • Accurate • Configurable**

Built with 💙 using Kotlin | Powered by Google Gemini 2.5

</div>
