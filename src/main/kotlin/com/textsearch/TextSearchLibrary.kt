package com.textsearch

import com.textsearch.core.*
import com.textsearch.index.FileSystemWatcher
import com.textsearch.index.TextIndex
import com.textsearch.index.TextIndexBuilder
import com.textsearch.search.SearchQueryExecutor
import kotlinx.coroutines.flow.SharedFlow
import java.nio.charset.Charset
import java.nio.file.Path

/**
 * Main entry point for the text search library.
 *
 * This class provides a unified API for building text indexes and executing searches.
 * It combines [TextIndexBuilder], [TextIndex], [SearchQueryExecutor], and optionally
 * [FileSystemWatcher] into a single convenient interface.
 *
 * ## Quick Start
 *
 * ```kotlin
 * // Create the library instance
 * val textSearch = TextSearchLibrary()
 *
 * // Build an index
 * runBlocking {
 *     val result = textSearch.buildIndex(Path.of("/path/to/code"))
 *     println("Indexed ${result.filesIndexed} files")
 * }
 *
 * // Search for text
 * runBlocking {
 *     val results = textSearch.search("TODO")
 *     results.results.forEach { println(it) }
 * }
 * ```
 *
 * ## Features
 *
 * - **Parallel indexing**: Files are indexed using multiple threads
 * - **Progress tracking**: Subscribe to progress updates during indexing
 * - **Cancellation**: Gracefully cancel ongoing indexing operations
 * - **Parallel search**: Search queries execute across multiple threads
 * - **Result limiting**: Configurable limits prevent memory issues with large result sets
 * - **Incremental updates**: Optionally watch for file changes and update index automatically
 *
 * @param indexConfig Configuration for the index builder
 * @param searchConfig Default configuration for search operations
 */
class TextSearchLibrary(
    private val indexConfig: IndexConfig = IndexConfig.DEFAULT,
    private val searchConfig: SearchConfig = SearchConfig.DEFAULT
) {
    private val index = TextIndex()
    private val builder = TextIndexBuilder(indexConfig, index)
    private val executor = SearchQueryExecutor(index, indexConfig.parallelism)
    private var watcher: FileSystemWatcher? = null

    /**
     * Progress updates during indexing operations.
     */
    val indexingProgress: SharedFlow<IndexingProgress>
        get() = builder.progress

    /**
     * Whether an indexing operation is currently running.
     */
    val isIndexing: Boolean
        get() = builder.isRunning

    /**
     * Number of files currently in the index.
     */
    val indexedFileCount: Int
        get() = index.fileCount

    /**
     * Total size of indexed files in bytes.
     */
    val indexedBytes: Long
        get() = index.totalBytes

    /**
     * All indexed file paths.
     */
    val indexedFiles: Set<Path>
        get() = index.indexedFiles

    /**
     * Builds an index for all text files in the given directory.
     *
     * This operation runs in parallel and can be monitored via [indexingProgress].
     * To cancel, call [cancelIndexing].
     *
     * @param rootDirectory The directory to index
     * @param charset The charset to use when reading files
     * @return The result of the indexing operation
     */
    suspend fun buildIndex(
        rootDirectory: Path,
        charset: Charset = Charsets.UTF_8
    ): IndexingResult {
        return builder.buildIndex(rootDirectory, charset)
    }

    /**
     * Cancels any ongoing indexing operation.
     *
     * Cancellation is graceful:
     * - Files currently being processed will complete
     * - The partial index will be cleared
     * - A [IndexingResult.Cancelled] result will be returned from [buildIndex]
     */
    fun cancelIndexing() {
        builder.cancel()
    }

    /**
     * Searches for a query string in the index.
     *
     * @param query The string to search for
     * @param config Search configuration (uses default if not specified)
     * @return Search response containing results and metadata
     */
    suspend fun search(
        query: String,
        config: SearchConfig = searchConfig
    ): SearchResponse {
        return executor.search(query, config)
    }

    /**
     * Searches for multiple queries in parallel.
     *
     * @param queries The queries to search for
     * @param config Search configuration applied to each query
     * @return Map of query to search response
     */
    suspend fun searchMultiple(
        queries: List<String>,
        config: SearchConfig = searchConfig
    ): Map<String, SearchResponse> {
        return executor.searchMultiple(queries, config)
    }

    /**
     * Counts occurrences of a query without returning full results.
     *
     * @param query The string to search for
     * @param config Search configuration
     * @return Total count of matches
     */
    suspend fun count(query: String, config: SearchConfig = searchConfig): Int {
        return executor.count(query, config)
    }

    /**
     * Finds files containing the query.
     *
     * @param query The string to search for
     * @param config Search configuration
     * @return Set of file paths containing at least one match
     */
    suspend fun findFilesContaining(
        query: String,
        config: SearchConfig = searchConfig
    ): Set<Path> {
        return executor.findFilesContaining(query, config)
    }

    /**
     * Starts watching for file system changes and updating the index automatically.
     *
     * @param rootDirectory The directory to watch
     * @throws IllegalStateException if already watching
     */
    suspend fun startWatching(rootDirectory: Path) {
        if (watcher != null) {
            throw IllegalStateException("Already watching. Call stopWatching() first.")
        }
        watcher = FileSystemWatcher(index, indexConfig).also {
            it.startWatching(rootDirectory)
        }
    }

    /**
     * Stops watching for file system changes.
     */
    fun stopWatching() {
        watcher?.stopWatching()
        watcher = null
    }

    /**
     * Whether the library is currently watching for file changes.
     */
    val isWatching: Boolean
        get() = watcher?.isWatching == true

    /**
     * Clears the index and stops any ongoing operations.
     */
    fun clear() {
        stopWatching()
        builder.cancel()
        index.clear()
    }
}
