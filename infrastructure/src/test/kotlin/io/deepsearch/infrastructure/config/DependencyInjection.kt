package io.deepsearch.infrastructure.config

import io.deepsearch.domain.repositories.*
import io.deepsearch.infrastructure.database.DatabaseConfig
import io.deepsearch.infrastructure.repositories.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

val infrastructureTestModule = module {
    // Initialize database for tests
    single(createdAtStart = true) { DatabaseConfig.configureDatabase() }
    
    singleOf(::ExposedWebpageIconRepository) bind IWebpageIconRepository::class
    singleOf(::ExposedWebpagePopupRepository) bind IWebpagePopupRepository::class
    singleOf(::ExposedWebpageTableRepository) bind IWebpageTableRepository::class
    singleOf(::ExposedWebpageTableInterpretationRepository) bind IWebpageTableInterpretationRepository::class
    singleOf(::ExposedWebpageNavigationElementRepository) bind IWebpageNavigationElementRepository::class

    single<CoroutineDispatcher> { StandardTestDispatcher() }
}


