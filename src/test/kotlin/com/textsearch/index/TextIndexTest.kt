package com.textsearch.index

import com.textsearch.core.SearchConfig
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import java.nio.file.Path

class TextIndexTest {

    private lateinit var index: TextIndex

    @BeforeEach
    fun setup() {
        index = TextIndex()
    }

    @Nested
    inner class AddAndRetrieveFiles {

        @Test
        fun `should add file to index`() {
            val path = Path.of("/test/file.txt")
            val content = "Hello, World!"

            index.addFile(path, content, content.length.toLong(), System.currentTimeMillis())

            assertEquals(1, index.fileCount)
            assertTrue(index.containsFile(path))
        }

        @Test
        fun `should store file content as lines`() {
            val path = Path.of("/test/file.txt")
            val content = "Line 1\nLine 2\nLine 3"

            index.addFile(path, content, content.length.toLong(), System.currentTimeMillis())

            val file = index.getFile(path)
            assertNotNull(file)
            assertEquals(3, file!!.lines.size)
            assertEquals("Line 1", file.lines[0])
            assertEquals("Line 2", file.lines[1])
            assertEquals("Line 3", file.lines[2])
        }

        @Test
        fun `should update existing file`() {
            val path = Path.of("/test/file.txt")

            index.addFile(path, "Original content", 16, 1000)
            index.addFile(path, "Updated content", 15, 2000)

            assertEquals(1, index.fileCount)
            val file = index.getFile(path)
            assertEquals(listOf("Updated content"), file?.lines)
        }

        @Test
        fun `should remove file from index`() {
            val path = Path.of("/test/file.txt")
            index.addFile(path, "Content", 7, System.currentTimeMillis())

            val removed = index.removeFile(path)

            assertTrue(removed)
            assertEquals(0, index.fileCount)
            assertFalse(index.containsFile(path))
        }

        @Test
        fun `should return false when removing non-existent file`() {
            val removed = index.removeFile(Path.of("/nonexistent"))
            assertFalse(removed)
        }

        @Test
        fun `should clear all files`() {
            index.addFile(Path.of("/file1.txt"), "Content 1", 9, 1000)
            index.addFile(Path.of("/file2.txt"), "Content 2", 9, 1000)

            index.clear()

            assertEquals(0, index.fileCount)
        }

        @Test
        fun `should track total bytes`() {
            index.addFile(Path.of("/file1.txt"), "Content", 100, 1000)
            index.addFile(Path.of("/file2.txt"), "Content", 200, 1000)

            assertEquals(300, index.totalBytes)
        }
    }

    @Nested
    inner class SearchInFile {

        @Test
        fun `should find single occurrence`() {
            val path = Path.of("/test/file.txt")
            index.addFile(path, "Hello World", 11, 1000)

            val results = index.searchInFile(path, "World")

            assertEquals(1, results.size)
            assertEquals(path, results[0].filePath)
            assertEquals(1, results[0].line)
            assertEquals(7, results[0].column)
            assertEquals("World", results[0].matchedText)
        }

        @Test
        fun `should find multiple occurrences on same line`() {
            val path = Path.of("/test/file.txt")
            index.addFile(path, "foo bar foo baz foo", 19, 1000)

            val results = index.searchInFile(path, "foo")

            assertEquals(3, results.size)
            assertEquals(1, results[0].column)
            assertEquals(9, results[1].column)
            assertEquals(17, results[2].column)
        }

        @Test
        fun `should find occurrences on multiple lines`() {
            val path = Path.of("/test/file.txt")
            index.addFile(path, "Line with TODO\nAnother line\nMore TODO here", 43, 1000)

            val results = index.searchInFile(path, "TODO")

            assertEquals(2, results.size)
            assertEquals(1, results[0].line)
            assertEquals(11, results[0].column)
            assertEquals(3, results[1].line)
            assertEquals(6, results[1].column)
        }

        @Test
        fun `should return empty list for no matches`() {
            val path = Path.of("/test/file.txt")
            index.addFile(path, "Hello World", 11, 1000)

            val results = index.searchInFile(path, "xyz")

            assertTrue(results.isEmpty())
        }

        @Test
        fun `should return empty list for non-existent file`() {
            val results = index.searchInFile(Path.of("/nonexistent"), "test")
            assertTrue(results.isEmpty())
        }

        @Test
        fun `should respect maxResultsPerFile limit`() {
            val path = Path.of("/test/file.txt")
            index.addFile(path, "a a a a a a a a a a", 19, 1000)

            val config = SearchConfig(maxResultsPerFile = 3)
            val results = index.searchInFile(path, "a", config)

            assertEquals(3, results.size)
        }

        @Test
        fun `should perform case-insensitive search when configured`() {
            val path = Path.of("/test/file.txt")
            index.addFile(path, "Hello HELLO hello", 17, 1000)

            val config = SearchConfig(caseSensitive = false)
            val results = index.searchInFile(path, "hello", config)

            assertEquals(3, results.size)
        }

        @Test
        fun `should perform case-sensitive search by default`() {
            val path = Path.of("/test/file.txt")
            index.addFile(path, "Hello HELLO hello", 17, 1000)

            val results = index.searchInFile(path, "hello")

            assertEquals(1, results.size)
            assertEquals(13, results[0].column)
        }

        @Test
        fun `should preserve matched text case in case-insensitive search`() {
            val path = Path.of("/test/file.txt")
            index.addFile(path, "Hello HELLO hello", 17, 1000)

            val config = SearchConfig(caseSensitive = false)
            val results = index.searchInFile(path, "HELLO", config)

            assertEquals("Hello", results[0].matchedText)
            assertEquals("HELLO", results[1].matchedText)
            assertEquals("hello", results[2].matchedText)
        }
    }

    @Nested
    inner class GlobalSearch {

        @Test
        fun `should search across all files`() {
            index.addFile(Path.of("/file1.txt"), "TODO: fix this", 14, 1000)
            index.addFile(Path.of("/file2.txt"), "Another TODO", 12, 1000)
            index.addFile(Path.of("/file3.txt"), "No matches here", 15, 1000)

            val response = index.search("TODO")

            assertEquals(2, response.totalMatches)
            assertFalse(response.truncated)
        }

        @Test
        fun `should respect maxResults limit`() {
            index.addFile(Path.of("/file1.txt"), "a a a a a", 9, 1000)
            index.addFile(Path.of("/file2.txt"), "a a a a a", 9, 1000)

            val config = SearchConfig(maxResults = 5)
            val response = index.search("a", config)

            assertEquals(5, response.results.size)
            assertTrue(response.truncated)
        }

        @Test
        fun `should return empty response for empty query`() {
            index.addFile(Path.of("/file.txt"), "Content", 7, 1000)

            val response = index.search("")

            assertEquals(0, response.totalMatches)
        }

        @Test
        fun `should include line content in results`() {
            index.addFile(Path.of("/file.txt"), "   Hello World   ", 17, 1000)

            val results = index.search("Hello").results

            assertEquals(1, results.size)
            assertEquals("Hello World", results[0].lineContent) // Trimmed
        }
    }

    @Nested
    inner class Snapshot {

        @Test
        fun `should create immutable snapshot`() {
            index.addFile(Path.of("/file.txt"), "Content", 7, 1000)

            val snapshot = index.snapshot()
            index.addFile(Path.of("/file2.txt"), "Content 2", 9, 1000)

            assertEquals(1, snapshot.size)
            assertEquals(2, index.fileCount)
        }
    }

    @Nested
    inner class ThreadSafety {

        @Test
        fun `should handle concurrent reads and writes`() {
            val threads = (1..10).map { i ->
                Thread {
                    repeat(100) { j ->
                        val path = Path.of("/file_${i}_$j.txt")
                        index.addFile(path, "Content $i $j", 20, System.currentTimeMillis())
                        index.search("Content")
                    }
                }
            }

            threads.forEach { it.start() }
            threads.forEach { it.join() }

            assertEquals(1000, index.fileCount)
        }
    }
}
