package com.textsearch.demo

import com.textsearch.TextSearchLibrary
import com.textsearch.core.IndexConfig
import com.textsearch.core.IndexingResult
import com.textsearch.core.SearchConfig
import kotlinx.coroutines.*
import java.nio.file.Path

/**
 * Demo application showcasing the text search library features.
 */
fun main() = runBlocking {
    println("=== Text Search Library Demo ===\n")

    // Get a directory to index (use current directory if no argument)
    val directory = System.getProperty("demo.directory")?.let { Path.of(it) }
        ?: Path.of(System.getProperty("user.dir"))

    println("Indexing directory: $directory\n")

    // Create the library with source code configuration
    val textSearch = TextSearchLibrary(
        indexConfig = IndexConfig.SOURCE_CODE,
        searchConfig = SearchConfig(maxResults = 100, maxResultsPerFile = 10)
    )

    // Monitor progress in a separate coroutine
    val progressJob = launch {
        textSearch.indexingProgress.collect { progress ->
            print("\r${progress}                    ")
        }
    }

    // Build the index
    val result = textSearch.buildIndex(directory)
    progressJob.cancel()
    println()

    when (result) {
        is IndexingResult.Success -> {
            println("\n✓ Indexing completed successfully!")
            println("  Files indexed: ${result.filesIndexed}")
            println("  Total size: ${formatBytes(result.totalBytes)}")
            println("  Duration: ${result.durationMs}ms")
        }
        is IndexingResult.Cancelled -> {
            println("\n⚠ Indexing was cancelled: ${result.message}")
            return@runBlocking
        }
        is IndexingResult.Failed -> {
            println("\n✗ Indexing failed: ${result.error.message}")
            return@runBlocking
        }
    }

    // Perform some example searches
    println("\n--- Search Examples ---\n")

    val queries = listOf("TODO", "fun ", "class ", "import ")

    for (query in queries) {
        val response = textSearch.search(query)
        println("Search for \"$query\":")
        println("  Found: ${response.totalMatches} matches in ${response.searchTimeMs}ms")
        if (response.truncated) {
            println("  (results were truncated)")
        }
        response.results.take(3).forEach { result ->
            println("    ${result.filePath.fileName}:${result.line}:${result.column}")
        }
        if (response.results.size > 3) {
            println("    ... and ${response.results.size - 3} more")
        }
        println()
    }

    // Demo: parallel search
    println("--- Parallel Search ---")
    val multiResults = textSearch.searchMultiple(queries)
    println("Searched ${queries.size} queries in parallel:")
    multiResults.forEach { (query, response) ->
        println("  \"$query\": ${response.totalMatches} matches")
    }

    println("\n=== Demo Complete ===")
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1_000_000 -> "${bytes / 1_000_000} MB"
        bytes >= 1_000 -> "${bytes / 1_000} KB"
        else -> "$bytes bytes"
    }
}
