package top.wanxiang.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import top.wanxiang.app.core.datastore.GitCredential

class GitAuthTest {

    @Test
    fun `hostOf parses https url`() {
        assertEquals("github.com", GitAuth.hostOf("https://github.com/peakSee/Wanxiang.git"))
    }

    @Test
    fun `hostOf parses https url with existing userinfo`() {
        assertEquals("gitee.com", GitAuth.hostOf("https://user@gitee.com/foo/bar.git"))
    }

    @Test
    fun `hostOf parses ssh scp url`() {
        assertEquals("github.com", GitAuth.hostOf("git@github.com:peakSee/Wanxiang.git"))
    }

    @Test
    fun `hostOf parses ssh url form`() {
        assertEquals("gitlab.example.com", GitAuth.hostOf("ssh://git@gitlab.example.com/team/repo.git"))
    }

    @Test
    fun `hostOf returns null for local paths`() {
        assertNull(GitAuth.hostOf("/workspace/repo"))
        assertNull(GitAuth.hostOf(""))
    }

    @Test
    fun `findCredential exact host match`() {
        val cred = credential(host = "github.com")
        assertEquals(cred, GitAuth.findCredential(listOf(cred), "github.com"))
    }

    @Test
    fun `findCredential case insensitive`() {
        val cred = credential(host = "GitHub.com")
        assertEquals(cred, GitAuth.findCredential(listOf(cred), "github.com"))
    }

    @Test
    fun `findCredential suffix match subdomain`() {
        val cred = credential(host = "github.com")
        assertEquals(cred, GitAuth.findCredential(listOf(cred), "api.github.com"))
    }

    @Test
    fun `findCredential returns null when no match`() {
        assertNull(GitAuth.findCredential(listOf(credential(host = "github.com")), "gitee.com"))
        assertNull(GitAuth.findCredential(emptyList(), "github.com"))
        assertNull(GitAuth.findCredential(listOf(credential()), null))
    }

    @Test
    fun `wrap embeds credential line into a single shell statement with cleanup`() {
        val cred = credential(host = "github.com", username = "octocat", token = "ghp_ABCDEF")
        val wrapped = GitAuth.wrap("git pull", cred)
        // 关键片段：临时文件 + printf 写入 URL 编码后的凭证 + GIT_CONFIG_KEY_0=credential.helper + 命令 + rm 清理
        assertNotNull(Regex("mktemp").find(wrapped))
        assert(wrapped.contains("printf '%s\\n' 'https://octocat:ghp_ABCDEF@github.com'"))
        assert(wrapped.contains("GIT_CONFIG_KEY_0=credential.helper"))
        assert(wrapped.contains("GIT_CONFIG_VALUE_0=\"store --file=\$__cred\""))
        assert(wrapped.contains("git pull"))
        assert(wrapped.contains("rm -f \"\$__cred\""))
        assert(wrapped.contains("exit \$__rc"))
    }

    @Test
    fun `wrap url-encodes username and token to avoid shell special chars`() {
        val cred = credential(host = "gitlab.com", username = "user@corp", token = "p/a:s\$s")
        val wrapped = GitAuth.wrap("git push", cred)
        // user@corp -> user%40corp, p/a:s$s -> p%2Fa%3As%24s
        assert(wrapped.contains("https://user%40corp:p%2Fa%3As%24s@gitlab.com"))
    }

    private fun credential(
        host: String = "example.com",
        username: String = "user",
        token: String = "tok",
    ) = GitCredential(
        id = "id-1",
        name = "test",
        host = host,
        username = username,
        token = token,
        createdAtMillis = 0L,
    )
}
