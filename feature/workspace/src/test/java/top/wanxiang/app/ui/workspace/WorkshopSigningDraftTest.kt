package top.wanxiang.app.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WorkshopSigningDraftTest {

    @Test
    fun `blank prefix uses wanxiang-release default pattern`() {
        val draft = generateDefaultSigningDraft("")
        val dateStr = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        val yearStr = SimpleDateFormat("yyyy", Locale.getDefault()).format(Date())

        assertTrue(draft.name.startsWith("wanxiang-release-$dateStr-"))
        assertEquals("wanxiang-release-key", draft.alias)
        assertTrue(draft.storePassword.startsWith("WanXiang#$yearStr" + "_"))
        assertEquals(draft.storePassword, draft.keyPassword)
        assertEquals(25, draft.validityYears)
        assertEquals("WanXiang Developer", draft.organization)
        assertTrue("密码长度应 >= 6", draft.storePassword.length >= 6)
    }

    @Test
    fun `custom prefix is formatted cleanly into name, alias, password, and org`() {
        val draft = generateDefaultSigningDraft("mygame_pro")
        val dateStr = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        val yearStr = SimpleDateFormat("yyyy", Locale.getDefault()).format(Date())

        assertTrue(draft.name.startsWith("mygame-pro-$dateStr-"))
        assertEquals("mygame-pro-key", draft.alias)
        assertTrue(draft.storePassword.startsWith("MygamePro#$yearStr" + "_"))
        assertEquals(draft.storePassword, draft.keyPassword)
        assertEquals(25, draft.validityYears)
        assertEquals("MygamePro Developer", draft.organization)
    }

    @Test
    fun `repeated clicking strips prior date and hex suffix instead of compounding`() {
        val first = generateDefaultSigningDraft("myapp")
        val second = generateDefaultSigningDraft(first.name)

        assertTrue(second.name.startsWith("myapp-"))
        // Should not have double date tags like myapp-20260830-xxxx-20260830-yyyy
        assertEquals(1, Regex("\\d{8}").findAll(second.name).count())
        assertEquals("myapp-key", second.alias)
    }
}
