# Text Search Library

A Kotlin library for building text indexes and executing search queries. Designed for parallel processing, graceful cancellation, and efficient result handling.

## Features

### Index Builder
- **Parallel indexing**: Files are processed concurrently using configurable parallelism
- **Progress reporting**: Real-time progress updates via Kotlin Flow
- **Cancellation support**: Cancellation that clears partial state
- **File filtering**: Configure file extensions, exclusion patterns, and size limits

### Search Query Executor
- **Parallel search**: Search queries execute across multiple threads
- **Result limiting**: Configurable per-file and total result limits to prevent memory issues
- **Multiple search modes**: Full search, count-only, find files containing
- **Case-sensitive/insensitive**: Configurable case sensitivity

### Result Format
Search results include:
- File path
- Line number (1-based)
- Column number (1-based)
- Matched text
- Full line content (for context)

## Quick Start

```kotlin
import com.textsearch.TextSearchLibrary
import com.textsearch.core.IndexingResult
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

fun main() = runBlocking {
    val textSearch = TextSearchLibrary()
    
    // Build the index
    val result = textSearch.buildIndex(Path.of("/path/to/code"))
    
    if (result is IndexingResult.Success) {
        println("Indexed ${result.filesIndexed} files")
        
        // Search for text
        val response = textSearch.search("TODO")
        response.results.forEach { match ->
            println("${match.filePath}:${match.line}:${match.column}: ${match.lineContent}")
        }
    }
}
```

## API Overview

### TextSearchLibrary

The main entry point combining all functionality:

```kotlin
val library = TextSearchLibrary(
    indexConfig = IndexConfig.DEFAULT,
    searchConfig = SearchConfig.DEFAULT
)

// Build index
val result = library.buildIndex(rootDirectory)

// Search
val response = library.search("query")
val multiResponse = library.searchMultiple(listOf("query1", "query2"))

// Utilities
val count = library.count("query")
val files = library.findFilesContaining("query")

// Cancellation
library.cancelIndexing()

```

### Progress Monitoring

```kotlin
launch {
    library.indexingProgress.collect { progress ->
        println("${progress.filesProcessed}/${progress.totalFiles} files processed")
        println("Current phase: ${progress.phase}")
    }
}

library.buildIndex(rootDirectory)
```

### Configuration

#### IndexConfig

```kotlin
val config = IndexConfig(
    parallelism = 8,                    // Number of parallel workers
    fileExtensions = setOf("kt", "java"), // File types to index (null = all)
    excludePatterns = setOf(".git/**"), // Patterns to exclude
    maxFileSizeBytes = 10_000_000,      // Skip files larger than this
    followSymlinks = false              // Whether to follow symlinks
)
```

#### SearchConfig

```kotlin
val config = SearchConfig(
    maxResults = 10_000,        // Max total results (null = unlimited)
    maxResultsPerFile = 1_000,  // Max results per file (null = unlimited)
    caseSensitive = true        // Case sensitivity
)
```

## Cancellation Behavior

When `cancelIndexing()` is called:

1. **No new files** are queued for processing
2. **Currently processing files** complete normally (to avoid corrupted state)
3. **The partial index is cleared** (no partial results retained)
4. **A `Cancelled` result** is returned from `buildIndex()`

This design ensures:
- Predictable, consistent state after cancellation
- No resource leaks or half-processed files
- Clear feedback to users about what happened

```kotlin
// Example: Cancel after timeout
val job = launch {
    delay(5000) // 5 second timeout
    library.cancelIndexing()
}

val result = library.buildIndex(rootDirectory)
job.cancel()

when (result) {
    is IndexingResult.Success -> println("Completed!")
    is IndexingResult.Cancelled -> println("Cancelled: ${result.message}")
    is IndexingResult.Failed -> println("Failed: ${result.error}")
}
```

## Result Limiting Strategy

When there are too many results, the executor employs:

1. **Per-file limit**: Stop searching a file after `maxResultsPerFile` matches
2. **Total limit**: Stop searching entirely after `maxResults` matches
3. **Early termination**: Stop spawning new search tasks once limits are reached

The `SearchResponse.truncated` flag indicates whether results were limited:

```kotlin
val response = library.search("common-word", SearchConfig(maxResults = 100))

if (response.truncated) {
    println("Results truncated. Showing ${response.results.size} of ${response.totalMatches}+ matches")
}
```

## Building and Testing

```bash
# Build the project
./gradlew build

# Run tests
./gradlew test

# Run the demo (indexes current directory with default queries)
./gradlew run

# Run with a custom directory
./gradlew run -Pdemo.directory=/path/to/search

# Run with custom search queries (comma-separated)
./gradlew run -Pdemo.queries="TODO,FIXME,error"

# Run with both custom directory and queries
./gradlew run -Pdemo.directory=/path/to/search -Pdemo.queries="TODO,FIXME,bug"
```

## Architecture

```
com.textsearch
├── TextSearchLibrary.kt      # Main facade
├── core/
│   └── Models.kt             # Data classes and configuration
├── index/
│   ├── TextIndex.kt          # In-memory index storage
│   ├── TextIndexBuilder.kt   # Parallel index builder
│   └── FileSystemWatcher.kt  # File change monitoring
├── search/
│   └── SearchQueryExecutor.kt # Parallel search executor
└── demo/
    └── Demo.kt               # Demo application
```

## Thread Safety

- `TextIndex` is thread-safe for concurrent reads and writes
- `TextIndexBuilder` prevents concurrent builds (throws `IllegalStateException`)
- `SearchQueryExecutor` supports multiple concurrent searches
- All public APIs are designed for safe concurrent use

## Requirements

- JDK 17 or higher
- Kotlin 1.9+
- Gradle 8.5+

## Dependencies

- `kotlinx-coroutines-core` - For parallel processing
- `junit-jupiter` - For testing (test scope only)
