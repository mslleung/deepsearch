package io.deepsearch.infrastructure.config

import io.deepsearch.domain.repositories.IWebpageIconRepository
import io.deepsearch.infrastructure.repositories.ExposedWebpageIconRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

val infrastructureTestModule = module {
    singleOf(::ExposedWebpageIconRepository) bind IWebpageIconRepository::class

    single<CoroutineDispatcher> { StandardTestDispatcher() }
}