package models.valueobjects

interface IBrowserContext {
    fun newPage(): IBrowserPage
}