package io.deepsearch.infrastructure.config

import io.deepsearch.domain.repositories.*
import io.deepsearch.infrastructure.database.DatabaseConfig
import io.deepsearch.infrastructure.repositories.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import org.koin.core.module.dsl.scopedOf
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

val infrastructureTestModule = module {
    // Initialize database for tests
    single(createdAtStart = true) { DatabaseConfig.configureDatabase() }
    
    singleOf(::ExposedUserRepository) bind IUserRepository::class
    singleOf(::ExposedWebpageIconRepository) bind IWebpageIconRepository::class
    singleOf(::ExposedWebpageImageRepository) bind IWebpageImageRepository::class
    singleOf(::ExposedWebpagePopupRepository) bind IWebpagePopupRepository::class
    singleOf(::ExposedWebpageTableRepository) bind IWebpageTableRepository::class
    singleOf(::ExposedWebpageTableInterpretationRepository) bind IWebpageTableInterpretationRepository::class
    singleOf(::ExposedWebpageNavigationElementRepository) bind IWebpageNavigationElementRepository::class
    singleOf(::ExposedWebpageMarkdownRepository) bind IWebpageMarkdownRepository::class
    singleOf(::ExposedPdfMarkdownRepository) bind IPdfMarkdownRepository::class

    single<CoroutineDispatcher> { StandardTestDispatcher() }
}


