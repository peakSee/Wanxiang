package top.wanxiang.app.runtime.signing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.wanxiang.app.runtime.build.buildCreateKeystoreCommand

class WorkshopSigningManagerTest {

    @Test
    fun createCommandPassesValidityAsCalculatedDayCount() {
        val command = buildCreateKeystoreCommand(
            javaHome = "/opt/wanxiang/toolchains/android/jdk",
            keystorePath = "/opt/wanxiang/keystores/release.p12",
            alias = "release",
            storePassword = "store-secret",
            keyPassword = "key-secret",
            validityYears = 10,
            dname = "CN=Release, C=CN",
        )

        assertTrue(command.contains(" -validity 3650 "))
        assertFalse(command.contains(" * 365"))
    }

    @Test
    fun createCommandClampsValidityBeforeConvertingToDays() {
        val minimum = createCommand(validityYears = 0)
        val maximum = createCommand(validityYears = 101)

        assertTrue(minimum.contains(" -validity 365 "))
        assertTrue(maximum.contains(" -validity 36500 "))
    }

    private fun createCommand(validityYears: Int): String = buildCreateKeystoreCommand(
        javaHome = "/jdk",
        keystorePath = "/keystore.p12",
        alias = "release",
        storePassword = "store-secret",
        keyPassword = "key-secret",
        validityYears = validityYears,
        dname = "CN=Release, C=CN",
    )
}
