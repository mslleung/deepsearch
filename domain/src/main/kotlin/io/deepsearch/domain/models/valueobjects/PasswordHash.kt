package io.deepsearch.domain.models.valueobjects

data class PasswordHash(val value: String) {
    init {
        require(value.isNotBlank()) { "Password hash cannot be blank" }
    }

    override fun toString(): String = "PasswordHash(***)"
}

