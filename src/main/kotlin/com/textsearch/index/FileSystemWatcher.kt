package com.textsearch.index

import com.textsearch.core.IndexConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.nio.charset.Charset
import java.nio.charset.MalformedInputException
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap

/**
 * Event types for file system changes.
 */
sealed class FileSystemEvent {
    abstract val path: Path

    data class Created(override val path: Path) : FileSystemEvent()
    data class Modified(override val path: Path) : FileSystemEvent()
    data class Deleted(override val path: Path) : FileSystemEvent()
}

/**
 * Watches for file system changes and updates the index incrementally.
 *
 * This component monitors a directory for file changes and automatically
 * updates the associated [TextIndex] when files are created, modified, or deleted.
 *
 * ## Features
 *
 * - Watches for file creation, modification, and deletion
 * - Recursively monitors subdirectories
 * - Respects the same file filters as [TextIndexBuilder]
 * - Emits events for external observers
 * - Debounces rapid file changes
 *
 * ## Usage Example
 *
 * ```kotlin
 * val index = TextIndex()
 * val watcher = FileSystemWatcher(index, IndexConfig.DEFAULT)
 *
 * // Start watching
 * val job = launch {
 *     watcher.events.collect { event ->
 *         println("File changed: $event")
 *     }
 * }
 *
 * watcher.startWatching(Path.of("/path/to/directory"))
 *
 * // Later...
 * watcher.stopWatching()
 * ```
 *
 * ## Limitations
 *
 * - Uses Java's WatchService which has platform-specific behaviors
 * - On macOS, changes may be delayed due to the polling-based implementation
 * - Very rapid changes to the same file may be coalesced
 */
class FileSystemWatcher(
    private val index: TextIndex,
    private val config: IndexConfig = IndexConfig.DEFAULT,
    private val charset: Charset = Charsets.UTF_8,
    private val debounceMs: Long = 100
) {
    private var watchJob: Job? = null
    private var watchService: WatchService? = null
    private val watchKeys = ConcurrentHashMap<WatchKey, Path>()

    private val _events = MutableSharedFlow<FileSystemEvent>(extraBufferCapacity = 100)

    /**
     * Flow of file system events.
     */
    val events: SharedFlow<FileSystemEvent> = _events.asSharedFlow()

    /**
     * Whether the watcher is currently active.
     */
    val isWatching: Boolean
        get() = watchJob?.isActive == true

    /**
     * Starts watching the given directory for changes.
     *
     * @param rootDirectory The directory to watch
     * @throws IllegalStateException if already watching
     */
    suspend fun startWatching(rootDirectory: Path) {
        if (isWatching) {
            throw IllegalStateException("Already watching. Call stopWatching() first.")
        }

        watchService = FileSystems.getDefault().newWatchService()

        // Register the root directory and all subdirectories
        registerDirectory(rootDirectory)

        watchJob = CoroutineScope(Dispatchers.IO).launch {
            watchLoop(rootDirectory)
        }
    }

    /**
     * Stops watching for file system changes.
     */
    fun stopWatching() {
        watchJob?.cancel()
        watchJob = null
        watchKeys.clear()
        watchService?.close()
        watchService = null
    }

    private fun registerDirectory(directory: Path) {
        val service = watchService ?: return

        if (!Files.isDirectory(directory)) return

        // Check exclusion patterns
        val relativePath = directory.toString()
        for (pattern in config.excludePatterns) {
            val dirPattern = pattern.removeSuffix("/**")
            if (relativePath.endsWith(dirPattern)) {
                return
            }
        }

        try {
            val key = directory.register(
                service,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE
            )
            watchKeys[key] = directory

            // Register subdirectories
            Files.list(directory).use { stream ->
                stream.filter { Files.isDirectory(it) }
                    .forEach { registerDirectory(it) }
            }
        } catch (e: Exception) {
            // Skip directories we can't watch
        }
    }

    private suspend fun watchLoop(rootDirectory: Path) {
        val service = watchService ?: return
        val pendingChanges = ConcurrentHashMap<Path, FileSystemEvent>()

        while (currentCoroutineContext().isActive) {
            try {
                val key = service.take()
                val dir = watchKeys[key] ?: continue

                for (event in key.pollEvents()) {
                    val kind = event.kind()

                    if (kind == StandardWatchEventKinds.OVERFLOW) {
                        // Handle overflow by re-indexing? For now, skip
                        continue
                    }

                    @Suppress("UNCHECKED_CAST")
                    val ev = event as WatchEvent<Path>
                    val filename = ev.context()
                    val fullPath = dir.resolve(filename)

                    val fsEvent = when (kind) {
                        StandardWatchEventKinds.ENTRY_CREATE -> {
                            // If it's a directory, register it for watching
                            if (Files.isDirectory(fullPath)) {
                                registerDirectory(fullPath)
                                null
                            } else {
                                FileSystemEvent.Created(fullPath)
                            }
                        }
                        StandardWatchEventKinds.ENTRY_MODIFY -> FileSystemEvent.Modified(fullPath)
                        StandardWatchEventKinds.ENTRY_DELETE -> FileSystemEvent.Deleted(fullPath)
                        else -> null
                    }

                    fsEvent?.let { pendingChanges[fullPath] = it }
                }

                if (!key.reset()) {
                    watchKeys.remove(key)
                }

                // Process pending changes with debouncing
                delay(debounceMs)
                processPendingChanges(pendingChanges, rootDirectory)
                pendingChanges.clear()

            } catch (e: ClosedWatchServiceException) {
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Continue watching despite errors
            }
        }
    }

    private suspend fun processPendingChanges(
        changes: Map<Path, FileSystemEvent>,
        rootDirectory: Path
    ) {
        for ((path, event) in changes) {
            try {
                when (event) {
                    is FileSystemEvent.Created, is FileSystemEvent.Modified -> {
                        if (shouldIndexFile(path, rootDirectory)) {
                            updateFileInIndex(path)
                            _events.emit(event)
                        }
                    }
                    is FileSystemEvent.Deleted -> {
                        if (index.removeFile(path)) {
                            _events.emit(event)
                        }
                    }
                }
            } catch (e: Exception) {
                // Skip files that cause errors
            }
        }
    }

    private fun shouldIndexFile(file: Path, rootDirectory: Path): Boolean {
        if (!Files.isRegularFile(file)) return false

        try {
            val attrs = Files.readAttributes(file, BasicFileAttributes::class.java)
            if (attrs.size() > config.maxFileSizeBytes) return false
        } catch (e: Exception) {
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

        // Check exclusion patterns
        val relativePath = rootDirectory.relativize(file).toString()
        for (pattern in config.excludePatterns) {
            if (matchesGlob(relativePath, pattern)) {
                return false
            }
        }

        return true
    }

    private fun matchesGlob(path: String, pattern: String): Boolean {
        val regex = pattern
            .replace(".", "\\.")
            .replace("**", ".*")
            .replace("*", "[^/]*")
            .let { ".*$it".toRegex() }
        return regex.matches(path)
    }

    private fun updateFileInIndex(file: Path) {
        try {
            val content = Files.readString(file, charset)
            val attrs = Files.readAttributes(file, BasicFileAttributes::class.java)

            index.addFile(
                path = file,
                content = content,
                sizeBytes = attrs.size(),
                lastModified = attrs.lastModifiedTime().toMillis()
            )
        } catch (e: MalformedInputException) {
            // Skip binary files
        }
    }
}
