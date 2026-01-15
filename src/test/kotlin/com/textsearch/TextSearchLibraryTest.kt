package com.textsearch

import com.textsearch.core.IndexConfig
import com.textsearch.core.IndexingResult
import com.textsearch.core.SearchConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class TextSearchLibraryTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var library: TextSearchLibrary

    @BeforeEach
    fun setup() {
        library = TextSearchLibrary()
    }

    @Nested
    inner class Integration {

        @Test
        fun `should index and search workflow`() = runTest {
            // Setup
            createFile("hello.txt", "Hello World")
            createFile("greeting.txt", "Hi there, Hello friend")
            createFile("other.txt", "Nothing relevant here")

            // Index
            val result = library.buildIndex(tempDir)
            assertTrue(result is IndexingResult.Success)
            assertEquals(3, library.indexedFileCount)

            // Search
            val response = library.search("Hello")
            assertEquals(2, response.totalMatches)
        }

        @Test
        fun `should support multiple search operations`() = runTest {
            createFile("code.kt", """
                fun main() {
                    // TODO: implement this
                    println("Hello World")
                    // FIXME: bug here
                }
            """.trimIndent())

            library.buildIndex(tempDir)

            val todoResults = library.search("TODO")
            val fixmeResults = library.search("FIXME")
            val funResults = library.search("fun")

            assertEquals(1, todoResults.totalMatches)
            assertEquals(1, fixmeResults.totalMatches)
            assertEquals(1, funResults.totalMatches)
        }

        @Test
        fun `should handle special characters in search`() = runTest {
            createFile("test.txt", "function() { return x + y; }")

            library.buildIndex(tempDir)

            val response = library.search("function()")
            assertEquals(1, response.totalMatches)
        }

        @Test
        fun `should search multiple queries in parallel`() = runTest {
            createFile("mixed.txt", "apple banana cherry")

            library.buildIndex(tempDir)

            val results = library.searchMultiple(listOf("apple", "banana", "cherry"))

            assertEquals(3, results.size)
            results.values.forEach { response ->
                assertEquals(1, response.totalMatches)
            }
        }

        @Test
        fun `should count matches`() = runTest {
            createFile("repeated.txt", "word word word word word")

            library.buildIndex(tempDir)

            val count = library.count("word")
            assertEquals(5, count)
        }

        @Test
        fun `should find files containing query`() = runTest {
            createFile("has-keyword.txt", "This file has the keyword")
            createFile("no-keyword.txt", "This file does not")

            library.buildIndex(tempDir)

            val files = library.findFilesContaining("keyword")
            assertEquals(1, files.size)
            assertTrue(files.first().toString().contains("has-keyword"))
        }
    }

    @Nested
    inner class Configuration {

        @Test
        fun `should use custom index config`() = runTest {
            createFile("code.kt", "Kotlin code")
            createFile("readme.md", "Documentation")

            val library = TextSearchLibrary(
                indexConfig = IndexConfig(fileExtensions = setOf("kt"))
            )
            library.buildIndex(tempDir)

            assertEquals(1, library.indexedFileCount)
        }

        @Test
        fun `should use custom search config`() = runTest {
            createFile("many-matches.txt", "x x x x x x x x x x")

            val library = TextSearchLibrary(
                searchConfig = SearchConfig(maxResults = 3)
            )
            library.buildIndex(tempDir)

            val response = library.search("x")
            assertEquals(3, response.results.size)
            assertTrue(response.truncated)
        }

        @Test
        fun `should allow overriding search config per search`() = runTest {
            createFile("test.txt", "x x x x x")

            library.buildIndex(tempDir)

            val defaultResponse = library.search("x")
            val limitedResponse = library.search("x", SearchConfig(maxResults = 2))

            assertTrue(defaultResponse.results.size > limitedResponse.results.size)
            assertEquals(2, limitedResponse.results.size)
        }
    }

    @Nested
    inner class Cancellation {

        @Test
        fun `should cancel indexing`() = runTest {
            // Create many files
            repeat(100) { i ->
                createFile("file$i.txt", "Content for file $i with some padding text")
            }

            val job = launch {
                delay(5)
                library.cancelIndexing()
            }

            val result = library.buildIndex(tempDir)
            job.join()

            assertTrue(result is IndexingResult.Cancelled)
        }
    }

    @Nested
    inner class StateManagement {

        @Test
        fun `should track indexed files`() = runTest {
            createFile("file1.txt", "Content")
            createFile("file2.txt", "Content")

            library.buildIndex(tempDir)

            assertEquals(2, library.indexedFiles.size)
            assertTrue(library.indexedFiles.any { it.fileName.toString() == "file1.txt" })
        }

        @Test
        fun `should track indexed bytes`() = runTest {
            val content1 = "Hello"
            val content2 = "World!"
            createFile("file1.txt", content1)
            createFile("file2.txt", content2)

            library.buildIndex(tempDir)

            assertEquals(11L, library.indexedBytes) // 5 + 6
        }

        @Test
        fun `should clear all state`() = runTest {
            createFile("test.txt", "Content")
            library.buildIndex(tempDir)

            library.clear()

            assertEquals(0, library.indexedFileCount)
            assertEquals(0, library.indexedBytes)
        }

        @Test
        fun `should report indexing status`() = runTest {
            assertFalse(library.isIndexing)

            createFile("test.txt", "Content")
            library.buildIndex(tempDir)

            assertFalse(library.isIndexing)
        }
    }

    @Nested
    inner class EdgeCases {

        @Test
        fun `should handle empty directory`() = runTest {
            val result = library.buildIndex(tempDir)

            assertTrue(result is IndexingResult.Success)
            assertEquals(0, library.indexedFileCount)

            val response = library.search("anything")
            assertEquals(0, response.totalMatches)
        }

        @Test
        fun `should handle empty files`() = runTest {
            createFile("empty.txt", "")

            library.buildIndex(tempDir)

            assertEquals(1, library.indexedFileCount)
            val response = library.search("test")
            assertEquals(0, response.totalMatches)
        }

        @Test
        fun `should handle files with only whitespace`() = runTest {
            createFile("whitespace.txt", "   \n\t\n   ")

            library.buildIndex(tempDir)

            val response = library.search("test")
            assertEquals(0, response.totalMatches)
        }

        @Test
        fun `should handle unicode content`() = runTest {
            createFile("unicode.txt", "Hello 世界 مرحبا 🌍")

            library.buildIndex(tempDir)

            assertEquals(1, library.search("世界").totalMatches)
            assertEquals(1, library.search("مرحبا").totalMatches)
            assertEquals(1, library.search("🌍").totalMatches)
        }

        @Test
        fun `should handle very long lines`() = runTest {
            val longLine = "x".repeat(10_000)
            createFile("long-line.txt", longLine)

            library.buildIndex(tempDir)

            val response = library.search("xxx")
            assertTrue(response.totalMatches > 0)
        }

        @Test
        fun `should handle many small files`() = runTest {
            repeat(100) { i ->
                createFile("file$i.txt", "Content $i")
            }

            val result = library.buildIndex(tempDir)

            assertTrue(result is IndexingResult.Success)
            assertEquals(100, library.indexedFileCount)
        }

        @Test
        fun `should handle deep directory nesting`() = runTest {
            val deepPath = "a/b/c/d/e/f/g/h/i/j/k/l/deep.txt"
            createFile(deepPath, "Deep content")

            library.buildIndex(tempDir)

            val response = library.search("Deep")
            assertEquals(1, response.totalMatches)
        }
    }

    @Nested
    inner class SearchResults {

        @Test
        fun `should return results in file-line-column format`() = runTest {
            createFile("test.txt", "first line\nsecond TARGET line\nthird line")

            library.buildIndex(tempDir)

            val response = library.search("TARGET")
            assertEquals(1, response.results.size)

            val result = response.results[0]
            assertTrue(result.filePath.toString().contains("test.txt"))
            assertEquals(2, result.line)
            assertEquals(8, result.column)
        }

        @Test
        fun `should include line content for context`() = runTest {
            createFile("test.txt", "The quick brown fox")

            library.buildIndex(tempDir)

            val response = library.search("quick")
            assertEquals("The quick brown fox", response.results[0].lineContent)
        }

        @Test
        fun `should handle multiple matches per line`() = runTest {
            createFile("test.txt", "aa bb aa cc aa")

            library.buildIndex(tempDir)

            val response = library.search("aa")
            assertEquals(3, response.totalMatches)

            val columns = response.results.map { it.column }
            assertEquals(listOf(1, 7, 13), columns)
        }
    }

    private fun createFile(relativePath: String, content: String): Path {
        val file = tempDir.resolve(relativePath)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
        return file
    }
}
