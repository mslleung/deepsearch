package io.deepsearch.domain.models.valueobjects

interface IBrowserContext {
    fun newPage(): IBrowserPage
}