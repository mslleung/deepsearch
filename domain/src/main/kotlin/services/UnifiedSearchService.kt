package services

interface IUnifiedSearchService {

}

/**
 * This is the core entry-point of our entire search technology.
 *
 * It starts all search strategies in parallel and return the results.
 */
class UnifiedSearchService: IUnifiedSearchService {
    // TODO a suspend function that starts AgenticSearchService and GoogleSearchService in parallel and return the results
}