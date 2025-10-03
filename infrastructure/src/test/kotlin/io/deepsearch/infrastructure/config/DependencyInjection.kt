package io.deepsearch.infrastructure.config

import io.deepsearch.domain.repositories.IWebpageIconRepository
import io.deepsearch.domain.repositories.IWebpagePopupRepository
import io.deepsearch.infrastructure.database.DatabaseConfig
import io.deepsearch.infrastructure.repositories.ExposedWebpageIconRepository
import io.deepsearch.infrastructure.repositories.ExposedWebpagePopupRepository
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

    single<CoroutineDispatcher> { StandardTestDispatcher() }
}


