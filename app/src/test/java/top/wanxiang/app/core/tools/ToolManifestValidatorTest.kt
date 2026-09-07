package top.wanxiang.app.core.tools

import top.wanxiang.app.core.model.ToolManifest
import org.junit.Test

class ToolManifestValidatorTest {
    @Test
    fun acceptsArm64DataOnlyManifest() {
        ToolManifestValidator.validateAll(
            listOf(
                ToolManifest(
                    id = "demo-tool",
                    name = "Demo",
                    description = "验证清单校验",
                    version = "1.0.0",
                    latestVersion = "1.1.0",
                    homepage = "https://example.com/demo",
                ),
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsupportedSchema() {
        ToolManifestValidator.validateAll(
            listOf(
                ToolManifest(
                    schemaVersion = 2,
                    id = "demo-tool",
                    name = "Demo",
                    description = "不应被接受",
                ),
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsafeServicePath() {
        ToolManifestValidator.validateAll(
            listOf(
                ToolManifest(
                    id = "demo-tool",
                    name = "Demo",
                    description = "不应被接受",
                    launchType = "web",
                    servicePort = 8080,
                    servicePath = "/../private",
                ),
            ),
        )
    }
}
