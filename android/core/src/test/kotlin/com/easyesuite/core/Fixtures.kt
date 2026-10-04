package com.easyesuite.core

import java.io.File

/** Loads the JSON samples in /shared/fixtures (captured from the live API, PII scrubbed). */
object Fixtures {
    private val dir: File by lazy {
        val fromProp = System.getProperty("fixtures.dir")?.let(::File)
        val candidates = listOfNotNull(
            fromProp,
            File("../../shared/fixtures"),
            File("../shared/fixtures"),
            File("shared/fixtures"),
        )
        candidates.firstOrNull { it.isDirectory } ?: error("fixtures dir not found; tried ${candidates.map { it.absolutePath }}")
    }

    fun read(name: String): String = File(dir, name).readText()
}
