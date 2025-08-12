package io.deepsearch.domain.browser

interface IBrowserContext {
    fun newPage(): IBrowserPage
}