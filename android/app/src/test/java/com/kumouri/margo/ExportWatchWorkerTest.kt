package com.kumouri.margo

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Regression coverage for the 2026-09-13 bug: the listener's `ok:true` response lists every table's
 * row count and routinely runs past 600 chars, but the response body used to be truncated to 600
 * chars BEFORE being parsed as JSON — turning a valid, successful response into invalid JSON and
 * reporting a delivered export as a failure forever (every hourly retry re-shipped and was misread
 * the same way).
 */
class ExportWatchWorkerTest {

    private fun longOkBody(): String {
        // A real listener response for a big import: `tables` lists dozens of entries, which is
        // exactly what pushed the real 2026-09-13 body past 600 chars.
        val tables = JSONObject().apply {
            for (i in 1..40) put("table_$i", 1000 + i)
        }
        val body = JSONObject().apply {
            put("ok", true)
            put("duplicate", false)
            put("file", "samsunghealth_will.c.armstrong_20260913183702.zip")
            put("bytes", 98555135L)
            put("export_id", "20260913183702")
            put("rows", 124924)
            put("tables", tables)
        }.toString()
        assertTrue("test body must exceed 600 chars to reproduce the bug", body.length > 600)
        return body
    }

    @Test
    fun `long ok-true body ships even though it exceeds the old 600-char cap`() {
        val body = longOkBody()
        val outcome = parseShipResponse(200, body)
        assertTrue("a full, valid ok:true body must be read as shipped", outcome.ok)
        assertFalse(outcome.retryable)
    }

    @Test
    fun `a body truncated mid-JSON is reported as truncation, never as a false ok-true claim`() {
        val body = longOkBody()
        val truncated = body.take(600) // the old bug: read in full, then chopped before parsing
        val outcome = parseShipResponse(200, truncated)
        assertFalse(outcome.ok)
        assertTrue(
            "message should name the real cause (invalid/unparseable JSON): ${outcome.detail}",
            outcome.detail.contains("not valid JSON"),
        )
        assertFalse(
            "must never claim the body 'did not say ok:true' when it was never fully read: ${outcome.detail}",
            outcome.detail.contains("did not say ok:true"),
        )
    }

    @Test
    fun `a well-formed body that genuinely omits ok-true is reported as such`() {
        val body = JSONObject().apply { put("ok", false); put("error", "bad token") }.toString()
        val outcome = parseShipResponse(200, body)
        assertFalse(outcome.ok)
        assertTrue(outcome.detail.contains("did not say ok:true"))
    }

    @Test
    fun `duplicate and already_imported both read as shipped`() {
        val dup = JSONObject().apply { put("ok", true); put("duplicate", true) }.toString()
        assertTrue(parseShipResponse(200, dup).ok)

        val already = JSONObject().apply {
            put("ok", true)
            put("already_imported", true)
            put("export_id", "20260913183702")
        }.toString()
        assertTrue(parseShipResponse(200, already).ok)
    }

    @Test
    fun `non-2xx never reaches JSON parsing`() {
        val outcome = parseShipResponse(500, "oops")
        assertFalse(outcome.ok)
        assertTrue(outcome.retryable)
        assertEquals("HTTP 500 oops", outcome.detail)
    }

    @Test
    fun `readBounded never reads past the cap`() {
        val text = "x".repeat(1000)
        val bounded = readBounded(text.byteInputStream(), 100)
        assertEquals(100, bounded.length)
    }

    @Test
    fun `readBounded reads the whole body when it is under the cap`() {
        val text = "y".repeat(50)
        val bounded = readBounded(text.byteInputStream(), 1000)
        assertEquals(text, bounded)
    }

    // --- 2026-09-30: cut-off zips reached the desktop ---------------------------------------------

    @Test
    fun `422 is the listener's re-ship answer and is retryable, unlike any other 4xx`() {
        val body = JSONObject().apply {
            put("ok", false); put("reship", true); put("error", "5000B arrived but not a whole zip")
        }.toString()
        val reship = parseShipResponse(HTTP_RESHIP, body)
        assertEquals(422, HTTP_RESHIP)
        assertFalse("a cut-off upload is never delivered", reship.ok)
        assertTrue("422 means zip again and re-ship", reship.retryable)
        assertTrue(reship.detail, reship.detail.contains("re-ship"))

        val badRequest = parseShipResponse(400, """{"ok":false,"error":"bad name"}""")
        assertFalse(badRequest.retryable)
    }

    private val root = "samsunghealth_w_20260930212205/"

    private fun writeZip(entries: Int, prefix: String = root): File {
        val f = File.createTempFile("export-test", ".zip")
        f.deleteOnExit()
        ZipOutputStream(FileOutputStream(f)).use { zos ->
            for (i in 0 until entries) {
                zos.putNextEntry(ZipEntry("${prefix}jsons/t/$i.json"))
                zos.write("{\"n\":$i}".repeat(50).toByteArray())
                zos.closeEntry()
            }
        }
        return f
    }

    @Test
    fun `a whole zip with exactly the files written verifies`() {
        assertNull(verifyZip(writeZip(12), 12, root))
    }

    @Test
    fun `a zip cut off before its central directory never verifies`() {
        val whole = writeZip(12).readBytes()
        val cdAt = (0 until whole.size - 3).first {
            whole[it] == 'P'.code.toByte() && whole[it + 1] == 'K'.code.toByte() &&
                whole[it + 2] == 1.toByte() && whole[it + 3] == 2.toByte()
        }
        for (cut in listOf(0, 40, cdAt / 2, cdAt, whole.size - 5)) {
            val f = File.createTempFile("export-cut", ".zip").apply { deleteOnExit(); writeBytes(whole.copyOf(cut)) }
            assertNotNull("a $cut/${whole.size}-byte prefix verified", verifyZip(f, 12, root))
        }
    }

    @Test
    fun `the 2026-09-30 shape - right file count, someone else's smaller file - never verifies`() {
        // The run counted 65,248 files of its own walk but the file at its path held far fewer.
        val why = verifyZip(writeZip(5), 65248, root)
        assertNotNull(why)
        assertTrue(why!!, why.contains("5 entries but 65248"))
    }

    @Test
    fun `entries outside the export folder, or a missing file, never verify`() {
        assertNotNull(verifyZip(writeZip(3, prefix = "elsewhere/"), 3, root))
        assertNotNull(verifyZip(File("does-not-exist-${System.nanoTime()}.zip"), 3, root))
    }
}
