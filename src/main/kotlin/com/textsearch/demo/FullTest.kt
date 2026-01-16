package com.textsearch.demo

import com.textsearch.TextSearchLibrary
import com.textsearch.core.IndexConfig
import com.textsearch.core.IndexingResult
import com.textsearch.core.SearchConfig
import kotlinx.coroutines.*
import java.nio.file.Path

/**
 * Comprehensive test of all library features.
 */
fun main() = runBlocking {
    println("=" .repeat(60))
    println("       TEXT SEARCH LIBRARY - COMPREHENSIVE TEST")
    println("=".repeat(60))
    
    val projectDir = Path.of(System.getProperty("user.dir"))
    val srcDir = projectDir.resolve("src")
    
    // Test 1: Basic indexing and searching
    println("\n[TEST 1] Basic Indexing and Searching")
    println("-".repeat(40))
    
    val library = TextSearchLibrary(
        indexConfig = IndexConfig.SOURCE_CODE,
        searchConfig = SearchConfig(maxResults = 50, maxResultsPerFile = 10)
    )
    
    val result = library.buildIndex(srcDir)
    
    when (result) {
        is IndexingResult.Success -> {
            println("✓ Indexed ${result.filesIndexed} files (${result.totalBytes} bytes) in ${result.durationMs}ms")
        }
        else -> {
            println("✗ Indexing failed: $result")
            return@runBlocking
        }
    }
    
    // Test 2: Search with results
    println("\n[TEST 2] Search for 'TextIndex'")
    println("-".repeat(40))
    
    val searchResult = library.search("TextIndex")
    println("Found ${searchResult.totalMatches} matches in ${searchResult.searchTimeMs}ms")
    println("Truncated: ${searchResult.truncated}")
    println("\nFirst 5 results:")
    searchResult.results.take(5).forEachIndexed { i, r ->
        println("  ${i+1}. ${r.filePath.fileName}:${r.line}:${r.column}")
        println("     → ${r.lineContent.take(60)}${if (r.lineContent.length > 60) "..." else ""}")
    }
    
    // Test 3: Case-insensitive search
    println("\n[TEST 3] Case-Insensitive Search for 'todo'")
    println("-".repeat(40))
    
    val caseInsensitive = library.search("todo", SearchConfig(caseSensitive = false, maxResults = 10))
    println("Found ${caseInsensitive.totalMatches} matches (case-insensitive)")
    caseInsensitive.results.take(3).forEach { r ->
        println("  ${r.filePath.fileName}:${r.line} - matched: '${r.matchedText}'")
    }
    
    // Test 4: Count matches
    println("\n[TEST 4] Count Occurrences")
    println("-".repeat(40))
    
    val keywords = listOf("fun ", "class ", "val ", "var ", "suspend ")
    keywords.forEach { keyword ->
        val count = library.count(keyword)
        println("  '$keyword' appears $count times")
    }
    
    // Test 5: Find files containing
    println("\n[TEST 5] Find Files Containing 'coroutine'")
    println("-".repeat(40))
    
    val filesWithCoroutine = library.findFilesContaining("coroutine", SearchConfig(caseSensitive = false))
    println("Found in ${filesWithCoroutine.size} files:")
    filesWithCoroutine.forEach { path ->
        println("  - ${path.fileName}")
    }
    
    // Test 6: Parallel multi-query search
    println("\n[TEST 6] Parallel Multi-Query Search")
    println("-".repeat(40))
    
    val queries = listOf("Search", "Index", "Config", "Result", "Progress")
    val startTime = System.currentTimeMillis()
    val multiResults = library.searchMultiple(queries)
    val totalTime = System.currentTimeMillis() - startTime
    
    println("Searched ${queries.size} queries in parallel in ${totalTime}ms:")
    multiResults.forEach { (query, response) ->
        println("  '$query': ${response.totalMatches} matches")
    }
    
    // Test 7: Cancellation
    println("\n[TEST 7] Cancellation Test")
    println("-".repeat(40))
    
    val largeLibrary = TextSearchLibrary()
    
    val cancelJob = launch {
        delay(5) // Cancel after 5ms
        largeLibrary.cancelIndexing()
        println("  Cancellation requested!")
    }
    
    val cancelResult = largeLibrary.buildIndex(projectDir) // Index entire project
    cancelJob.join()
    
    when (cancelResult) {
        is IndexingResult.Cancelled -> {
            println("✓ Cancellation worked! ${cancelResult.message}")
            println("  Files indexed before cancellation: ${cancelResult.filesIndexedBeforeCancellation}")
        }
        is IndexingResult.Success -> {
            println("  (Indexing completed before cancellation could take effect)")
            println("  Indexed ${cancelResult.filesIndexed} files")
        }
        else -> println("  Result: $cancelResult")
    }
    
    // Test 8: Result limiting
    println("\n[TEST 8] Result Limiting Strategy")
    println("-".repeat(40))
    
    val unlimitedConfig = SearchConfig(maxResults = null, maxResultsPerFile = null)
    val limitedConfig = SearchConfig(maxResults = 10, maxResultsPerFile = 3)
    
    val unlimitedResults = library.search("val", unlimitedConfig)
    val limitedResults = library.search("val", limitedConfig)
    
    println("Search for 'val':")
    println("  Unlimited: ${unlimitedResults.totalMatches} results, truncated=${unlimitedResults.truncated}")
    println("  Limited (max=10, perFile=3): ${limitedResults.results.size} results, truncated=${limitedResults.truncated}")
    
    // Summary
    println("\n" + "=".repeat(60))
    println("                    ALL TESTS COMPLETED!")
    println("=".repeat(60))
    println("\nLibrary Statistics:")
    println("  • Files in index: ${library.indexedFileCount}")
    println("  • Total indexed size: ${library.indexedBytes / 1024} KB")
    println("  • Indexed files: ${library.indexedFiles.map { it.fileName }.joinToString(", ")}")
}
