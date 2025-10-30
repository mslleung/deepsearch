package io.deepsearch.domain.exceptions

import io.deepsearch.domain.models.valueobjects.UserId

sealed class DomainException(message: String) : Exception(message)

class UserNotFoundException(userId: UserId) : DomainException("User with ID $userId not found")

class UserAlreadyExistsException(userId: UserId) : DomainException("User with ID $userId already exists")

class InvalidUserDataException(message: String) : DomainException("Invalid user data: $message")

class WebScrapeException(message: String) : DomainException("Web scraping failed: $message")

class InvalidUrlException(url: String) : DomainException("Invalid URL: $url")

class WebScrapeTimeoutException(url: String) : DomainException("Web scraping timed out for URL: $url")

class AiInterpretationException(message: String) : DomainException("AI interpretation failed: $message")

class OptimisticLockException(
    entityType: String,
    entityId: Any,
    expectedVersion: Long
) : DomainException("Optimistic lock failed for $entityType with ID $entityId. Expected version: $expectedVersion. The entity was modified by another transaction.") 