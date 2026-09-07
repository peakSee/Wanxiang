package top.wanxiang.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SshCommandFactoryTest {
    private val key = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIMockedPublicKeyDataForWanXiang user@phone"

    @Test
    fun `authorized keys are trimmed and deduplicated`() {
        val normalized = SshCommandFactory.normalizeAuthorizedKeys("  $key  \n\n$key")

        assertEquals(key, normalized)
    }

    @Test
    fun `shell-like public key payload is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            SshCommandFactory.normalizeAuthorizedKeys("ssh-ed25519 AAAA; touch /tmp/owned")
        }
    }

    @Test
    fun `generated configuration keeps raw public key out of shell source`() {
        val command = SshCommandFactory.configureCommand(
            SshRuntimeConfig(port = 9022, authorizedKeys = key),
        )

        assertTrue(command.contains("base64 -d"))
        assertTrue(command.contains("sshd_config"))
        assertFalse(command.contains(key))
    }

    @Test
    fun `privileged ports are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            SshRuntimeConfig(port = 22, authorizedKeys = key)
        }
    }

    @Test
    fun `password-only configuration encodes the credential`() {
        val password = "strong password 42"
        val command = SshCommandFactory.configureCommand(
            SshRuntimeConfig(passwordAuthEnabled = true),
            password,
        )

        assertTrue(command.contains("chpasswd"))
        assertTrue(command.contains("base64 -d"))
        assertFalse(command.contains(password))
    }

    @Test
    fun `weak or ambiguous passwords are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            SshCommandFactory.normalizePassword("short")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SshCommandFactory.normalizePassword("bad:password")
        }
    }
}
