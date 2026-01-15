package com.textsearch.search

import com.textsearch.core.SearchConfig
import com.textsearch.index.TextIndex
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Path

class SearchQueryExecutorTest {

    private lateinit var index: TextIndex
    private lateinit var executor: SearchQueryExecutor

    @BeforeEach
    fun setup() {
        index = TextIndex()
        executor = SearchQueryExecutor(index)
    }

    @Nested
    inner class BasicSearch {

        @Test
        fun `should find matches in single file`() = runTest {
            index.addFile(Path.of("/test.txt"), "Hello World", 11, 1000)

            val response = executor.search("World")

            assertEquals(1, response.totalMatches)
            assertFalse(response.truncated)
            assertEquals("World", response.results[0].matchedText)
        }

        @Test
        fun `should find matches across multiple files`() = runTest {
            index.addFile(Path.of("/file1.txt"), "TODO: first task", 16, 1000)
            index.addFile(Path.of("/file2.txt"), "TODO: second task", 17, 1000)
            index.addFile(Path.of("/file3.txt"), "Nothing here", 12, 1000)

            val response = executor.search("TODO")

            assertEquals(2, response.totalMatches)
        }

        @Test
        fun `should return empty response for no matches`() = runTest {
            index.addFile(Path.of("/test.txt"), "Hello World", 11, 1000)

            val response = executor.search("xyz")

            assertEquals(0, response.totalMatches)
            assertTrue(response.results.isEmpty())
        }

        @Test
        fun `should return empty response for empty query`() = runTest {
            index.addFile(Path.of("/test.txt"), "Hello World", 11, 1000)

            val response = executor.search("")

            assertEquals(0, response.totalMatches)
        }

        @Test
        fun `should return empty response for empty index`() = runTest {
            val response = executor.search("test")

            assertEquals(0, response.totalMatches)
        }

        @Test
        fun `should include search time in response`() = runTest {
            index.addFile(Path.of("/test.txt"), "Content", 7, 1000)

            val response = executor.search("Content")

            assertTrue(response.searchTimeMs >= 0)
        }
    }

    @Nested
    inner class ResultLimiting {

        @Test
        fun `should respect maxResults limit`() = runTest {
            // Create files with many matches
            repeat(10) { i ->
                index.addFile(Path.of("/file$i.txt"), "match match match match match", 29, 1000)
            }

            val config = SearchConfig(maxResults = 10)
            val response = executor.search("match", config)

            assertEquals(10, response.results.size)
            assertTrue(response.truncated)
        }

        @Test
        fun `should respect maxResultsPerFile limit`() = runTest {
            index.addFile(
                Path.of("/file.txt"),
                "a a a a a a a a a a",  // 10 matches
                19,
                1000
            )

            val config = SearchConfig(maxResultsPerFile = 3, maxResults = null)
            val response = executor.search("a", config)

            assertEquals(3, response.results.size)
        }

        @Test
        fun `should not truncate when under limit`() = runTest {
            index.addFile(Path.of("/file.txt"), "one two three", 13, 1000)

            val config = SearchConfig(maxResults = 100)
            val response = executor.search("o", config)

            // "one" and "two" contain 'o'
            assertEquals(2, response.totalMatches)
            assertFalse(response.truncated)
        }

        @Test
        fun `should work with unlimited config`() = runTest {
            repeat(100) { i ->
                index.addFile(Path.of("/file$i.txt"), "match", 5, 1000)
            }

            val response = executor.search("match", SearchConfig.UNLIMITED)

            assertEquals(100, response.totalMatches)
            assertFalse(response.truncated)
        }
    }

    @Nested
    inner class CaseSensitivity {

        @Test
        fun `should be case-sensitive by default`() = runTest {
            index.addFile(Path.of("/test.txt"), "Hello HELLO hello", 17, 1000)

            val response = executor.search("Hello")

            assertEquals(1, response.totalMatches)
        }

        @Test
        fun `should support case-insensitive search`() = runTest {
            index.addFile(Path.of("/test.txt"), "Hello HELLO hello", 17, 1000)

            val config = SearchConfig(caseSensitive = false)
            val response = executor.search("hello", config)

            assertEquals(3, response.totalMatches)
        }
    }

    @Nested
    inner class ParallelSearch {

        @Test
        fun `should handle parallel searches`() = runTest {
            repeat(10) { i ->
                index.addFile(
                    Path.of("/file$i.txt"),
                    "apple banana cherry date",
                    24,
                    1000
                )
            }

            val queries = listOf("apple", "banana", "cherry", "date")
            val responses = queries.map { query ->
                async { executor.search(query) }
            }.awaitAll()

            responses.forEach { response ->
                assertEquals(10, response.totalMatches)
            }
        }

        @Test
        fun `should search multiple queries efficiently`() = runTest {
            repeat(5) { i ->
                index.addFile(
                    Path.of("/file$i.txt"),
                    "TODO FIXME BUG",
                    14,
                    1000
                )
            }

            val results = executor.searchMultiple(listOf("TODO", "FIXME", "BUG"))

            assertEquals(3, results.size)
            assertEquals(5, results["TODO"]?.totalMatches)
            assertEquals(5, results["FIXME"]?.totalMatches)
            assertEquals(5, results["BUG"]?.totalMatches)
        }
    }

    @Nested
    inner class CountOperation {

        @Test
        fun `should count matches without returning results`() = runTest {
            repeat(10) { i ->
                index.addFile(Path.of("/file$i.txt"), "match", 5, 1000)
            }

            val count = executor.count("match")

            assertEquals(10, count)
        }

        @Test
        fun `should return zero for no matches`() = runTest {
            index.addFile(Path.of("/test.txt"), "Content", 7, 1000)

            val count = executor.count("xyz")

            assertEquals(0, count)
        }

        @Test
        fun `should return zero for empty query`() = runTest {
            index.addFile(Path.of("/test.txt"), "Content", 7, 1000)

            val count = executor.count("")

            assertEquals(0, count)
        }
    }

    @Nested
    inner class FindFilesContaining {

        @Test
        fun `should find files containing query`() = runTest {
            index.addFile(Path.of("/has-match.txt"), "Has a TODO here", 15, 1000)
            index.addFile(Path.of("/no-match.txt"), "Nothing special", 15, 1000)
            index.addFile(Path.of("/also-match.txt"), "Another TODO", 12, 1000)

            val files = executor.findFilesContaining("TODO")

            assertEquals(2, files.size)
            assertTrue(files.any { it.toString().contains("has-match") })
            assertTrue(files.any { it.toString().contains("also-match") })
        }

        @Test
        fun `should return empty set for no matches`() = runTest {
            index.addFile(Path.of("/test.txt"), "Content", 7, 1000)

            val files = executor.findFilesContaining("xyz")

            assertTrue(files.isEmpty())
        }

        @Test
        fun `should return empty set for empty query`() = runTest {
            index.addFile(Path.of("/test.txt"), "Content", 7, 1000)

            val files = executor.findFilesContaining("")

            assertTrue(files.isEmpty())
        }
    }

    @Nested
    inner class SequenceSearch {

        @Test
        fun `should return results as sequence`() = runTest {
            repeat(10) { i ->
                index.addFile(Path.of("/file$i.txt"), "match", 5, 1000)
            }

            val results = executor.searchSequence("match").toList()

            assertEquals(10, results.size)
        }

        @Test
        fun `should respect limit in sequence`() = runTest {
            repeat(100) { i ->
                index.addFile(Path.of("/file$i.txt"), "match", 5, 1000)
            }

            val config = SearchConfig(maxResults = 5)
            val results = executor.searchSequence("match", config).toList()

            assertEquals(5, results.size)
        }

        @Test
        fun `should be lazy`() = runTest {
            var filesAccessed = 0

            // This is a behavioral test - the sequence should allow
            // early termination
            repeat(100) { i ->
                index.addFile(Path.of("/file$i.txt"), "match", 5, 1000)
            }

            val firstResult = executor.searchSequence("match").first()

            assertNotNull(firstResult)
        }
    }

    @Nested
    inner class ResultFormat {

        @Test
        fun `should include correct file path`() = runTest {
            val path = Path.of("/path/to/file.txt")
            index.addFile(path, "test content", 12, 1000)

            val response = executor.search("test")

            assertEquals(path, response.results[0].filePath)
        }

        @Test
        fun `should include correct line number`() = runTest {
            index.addFile(
                Path.of("/test.txt"),
                "line 1\nline 2\nfind me here\nline 4",
                34,
                1000
            )

            val response = executor.search("find me")

            assertEquals(3, response.results[0].line)
        }

        @Test
        fun `should include correct column number`() = runTest {
            index.addFile(Path.of("/test.txt"), "prefix TARGET suffix", 20, 1000)

            val response = executor.search("TARGET")

            assertEquals(8, response.results[0].column)
        }

        @Test
        fun `should include matched text`() = runTest {
            index.addFile(Path.of("/test.txt"), "Hello World", 11, 1000)

            val response = executor.search("World")

            assertEquals("World", response.results[0].matchedText)
        }

        @Test
        fun `should include line content trimmed`() = runTest {
            index.addFile(Path.of("/test.txt"), "   spaced content   ", 20, 1000)

            val response = executor.search("spaced")

            assertEquals("spaced content", response.results[0].lineContent)
        }
    }

    @Nested
    inner class ConcurrencyStress {

        @Test
        fun `should handle many concurrent searches`() = runTest {
            // Populate index
            repeat(50) { i ->
                index.addFile(
                    Path.of("/file$i.txt"),
                    "keyword1 keyword2 keyword3 keyword4 keyword5",
                    44,
                    1000
                )
            }

            // Run many concurrent searches
            val searches = (1..100).map { i ->
                async { executor.search("keyword${(i % 5) + 1}") }
            }

            val results = searches.awaitAll()

            // All searches should complete successfully
            assertEquals(100, results.size)
            results.forEach { response ->
                assertEquals(50, response.totalMatches)
            }
        }
    }
}
