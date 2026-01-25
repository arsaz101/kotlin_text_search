package com.textsearch.demo

import com.textsearch.TextSearchLibrary
import com.textsearch.core.IndexConfig
import com.textsearch.core.IndexingResult
import com.textsearch.core.SearchConfig
import com.textsearch.core.SearchResult
import kotlinx.coroutines.*
import java.nio.file.Path

// ANSI color codes for terminal highlighting
object Colors {
    const val RESET = "\u001B[0m"
    const val BOLD = "\u001B[1m"
    const val DIM = "\u001B[2m"
    
    const val RED = "\u001B[31m"
    const val GREEN = "\u001B[32m"
    const val YELLOW = "\u001B[33m"
    const val BLUE = "\u001B[34m"
    const val MAGENTA = "\u001B[35m"
    const val CYAN = "\u001B[36m"
    const val WHITE = "\u001B[37m"
    
    const val BG_RED = "\u001B[41m"
    const val BG_YELLOW = "\u001B[43m"
}

/**
 * Demo application showcasing the text search library features.
 */
fun main() = runBlocking {
    println("${Colors.BOLD}${Colors.CYAN}=== Text Search Library Demo ===${Colors.RESET}\n")

    // Get a directory to index (use current directory if no argument)
    val directory = System.getProperty("demo.directory")?.let { Path.of(it) }
        ?: Path.of(System.getProperty("user.dir"))

    println("${Colors.DIM}Indexing directory:${Colors.RESET} ${Colors.WHITE}$directory${Colors.RESET}\n")

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
            println("\n${Colors.GREEN}${Colors.BOLD}-> Indexing completed successfully!${Colors.RESET}")
            println("  ${Colors.DIM}Files indexed:${Colors.RESET} ${Colors.WHITE}${result.filesIndexed}${Colors.RESET}")
            println("  ${Colors.DIM}Total size:${Colors.RESET} ${Colors.WHITE}${formatBytes(result.totalBytes)}${Colors.RESET}")
            println("  ${Colors.DIM}Duration:${Colors.RESET} ${Colors.WHITE}${result.durationMs}ms${Colors.RESET}")
        }
        is IndexingResult.Cancelled -> {
            println("\n${Colors.YELLOW}⚠ Indexing was cancelled: ${result.message}${Colors.RESET}")
            return@runBlocking
        }
        is IndexingResult.Failed -> {
            println("\n${Colors.RED}X Indexing failed: ${result.error.message}${Colors.RESET}")
            return@runBlocking
        }
    }

    // Perform some example searches
    println("\n${Colors.BOLD}${Colors.CYAN}--- Search Examples ---${Colors.RESET}\n")

    // Get queries from command line or use defaults
    val queries = System.getProperty("demo.queries")
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?: listOf("TODO", "fun ", "class ", "import ")

    for (query in queries) {
        val response = textSearch.search(query)
        println("Search for ${Colors.MAGENTA}${Colors.BOLD}\"$query\"${Colors.RESET}:")
        println("  ${Colors.GREEN}Found: ${response.totalMatches} matches${Colors.RESET} ${Colors.DIM}in ${response.searchTimeMs}ms${Colors.RESET}")
        if (response.truncated) {
            println("  ${Colors.YELLOW}(results were truncated)${Colors.RESET}")
        }
        response.results.take(5).forEach { searchResult ->
            println(formatSearchResult(searchResult, query))
        }
        if (response.results.size > 5) {
            println("    ${Colors.DIM}... and ${response.results.size - 5} more${Colors.RESET}")
        }
        println()
    }

    // Demo: parallel search
    println("${Colors.BOLD}${Colors.CYAN}--- Parallel Search ---${Colors.RESET}")
    val multiResults = textSearch.searchMultiple(queries)
    println("Searched ${Colors.WHITE}${queries.size}${Colors.RESET} queries in parallel:")
    multiResults.forEach { (query, response) ->
        println("  ${Colors.MAGENTA}\"$query\"${Colors.RESET}: ${Colors.GREEN}${response.totalMatches} matches${Colors.RESET}")
    }

    println("\n${Colors.BOLD}${Colors.CYAN}=== Demo Complete ===${Colors.RESET}")
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1_000_000 -> "${bytes / 1_000_000} MB"
        bytes >= 1_000 -> "${bytes / 1_000} KB"
        else -> "$bytes bytes"
    }
}

/**
 * Format a search result with syntax highlighting.
 * Highlights the matched text within the line content.
 */
private fun formatSearchResult(result: SearchResult, query: String): String {
    val fileName = "${Colors.CYAN}${result.filePath.fileName}${Colors.RESET}"
    val lineNum = "${Colors.YELLOW}${result.line}${Colors.RESET}"
    val colNum = "${Colors.YELLOW}${result.column}${Colors.RESET}"
    
    // Highlight the matched text in the line content
    val lineContent = result.lineContent.trim()
    val highlightedContent = highlightMatch(lineContent, query)
    
    return "    $fileName:$lineNum:$colNum ${Colors.DIM}|${Colors.RESET} $highlightedContent"
}

/**
 * Highlight all occurrences of the query in the text.
 */
private fun highlightMatch(text: String, query: String): String {
    if (query.isBlank()) return text
    
    val result = StringBuilder()
    var currentIndex = 0
    var searchIndex = text.indexOf(query, ignoreCase = false)
    
    // If no match found with case-sensitive, try case-insensitive
    if (searchIndex == -1) {
        searchIndex = text.indexOf(query, ignoreCase = true)
    }
    
    while (searchIndex != -1) {
        // Add text before the match
        result.append(text.substring(currentIndex, searchIndex))
        // Add highlighted match
        result.append("${Colors.RED}${Colors.BOLD}")
        result.append(text.substring(searchIndex, searchIndex + query.length))
        result.append(Colors.RESET)
        
        currentIndex = searchIndex + query.length
        searchIndex = text.indexOf(query, currentIndex, ignoreCase = false)
        if (searchIndex == -1) {
            searchIndex = text.indexOf(query, currentIndex, ignoreCase = true)
        }
    }
    
    // Add remaining text
    result.append(text.substring(currentIndex))
    
    return result.toString()
}
