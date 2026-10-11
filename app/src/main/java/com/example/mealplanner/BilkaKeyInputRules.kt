package com.example.mealplanner

/** Mirror Prej's server-side format guard before sending a secret over the network. */
internal object BilkaKeyInputRules {
    private val pattern = Regex("^prej_(dev|biz|pro)_[A-Za-z0-9_-]{12,256}$")

    fun isValid(value: String): Boolean = pattern.matches(value)
}
