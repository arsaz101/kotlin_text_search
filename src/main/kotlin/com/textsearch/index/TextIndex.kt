package com.textsearch.index

import com.textsearch.core.SearchConfig
import com.textsearch.core.SearchResponse
import com.textsearch.core.SearchResult
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Thread-safe in-memory text index that stores file contents for searching.
 *
 * The index maintains a mapping of file paths to their content, split into lines
 * for efficient line/column position reporting in search results.
 *
 * This class is designed to be:
 * - Thread-safe for concurrent reads and writes
 * - Memory-efficient by storing lines as arrays
 * - Fast for substring searches using parallel processing
 */
class TextIndex {
    // Map of file path to indexed file content
    private val fileContents = ConcurrentHashMap<Path, IndexedFile>()

    /**
     * Represents an indexed file with its content split into lines.
     */
    data class IndexedFile(
        val path: Path,
        val lines: List<String>,
        val sizeBytes: Long,
        val lastModified: Long
    )

    /**
     * Number of files currently in the index.
     */
    val fileCount: Int
        get() = fileContents.size

    /**
     * Total size of all indexed files in bytes.
     */
    val totalBytes: Long
        get() = fileContents.values.sumOf { it.sizeBytes }

    /**
     * All indexed file paths.
     */
    val indexedFiles: Set<Path>
        get() = fileContents.keys.toSet()

    /**
     * Adds or updates a file in the index.
     *
     * @param path The file path
     * @param content The file content
     * @param sizeBytes The file size in bytes
     * @param lastModified The last modification timestamp
     */
    fun addFile(path: Path, content: String, sizeBytes: Long, lastModified: Long) {
        val lines = content.lines()
        fileContents[path] = IndexedFile(path, lines, sizeBytes, lastModified)
    }

    /**
     * Removes a file from the index.
     *
     * @param path The file path to remove
     * @return true if the file was in the index and removed
     */
    fun removeFile(path: Path): Boolean {
        return fileContents.remove(path) != null
    }

    /**
     * Checks if a file is in the index.
     */
    fun containsFile(path: Path): Boolean = fileContents.containsKey(path)

    /**
     * Gets the indexed file data for a path.
     */
    fun getFile(path: Path): IndexedFile? = fileContents[path]

    /**
     * Clears all indexed data.
     */
    fun clear() {
        fileContents.clear()
    }

    /**
     * Searches for a query string in a specific file.
     *
     * @param path The file path to search in
     * @param query The string to search for
     * @param config Search configuration
     * @return List of search results for this file
     */
    fun searchInFile(
        path: Path,
        query: String,
        config: SearchConfig = SearchConfig.DEFAULT
    ): List<SearchResult> {
        if (query.isEmpty()) return emptyList()
        val indexedFile = fileContents[path] ?: return emptyList()
        val results = mutableListOf<SearchResult>()
        val searchQuery = if (config.caseSensitive) query else query.lowercase()

        for ((lineIndex, line) in indexedFile.lines.withIndex()) {
            val searchLine = if (config.caseSensitive) line else line.lowercase()
            var startIndex = 0

            while (true) {
                val foundIndex = searchLine.indexOf(searchQuery, startIndex)
                if (foundIndex == -1) break

                results.add(
                    SearchResult(
                        filePath = path,
                        line = lineIndex + 1, // 1-based
                        column = foundIndex + 1, // 1-based
                        matchedText = line.substring(foundIndex, foundIndex + query.length),
                        lineContent = line.trim()
                    )
                )

                // Check per-file limit
                if (config.maxResultsPerFile != null && results.size >= config.maxResultsPerFile) {
                    return results
                }

                startIndex = foundIndex + 1
            }
        }

        return results
    }

    /**
     * Searches for a query string across all indexed files.
     * This is a simple sequential search - for parallel search, use SearchQueryExecutor.
     *
     * @param query The string to search for
     * @param config Search configuration
     * @return Search response with results and metadata
     */
    fun search(query: String, config: SearchConfig = SearchConfig.DEFAULT): SearchResponse {
        val startTime = System.currentTimeMillis()

        if (query.isEmpty()) {
            return SearchResponse.empty(System.currentTimeMillis() - startTime)
        }

        val allResults = mutableListOf<SearchResult>()
        val totalMatchCount = AtomicInteger(0)
        var truncated = false

        for (path in fileContents.keys) {
            val fileResults = searchInFile(path, query, config)
            totalMatchCount.addAndGet(fileResults.size)

            if (config.maxResults != null) {
                val remaining = config.maxResults - allResults.size
                if (remaining <= 0) {
                    truncated = true
                    break
                }
                if (fileResults.size > remaining) {
                    allResults.addAll(fileResults.take(remaining))
                    truncated = true
                    break
                }
            }
            allResults.addAll(fileResults)
        }

        return SearchResponse(
            results = allResults,
            totalMatches = totalMatchCount.get(),
            truncated = truncated,
            searchTimeMs = System.currentTimeMillis() - startTime
        )
    }

    /**
     * Creates a snapshot of the current index state for consistent iteration.
     */
    fun snapshot(): Map<Path, IndexedFile> = fileContents.toMap()
}
