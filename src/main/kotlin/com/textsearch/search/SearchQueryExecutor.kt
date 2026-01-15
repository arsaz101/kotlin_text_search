package com.textsearch.search

import com.textsearch.core.SearchConfig
import com.textsearch.core.SearchResponse
import com.textsearch.core.SearchResult
import com.textsearch.index.TextIndex
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/**
 * Executes search queries against a text index.
 *
 * Features:
 * - Parallel search across files using coroutines
 * - Configurable result limiting (per-file and total)
 * - Case-sensitive and case-insensitive search
 * - Multiple concurrent search requests
 *
 * ## Result Limiting Strategy
 *
 * When there are too many results, the executor employs the following strategy:
 * 1. **Per-file limit**: Stop searching a file after [SearchConfig.maxResultsPerFile] matches
 * 2. **Total limit**: Stop searching entirely after [SearchConfig.maxResults] matches
 * 3. **Early termination**: Files are processed in parallel, but we stop spawning new
 *    search tasks once the total limit is reached
 *
 * The [SearchResponse.truncated] flag indicates whether results were limited.
 *
 * ## Usage Example
 *
 * ```kotlin
 * val executor = SearchQueryExecutor(index)
 *
 * // Simple search
 * val response = executor.search("TODO")
 *
 * // Search with custom config
 * val response = executor.search("error", SearchConfig(
 *     maxResults = 100,
 *     caseSensitive = false
 * ))
 *
 * // Multiple concurrent searches
 * coroutineScope {
 *     val results = listOf("TODO", "FIXME", "BUG").map { query ->
 *         async { executor.search(query) }
 *     }.awaitAll()
 * }
 * ```
 */
class SearchQueryExecutor(
    private val index: TextIndex,
    private val parallelism: Int = Runtime.getRuntime().availableProcessors()
) {
    /**
     * Searches for a query string in the index.
     *
     * @param query The string to search for
     * @param config Search configuration
     * @return Search response containing results and metadata
     */
    suspend fun search(
        query: String,
        config: SearchConfig = SearchConfig.DEFAULT
    ): SearchResponse = withContext(Dispatchers.Default) {
        if (query.isEmpty()) {
            return@withContext SearchResponse.empty()
        }

        val startTime = System.currentTimeMillis()
        val snapshot = index.snapshot()

        if (snapshot.isEmpty()) {
            return@withContext SearchResponse.empty(System.currentTimeMillis() - startTime)
        }

        val results = searchParallel(snapshot.keys.toList(), query, config)
        val searchTimeMs = System.currentTimeMillis() - startTime

        val maxResults = config.maxResults
        val truncated = maxResults != null && results.size >= maxResults

        SearchResponse(
            results = results,
            totalMatches = results.size,
            truncated = truncated,
            searchTimeMs = searchTimeMs
        )
    }

    /**
     * Searches for a query string, returning results as a sequence for memory efficiency.
     * Useful when processing a very large number of results.
     *
     * @param query The string to search for
     * @param config Search configuration
     * @return Sequence of search results
     */
    fun searchSequence(
        query: String,
        config: SearchConfig = SearchConfig.DEFAULT
    ): Sequence<SearchResult> = sequence {
        if (query.isEmpty()) return@sequence

        val snapshot = index.snapshot()
        var totalYielded = 0

        for ((path, _) in snapshot) {
            val fileResults = index.searchInFile(path, query, config)

            for (result in fileResults) {
                if (config.maxResults != null && totalYielded >= config.maxResults) {
                    return@sequence
                }
                yield(result)
                totalYielded++
            }
        }
    }

    private suspend fun searchParallel(
        files: List<Path>,
        query: String,
        config: SearchConfig
    ): List<SearchResult> = coroutineScope {
        val semaphore = Semaphore(parallelism)
        val results = mutableListOf<SearchResult>()
        val resultsMutex = Mutex()
        val totalCount = AtomicInteger(0)
        val shouldStop = { config.maxResults?.let { totalCount.get() >= it } ?: false }

        val jobs = files.map { file ->
            async {
                if (shouldStop()) return@async emptyList()

                semaphore.acquire()
                try {
                    if (shouldStop()) return@async emptyList()

                    val fileResults = index.searchInFile(file, query, config)

                    // Add results with limit checking
                    resultsMutex.withLock {
                        val maxResults = config.maxResults
                        if (maxResults != null) {
                            val remaining = maxResults - results.size
                            if (remaining <= 0) {
                                return@async emptyList()
                            }
                            val toAdd = fileResults.take(remaining)
                            results.addAll(toAdd)
                            totalCount.addAndGet(toAdd.size)
                        } else {
                            results.addAll(fileResults)
                            totalCount.addAndGet(fileResults.size)
                        }
                    }

                    fileResults
                } finally {
                    semaphore.release()
                }
            }
        }

        jobs.awaitAll()
        results
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
        config: SearchConfig = SearchConfig.DEFAULT
    ): Map<String, SearchResponse> = coroutineScope {
        queries.associateWith { query ->
            async { search(query, config) }
        }.mapValues { it.value.await() }
    }

    /**
     * Counts occurrences of a query without returning full results.
     * More efficient when you only need the count.
     *
     * @param query The string to search for
     * @param config Search configuration (limits still apply)
     * @return Total count of matches
     */
    suspend fun count(
        query: String,
        config: SearchConfig = SearchConfig.DEFAULT
    ): Int = withContext(Dispatchers.Default) {
        if (query.isEmpty()) return@withContext 0

        val snapshot = index.snapshot()
        val semaphore = Semaphore(parallelism)
        val totalCount = AtomicInteger(0)

        val jobs = snapshot.keys.map { path ->
            async {
                semaphore.acquire()
                try {
                    val count = index.searchInFile(path, query, config).size
                    totalCount.addAndGet(count)
                } finally {
                    semaphore.release()
                }
            }
        }

        jobs.awaitAll()
        totalCount.get()
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
        config: SearchConfig = SearchConfig.DEFAULT
    ): Set<Path> = withContext(Dispatchers.Default) {
        if (query.isEmpty()) return@withContext emptySet()

        val snapshot = index.snapshot()
        val semaphore = Semaphore(parallelism)
        val matchingFiles = mutableSetOf<Path>()
        val mutex = Mutex()

        val jobs = snapshot.keys.map { path ->
            async {
                semaphore.acquire()
                try {
                    // Only need to find one match per file
                    val singleMatchConfig = config.copy(maxResultsPerFile = 1)
                    val hasMatch = index.searchInFile(path, query, singleMatchConfig).isNotEmpty()
                    if (hasMatch) {
                        mutex.withLock { matchingFiles.add(path) }
                    }
                } finally {
                    semaphore.release()
                }
            }
        }

        jobs.awaitAll()
        matchingFiles
    }
}
