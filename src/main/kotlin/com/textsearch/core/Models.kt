package com.textsearch.core

import java.nio.file.Path

/**
 * Represents a single search result with file location and position information.
 *
 * @property filePath The path to the file containing the match
 * @property line The 1-based line number where the match was found
 * @property column The 1-based column number where the match starts
 * @property matchedText The actual text that was matched
 * @property lineContent The full content of the line containing the match (for context)
 */
data class SearchResult(
    val filePath: Path,
    val line: Int,
    val column: Int,
    val matchedText: String,
    val lineContent: String
) {
    override fun toString(): String = "$filePath:$line:$column: $lineContent"
}

/**
 * Represents the search results with metadata about the search operation.
 *
 * @property results The list of search results
 * @property totalMatches Total number of matches found (may be higher than results.size if truncated)
 * @property truncated Whether the results were truncated due to limit
 * @property searchTimeMs Time taken to perform the search in milliseconds
 */
data class SearchResponse(
    val results: List<SearchResult>,
    val totalMatches: Int,
    val truncated: Boolean,
    val searchTimeMs: Long
) {
    companion object {
        fun empty(searchTimeMs: Long = 0) = SearchResponse(
            results = emptyList(),
            totalMatches = 0,
            truncated = false,
            searchTimeMs = searchTimeMs
        )
    }
}

/**
 * Configuration for search operations.
 *
 * @property maxResults Maximum number of results to return (null for unlimited)
 * @property maxResultsPerFile Maximum results per file (null for unlimited)
 * @property caseSensitive Whether the search should be case-sensitive
 */
data class SearchConfig(
    val maxResults: Int? = 10_000,
    val maxResultsPerFile: Int? = 1_000,
    val caseSensitive: Boolean = true
) {
    companion object {
        val DEFAULT = SearchConfig()
        val UNLIMITED = SearchConfig(maxResults = null, maxResultsPerFile = null)
    }
}

/**
 * Progress information for indexing operations.
 *
 * @property filesProcessed Number of files processed so far
 * @property totalFiles Total number of files to process (may be estimated)
 * @property currentFile The file currently being processed
 * @property bytesProcessed Total bytes processed
 * @property phase Current phase of the indexing operation
 */
data class IndexingProgress(
    val filesProcessed: Int,
    val totalFiles: Int,
    val currentFile: Path?,
    val bytesProcessed: Long,
    val phase: IndexingPhase
) {
    val percentComplete: Double
        get() = if (totalFiles > 0) (filesProcessed.toDouble() / totalFiles) * 100 else 0.0

    override fun toString(): String {
        return when (phase) {
            IndexingPhase.SCANNING -> "Scanning for files..."
            IndexingPhase.INDEXING -> "Indexing: $filesProcessed/$totalFiles files (${String.format("%.1f", percentComplete)}%)"
            IndexingPhase.COMPLETED -> "Completed: $filesProcessed files indexed"
            IndexingPhase.CANCELLED -> "Cancelled: $filesProcessed files indexed before cancellation"
            IndexingPhase.FAILED -> "Failed after processing $filesProcessed files"
        }
    }
}

/**
 * Phases of the indexing operation.
 */
enum class IndexingPhase {
    /** Scanning the file system to discover files */
    SCANNING,
    /** Actively indexing file contents */
    INDEXING,
    /** Indexing completed successfully */
    COMPLETED,
    /** Indexing was cancelled by user */
    CANCELLED,
    /** Indexing failed due to an error */
    FAILED
}

/**
 * Result of an indexing operation.
 */
sealed class IndexingResult {
    /** Indexing completed successfully */
    data class Success(
        val filesIndexed: Int,
        val totalBytes: Long,
        val durationMs: Long
    ) : IndexingResult()

    /** Indexing was cancelled by user request */
    data class Cancelled(
        val filesIndexedBeforeCancellation: Int,
        val message: String = "Indexing was cancelled by user request"
    ) : IndexingResult()

    /** Indexing failed due to an error */
    data class Failed(
        val error: Throwable,
        val filesProcessedBeforeFailure: Int
    ) : IndexingResult()
}

/**
 * Configuration for the index builder.
 *
 * @property parallelism Number of parallel workers for indexing (default: number of CPU cores)
 * @property fileExtensions File extensions to include (null for all text files)
 * @property excludePatterns Glob patterns for paths to exclude
 * @property maxFileSizeBytes Maximum file size to index (larger files are skipped)
 * @property followSymlinks Whether to follow symbolic links
 */
data class IndexConfig(
    val parallelism: Int = Runtime.getRuntime().availableProcessors(),
    val fileExtensions: Set<String>? = null,
    val excludePatterns: Set<String> = setOf("*.class", "*.jar", "*.zip", "*.tar", "*.gz", ".git/**", "node_modules/**", "build/**", "target/**"),
    val maxFileSizeBytes: Long = 10 * 1024 * 1024, // 10 MB
    val followSymlinks: Boolean = false
) {
    companion object {
        val DEFAULT = IndexConfig()

        /** Configuration for source code files */
        val SOURCE_CODE = IndexConfig(
            fileExtensions = setOf(
                "kt", "java", "scala", "groovy",  // JVM
                "py", "rb", "php",                 // Scripting
                "js", "ts", "jsx", "tsx",          // JavaScript
                "c", "cpp", "h", "hpp",            // C/C++
                "go", "rs", "swift",               // Modern compiled
                "xml", "json", "yaml", "yml",      // Config
                "md", "txt", "csv"                 // Text
            )
        )
    }
}
