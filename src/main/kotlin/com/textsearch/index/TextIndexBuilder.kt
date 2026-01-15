package com.textsearch.index

import com.textsearch.core.IndexConfig
import com.textsearch.core.IndexingPhase
import com.textsearch.core.IndexingProgress
import com.textsearch.core.IndexingResult
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import java.nio.charset.Charset
import java.nio.charset.MalformedInputException
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Builds a text index for files in a directory.
 *
 * Features:
 * - Parallel processing of files using coroutines
 * - Progress reporting via Flow
 * - Graceful cancellation support
 * - Configurable file filtering and limits
 *
 * ## Cancellation Behavior
 *
 * When [cancel] is called:
 * 1. No new files will be queued for processing
 * 2. Currently processing files will complete (to avoid corrupted state)
 * 3. The index will be cleared (partial index is not retained)
 * 4. A [IndexingResult.Cancelled] result is returned
 *
 * This ensures a clean, predictable state after cancellation.
 *
 * ## Usage Example
 *
 * ```kotlin
 * val builder = TextIndexBuilder()
 *
 * // Subscribe to progress updates
 * launch {
 *     builder.progress.collect { progress ->
 *         println(progress)
 *     }
 * }
 *
 * // Build the index
 * val result = builder.buildIndex(Path.of("/path/to/directory"))
 *
 * // Or cancel if needed
 * builder.cancel()
 * ```
 */
class TextIndexBuilder(
    private val config: IndexConfig = IndexConfig.DEFAULT,
    private val targetIndex: TextIndex = TextIndex()
) {
    private val _index: TextIndex = targetIndex
    private val _cancelled = AtomicBoolean(false)
    private val _isRunning = AtomicBoolean(false)

    // Progress tracking
    private val _filesProcessed = AtomicInteger(0)
    private val _bytesProcessed = AtomicLong(0)
    private val _totalFiles = AtomicInteger(0)
    private val _currentPhase = MutableStateFlow(IndexingPhase.SCANNING)
    private val _currentFile = MutableStateFlow<Path?>(null)

    // Progress flow for external observers
    private val _progressFlow = MutableSharedFlow<IndexingProgress>(replay = 1)
    val progress: SharedFlow<IndexingProgress> = _progressFlow.asSharedFlow()

    /**
     * The built index. Only valid after [buildIndex] completes successfully.
     */
    val index: TextIndex
        get() = _index

    /**
     * Whether the builder is currently running.
     */
    val isRunning: Boolean
        get() = _isRunning.get()

    /**
     * Whether cancellation has been requested.
     */
    val isCancelled: Boolean
        get() = _cancelled.get()

    /**
     * Cancels the current indexing operation.
     *
     * This is a graceful cancellation that:
     * - Stops processing new files immediately
     * - Allows currently processing files to complete
     * - Clears the partial index
     * - Returns a Cancelled result from buildIndex
     *
     * This method is thread-safe and can be called from any thread.
     */
    fun cancel() {
        _cancelled.set(true)
    }

    /**
     * Resets the builder state for a new indexing operation.
     */
    fun reset() {
        _cancelled.set(false)
        _filesProcessed.set(0)
        _bytesProcessed.set(0)
        _totalFiles.set(0)
        _currentPhase.value = IndexingPhase.SCANNING
        _currentFile.value = null
        _index.clear()
    }

    /**
     * Builds an index for all text files in the given directory.
     *
     * @param rootDirectory The directory to index
     * @param charset The charset to use when reading files (default: UTF-8)
     * @return The result of the indexing operation
     */
    suspend fun buildIndex(
        rootDirectory: Path,
        charset: Charset = Charsets.UTF_8
    ): IndexingResult = withContext(Dispatchers.IO) {
        if (_isRunning.getAndSet(true)) {
            throw IllegalStateException("Index builder is already running")
        }

        reset()
        val startTime = System.currentTimeMillis()

        try {
            // Phase 1: Scan for files
            emitProgress()
            val files = scanForFiles(rootDirectory)

            if (_cancelled.get()) {
                return@withContext handleCancellation()
            }

            _totalFiles.set(files.size)
            _currentPhase.value = IndexingPhase.INDEXING
            emitProgress()

            if (files.isEmpty()) {
                _currentPhase.value = IndexingPhase.COMPLETED
                emitProgress()
                return@withContext IndexingResult.Success(0, 0, System.currentTimeMillis() - startTime)
            }

            // Phase 2: Index files in parallel
            val result = indexFilesParallel(files, charset)

            if (_cancelled.get()) {
                return@withContext handleCancellation()
            }

            _currentPhase.value = IndexingPhase.COMPLETED
            emitProgress()

            result ?: IndexingResult.Success(
                filesIndexed = _filesProcessed.get(),
                totalBytes = _bytesProcessed.get(),
                durationMs = System.currentTimeMillis() - startTime
            )

        } catch (e: CancellationException) {
            handleCancellation()
        } catch (e: Exception) {
            _currentPhase.value = IndexingPhase.FAILED
            emitProgress()
            IndexingResult.Failed(e, _filesProcessed.get())
        } finally {
            _isRunning.set(false)
        }
    }

    private fun handleCancellation(): IndexingResult.Cancelled {
        val filesProcessed = _filesProcessed.get()
        _index.clear() // Clear partial index on cancellation
        _currentPhase.value = IndexingPhase.CANCELLED
        runBlocking { emitProgress() }
        return IndexingResult.Cancelled(filesProcessed)
    }

    private suspend fun scanForFiles(rootDirectory: Path): List<Path> {
        val files = mutableListOf<Path>()

        if (!Files.exists(rootDirectory)) {
            throw NoSuchFileException(rootDirectory.toString(), null, "Directory does not exist")
        }

        if (!Files.isDirectory(rootDirectory)) {
            throw NotDirectoryException(rootDirectory.toString())
        }

        val visitOption = if (config.followSymlinks) {
            setOf(FileVisitOption.FOLLOW_LINKS)
        } else {
            emptySet()
        }

        Files.walkFileTree(rootDirectory, visitOption, Int.MAX_VALUE, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (_cancelled.get()) {
                    return FileVisitResult.TERMINATE
                }

                if (shouldIndexFile(file, attrs, rootDirectory)) {
                    files.add(file)
                }
                return FileVisitResult.CONTINUE
            }

            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (_cancelled.get()) {
                    return FileVisitResult.TERMINATE
                }

                val relativePath = rootDirectory.relativize(dir).toString()
                if (shouldExcludeDirectory(relativePath)) {
                    return FileVisitResult.SKIP_SUBTREE
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult {
                // Skip files we can't read
                return FileVisitResult.CONTINUE
            }
        })

        return files
    }

    private fun shouldIndexFile(file: Path, attrs: BasicFileAttributes, rootDirectory: Path): Boolean {
        // Skip if too large
        if (attrs.size() > config.maxFileSizeBytes) {
            return false
        }

        // Check file extension filter
        val fileName = file.fileName?.toString() ?: return false
        if (config.fileExtensions != null) {
            val extension = fileName.substringAfterLast('.', "")
            if (extension !in config.fileExtensions) {
                return false
            }
        }

        // Check exclusion patterns using relative path
        val relativePath = rootDirectory.relativize(file).toString()
        for (pattern in config.excludePatterns) {
            if (matchesGlob(relativePath, pattern)) {
                return false
            }
        }

        return true
    }

    private fun shouldExcludeDirectory(relativePath: String): Boolean {
        if (relativePath.isEmpty()) return false

        for (pattern in config.excludePatterns) {
            // Check for directory-specific patterns
            val dirPattern = pattern.removeSuffix("/**")
            if (relativePath == dirPattern || relativePath.startsWith("$dirPattern/")) {
                return true
            }
        }
        return false
    }

    private fun matchesGlob(relativePath: String, pattern: String): Boolean {
        // Handle common glob patterns:
        // - *.ext matches files with that extension
        // - dir/** matches all files under dir
        // - .git/** matches all files under .git

        return when {
            // Extension pattern like *.class, *.jar
            pattern.startsWith("*.") -> {
                val extension = pattern.substring(2) // Remove "*."
                relativePath.endsWith(".$extension")
            }
            // Directory pattern like build/**, .git/**
            pattern.endsWith("/**") -> {
                val dir = pattern.removeSuffix("/**")
                relativePath.startsWith("$dir/") || relativePath == dir
            }
            // Exact match
            else -> relativePath == pattern
        }
    }

    private suspend fun indexFilesParallel(
        files: List<Path>,
        charset: Charset
    ): IndexingResult? = coroutineScope {
        val semaphore = Semaphore(config.parallelism)
        val errorChannel = Channel<Throwable>(Channel.BUFFERED)
        var fatalError: Throwable? = null

        val jobs = files.map { file ->
            launch {
                if (_cancelled.get()) return@launch

                semaphore.acquire()
                try {
                    if (_cancelled.get()) return@launch

                    _currentFile.value = file
                    val indexed = indexFile(file, charset)
                    if (indexed) {
                        _filesProcessed.incrementAndGet()
                    }
                    emitProgress()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Log but continue - individual file errors shouldn't stop indexing
                    // In a real system, we'd want proper logging here
                } finally {
                    semaphore.release()
                }
            }
        }

        // Wait for all jobs to complete
        jobs.forEach { it.join() }

        // Check for fatal errors
        errorChannel.close()
        for (error in errorChannel) {
            fatalError = error
            break
        }

        fatalError?.let {
            IndexingResult.Failed(it, _filesProcessed.get())
        }
    }

    /**
     * Indexes a single file.
     * @return true if the file was successfully indexed, false if skipped
     */
    private fun indexFile(file: Path, charset: Charset): Boolean {
        return try {
            val content = Files.readString(file, charset)
            val attrs = Files.readAttributes(file, BasicFileAttributes::class.java)

            _index.addFile(
                path = file,
                content = content,
                sizeBytes = attrs.size(),
                lastModified = attrs.lastModifiedTime().toMillis()
            )

            _bytesProcessed.addAndGet(attrs.size())
            true
        } catch (e: MalformedInputException) {
            // Skip binary files that can't be read as text
            false
        } catch (e: Exception) {
            // Skip files that can't be read
            false
        }
    }

    private suspend fun emitProgress() {
        val progress = IndexingProgress(
            filesProcessed = _filesProcessed.get(),
            totalFiles = _totalFiles.get(),
            currentFile = _currentFile.value,
            bytesProcessed = _bytesProcessed.get(),
            phase = _currentPhase.value
        )
        _progressFlow.emit(progress)
    }
}
