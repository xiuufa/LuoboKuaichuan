package com.lobo.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BoardRepositoryTest {

    private fun newRepo(): Pair<BoardRepository, File> {
        val dir = Files.createTempDirectory("luobo-board").toFile()
        val store = File(dir, "board.json")
        return BoardRepository(dir, store, 100) to dir
    }

    @Test
    fun textSizeIsUtf8ByteCount() {
        val (repo, _) = newRepo()
        val item = repo.addText("修复后测试中文abc", "tester", "t1")
        assertEquals(24L, item.size)
        assertEquals("修复后测试中文abc", item.text)
        assertEquals(BoardItem.KIND_TEXT, item.kind)
        assertEquals(1L, repo.currentVersion())
        assertEquals(1, repo.size())
    }

    @Test
    fun textPersistsAndReloads() {
        val (repo, dir) = newRepo()
        val item = repo.addText("hello 萝卜", "tester", "t1")
        val reloaded = BoardRepository(dir, File(dir, "board.json"), 100)
        val got = reloaded.get(item.id)
        assertNotNull(got)
        assertEquals(item.size, got!!.size)
        assertEquals("hello 萝卜", got.text)
        assertEquals(1L, reloaded.currentVersion())
    }

    @Test
    fun fileLifecycle() {
        val (repo, _) = newRepo()
        val (item, file) = repo.beginFile("a.txt", 5, "text/plain", "s", "s1")
        assertEquals(BoardItem.STATE_RECEIVING, item.state)
        file.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        repo.appendReceived(item.id, 5)
        assertEquals(5L, repo.get(item.id)!!.received)
        val done = repo.completeFile(item.id)
        assertEquals(BoardItem.STATE_READY, done!!.state)
        assertTrue(repo.fileOf(item.id)!!.isFile)
        // 完成后磁盘名为原始文件名（不带 id 前缀）
        assertEquals("a.txt", repo.fileOf(item.id)!!.name)
        assertEquals(2L, repo.currentVersion())
    }

    @Test
    fun deleteRemovesEntryAndFile() {
        val (repo, _) = newRepo()
        val (item, file) = repo.beginFile("b.bin", 1, "application/octet-stream", "s", "s1")
        file.writeBytes(byteArrayOf(9))
        repo.appendReceived(item.id, 1)
        repo.completeFile(item.id)
        assertTrue(repo.delete(item.id))
        assertNull(repo.get(item.id))
        assertTrue(!file.exists())
    }

    @Test
    fun resendTextCreatesNewEntry() {
        val (repo, _) = newRepo()
        val item = repo.addText("hello", "s", "s1")
        val copy = repo.resend(item.id, "me", "self")!!
        assertNotEquals(item.id, copy.id)
        assertEquals("hello", copy.text)
        assertEquals("me", copy.sender)
        assertEquals(2, repo.size())
    }

    @Test
    fun deleteFileOnlyKeepsRecord() {
        val (repo, _) = newRepo()
        val (item, file) = repo.beginFile("c.txt", 2, "text/plain", "s", "s1")
        file.writeBytes(byteArrayOf(1, 2))
        repo.appendReceived(item.id, 2)
        repo.completeFile(item.id)
        assertTrue(repo.deleteFileOnly(item.id))
        assertTrue(!repo.fileOf(item.id)!!.exists())
        assertEquals(BoardItem.STATE_FAILED, repo.get(item.id)!!.state)
    }
}
