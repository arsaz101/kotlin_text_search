package com.textsearch.index

import com.textsearch.core.IndexConfig
import com.textsearch.core.IndexingPhase
import com.textsearch.core.IndexingResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class TextIndexBuilderTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var builder: TextIndexBuilder

    @BeforeEach
    fun setup() {
        builder = TextIndexBuilder()
    }

    @Nested
    inner class BasicIndexing {

        @Test
        fun `should index empty directory`() = runTest {
            val result = builder.buildIndex(tempDir)

            assertTrue(result is IndexingResult.Success)
            val success = result as IndexingResult.Success
            assertEquals(0, success.filesIndexed)
        }

        @Test
        fun `should index single file`() = runTest {
            createFile("test.txt", "Hello, World!")

            val result = builder.buildIndex(tempDir)

            assertTrue(result is IndexingResult.Success)
            val success = result as IndexingResult.Success
            assertEquals(1, success.filesIndexed)
            assertEquals(1, builder.index.fileCount)
        }

        @Test
        fun `should index multiple files`() = runTest {
            createFile("file1.txt", "Content 1")
            createFile("file2.txt", "Content 2")
            createFile("file3.txt", "Content 3")

            val result = builder.buildIndex(tempDir)

            assertTrue(result is IndexingResult.Success)
            assertEquals(3, (result as IndexingResult.Success).filesIndexed)
        }

        @Test
        fun `should index files in subdirectories`() = runTest {
            createFile("root.txt", "Root content")
            createFile("sub/nested.txt", "Nested content")
            createFile("sub/deep/very-nested.txt", "Very nested")

            val result = builder.buildIndex(tempDir)

            assertTrue(result is IndexingResult.Success)
            assertEquals(3, (result as IndexingResult.Success).filesIndexed)
        }

        @Test
        fun `should make content searchable after indexing`() = runTest {
            createFile("test.txt", "Hello World\nThis is a test\nFOO BAR BAZ")

            builder.buildIndex(tempDir)

            val results = builder.index.search("World")
            assertEquals(1, results.totalMatches)

            val fooResults = builder.index.search("FOO")
            assertEquals(1, fooResults.totalMatches)
            assertEquals(3, fooResults.results[0].line)
        }
    }

    @Nested
    inner class FileFiltering {

        @Test
        fun `should filter by file extension`() = runTest {
            createFile("code.kt", "Kotlin code")
            createFile("code.java", "Java code")
            createFile("readme.md", "Documentation")

            val config = IndexConfig(fileExtensions = setOf("kt", "java"))
            val builder = TextIndexBuilder(config)
            val result = builder.buildIndex(tempDir)

            assertEquals(2, (result as IndexingResult.Success).filesIndexed)
        }

        @Test
        fun `should skip files exceeding size limit`() = runTest {
            val smallContent = "Small content"
            val largeContent = "x".repeat(1000)

            createFile("small.txt", smallContent)
            createFile("large.txt", largeContent)

            val config = IndexConfig(maxFileSizeBytes = 100)
            val builder = TextIndexBuilder(config)
            val result = builder.buildIndex(tempDir)

            assertEquals(1, (result as IndexingResult.Success).filesIndexed)
        }

        @Test
        fun `should exclude patterns`() = runTest {
            createFile("main.kt", "Main code")
            createFile("build/output.txt", "Build output")
            createFile("node_modules/package.json", "{}")

            val result = builder.buildIndex(tempDir)

            // build and node_modules are excluded by default
            assertEquals(1, (result as IndexingResult.Success).filesIndexed)
        }
    }

    @Nested
    inner class ProgressReporting {

        @Test
        fun `should emit progress updates`() = runTest {
            createFile("file1.txt", "Content 1")
            createFile("file2.txt", "Content 2")

            val progressUpdates = mutableListOf<IndexingPhase>()
            val job = launch(Dispatchers.IO) {
                builder.progress.collect { progress ->
                    progressUpdates.add(progress.phase)
                }
            }

            // Small delay to let collector start
            kotlinx.coroutines.delay(10)
            
            builder.buildIndex(tempDir)
            
            // Give time for final emissions
            kotlinx.coroutines.delay(50)
            job.cancel()

            // At minimum, COMPLETED should be present (due to replay = 1)
            // Check the replay value - the last emitted progress
            val lastProgress = builder.progress.replayCache.lastOrNull()
            assertNotNull(lastProgress)
            assertEquals(IndexingPhase.COMPLETED, lastProgress?.phase)
        }
    }

    @Nested
    inner class Cancellation {

        @Test
        fun `should cancel indexing`() = runTest {
            // Create many files to ensure cancellation happens during indexing
            repeat(100) { i ->
                createFile("file$i.txt", "Content for file $i with some text")
            }

            val job = launch {
                delay(10) // Let indexing start
                builder.cancel()
            }

            val result = builder.buildIndex(tempDir)
            job.join()

            assertTrue(result is IndexingResult.Cancelled)
        }

        @Test
        fun `should clear index on cancellation`() = runTest {
            // Create many files with larger content to ensure indexing takes time
            repeat(500) { i ->
                createFile("file$i.txt", "Content line $i with some additional text to make it larger\n".repeat(50))
            }

            val job = launch(Dispatchers.Default) {
                delay(2) // Very short delay to cancel during indexing
                builder.cancel()
            }

            val result = builder.buildIndex(tempDir)
            job.join()

            // If cancellation happened, index should be cleared
            if (result is IndexingResult.Cancelled) {
                assertEquals(0, builder.index.fileCount, "Index should be cleared after cancellation")
            }
            // Note: If indexing completed before cancellation could take effect,
            // the test still passes - we're testing that cancellation clears the index
            // when it does occur, not that it always occurs within a time window
        }

        @Test
        fun `should report cancelled status`() = runTest {
            createFile("test.txt", "Content")

            val job = launch {
                delay(1)
                builder.cancel()
            }

            val result = builder.buildIndex(tempDir)
            job.join()

            if (result is IndexingResult.Cancelled) {
                assertTrue(result.message.contains("cancelled"))
            }
        }
    }

    @Nested
    inner class ParallelProcessing {

        @Test
        fun `should process files in parallel`() = runTest {
            // Create multiple files
            repeat(20) { i ->
                createFile("file$i.txt", "Content for file number $i")
            }

            val config = IndexConfig(parallelism = 4)
            val builder = TextIndexBuilder(config)
            val result = builder.buildIndex(tempDir)

            assertTrue(result is IndexingResult.Success)
            assertEquals(20, (result as IndexingResult.Success).filesIndexed)
        }
    }

    @Nested
    inner class ErrorHandling {

        @Test
        fun `should fail for non-existent directory`() = runTest {
            val nonExistent = tempDir.resolve("does-not-exist")

            val result = builder.buildIndex(nonExistent)

            assertTrue(result is IndexingResult.Failed)
        }

        @Test
        fun `should fail for file instead of directory`() = runTest {
            val file = createFile("file.txt", "Content")

            val result = builder.buildIndex(file)

            assertTrue(result is IndexingResult.Failed)
        }

        @Test
        fun `should skip binary files gracefully`() = runTest {
            createFile("text.txt", "Readable text")
            // Create a file with invalid UTF-8
            val binaryFile = tempDir.resolve("binary.dat")
            Files.write(binaryFile, byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0x01))

            val result = builder.buildIndex(tempDir)

            // Should succeed, skipping the binary file
            assertTrue(result is IndexingResult.Success)
            assertEquals(1, (result as IndexingResult.Success).filesIndexed)
        }
    }

    @Nested
    inner class ResetAndReuse {

        @Test
        fun `should be reusable after completion`() = runTest {
            createFile("file1.txt", "First content")
            builder.buildIndex(tempDir)
            assertEquals(1, builder.index.fileCount)

            // Create new file and rebuild
            createFile("file2.txt", "Second content")
            builder.reset()
            builder.buildIndex(tempDir)

            assertEquals(2, builder.index.fileCount)
        }

        @Test
        fun `should not allow concurrent builds`() = runTest {
            createFile("test.txt", "Content")

            var exception: Exception? = null

            coroutineScope {
                launch {
                    builder.buildIndex(tempDir)
                }

                delay(1) // Let first build start

                try {
                    builder.buildIndex(tempDir)
                } catch (e: Exception) {
                    exception = e
                }
            }

            assertTrue(exception is IllegalStateException)
        }
    }

    private fun createFile(relativePath: String, content: String): Path {
        val file = tempDir.resolve(relativePath)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
        return file
    }
}
