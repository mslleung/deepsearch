package io.deepsearch.domain.models.valueobjects

data class Email(val value: String) {
    init {
        require(value.isNotBlank()) { "Email cannot be blank" }
        require(isValidEmail(value)) { "Invalid email format: $value" }
    }

    companion object {
        private val EMAIL_REGEX = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$".toRegex()

        fun isValidEmail(email: String): Boolean {
            return EMAIL_REGEX.matches(email)
        }
    }

    override fun toString(): String = value
}

