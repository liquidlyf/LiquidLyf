package io.github.liquidlyf.core.firebase.dto

import java.io.File

/**
 * The field names firestore.rules allows in each document, read from the rules file itself
 * (Gradle passes its path as `liquidlyf.rulesFile`). If session B adds or renames a field, the
 * app's DTO tests fail here instead of the rules rejecting writes at runtime.
 */
object RulesKeys {
    private val rules: String by lazy {
        val file = File(System.getProperty("liquidlyf.rulesFile") ?: "../firebase/firestore.rules")
        check(file.isFile) { "Missing firestore.rules: ${file.absolutePath}" }
        file.readText()
    }

    private val quoted = Regex("'([A-Za-z]+)'")

    /** Names in the first `[...]` list of rules function [name] (e.g. `requestKeys`, `validOffer`). */
    fun inFunction(name: String): Set<String> = firstList(Regex("""function $name\([^)]*\)\s*\{[^\[]*\[([^\]]*)]"""))

    /** Names in the first `[...]` list after `match /<collection>/{...}`. */
    fun inMatch(collection: String): Set<String> = firstList(Regex("""match /$collection/\{\w+\}[^\[]*\[([^\]]*)]"""))

    private fun firstList(pattern: Regex): Set<String> {
        val list = checkNotNull(pattern.find(rules)) { "Not found in firestore.rules: $pattern" }.groupValues[1]
        return quoted.findAll(list).map { it.groupValues[1] }.toSet().also { check(it.isNotEmpty()) }
    }
}
