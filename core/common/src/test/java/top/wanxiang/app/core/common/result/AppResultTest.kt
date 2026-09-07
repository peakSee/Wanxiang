package top.wanxiang.app.core.common.result

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppResultTest {
    private val error = AppError(ErrorCode.IO, "boom")

    @Test
    fun `success holds data`() {
        val result: AppResult<Int> = AppResult.Success(42)
        assertTrue(result.isSuccess)
        assertEquals(42, result.getOrNull())
        assertNull(result.errorOrNull())
    }

    @Test
    fun `failure holds error`() {
        val result: AppResult<Int> = AppResult.Failure(error)
        assertTrue(result.isFailure)
        assertNull(result.getOrNull())
        assertEquals("boom", result.errorOrNull()?.message)
    }

    @Test
    fun `map transforms success only`() {
        val result: AppResult<Int> = AppResult.Success(2)
        assertEquals("22", result.map { "$it$it" }.getOrNull())
    }
}
