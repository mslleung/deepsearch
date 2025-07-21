package io.deepsearch.domain.entities

import io.deepsearch.domain.valueobjects.UserId
import io.deepsearch.domain.valueobjects.UserName
import io.deepsearch.domain.valueobjects.UserAge

data class User(
    val id: UserId? = null,
    val name: UserName,
    val age: UserAge
) {
    fun isAdult(): Boolean = age.value >= 18
    
    fun canVote(): Boolean = age.value >= 18
    
    fun withId(id: UserId): User = copy(id = id)
} 