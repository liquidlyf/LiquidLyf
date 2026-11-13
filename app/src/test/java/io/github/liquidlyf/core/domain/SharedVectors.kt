package io.github.liquidlyf.core.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * Reads the test vectors in backend/shared/, which firestore.rules, the Worker and the app must
 * all agree with. Gradle passes the folder as the `liquidlyf.sharedDir` system property.
 */
object SharedVectors {
    private val dir: File =
        File(System.getProperty("liquidlyf.sharedDir") ?: "../backend/shared")

    fun read(name: String): JsonObject {
        val file = dir.resolve(name)
        check(file.isFile) { "Missing shared test vectors: ${file.absolutePath}" }
        return Json.parseToJsonElement(file.readText()).jsonObject
    }
}
