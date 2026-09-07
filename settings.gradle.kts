pluginManagement {
    // CI（GitHub Actions 自动注入 CI=true）在海外，阿里云镜像同步滞后且访问慢；
    // 本地开发亦可通过 -PuseOfficialRepos=true 或 USE_OFFICIAL_REPOS=true 强制走官方源。
    val useOfficialRepos = System.getenv("CI") == "true" ||
        providers.gradleProperty("useOfficialRepos").orNull == "true" ||
        System.getenv("USE_OFFICIAL_REPOS") == "true"
    repositories {
        if (useOfficialRepos) {
            google()
            mavenCentral()
            gradlePluginPortal()
        } else {
            gradlePluginPortal()
            maven {
                url = uri("https://maven.aliyun.com/repository/google")
                isAllowInsecureProtocol = false
            }
            maven {
                url = uri("https://maven.aliyun.com/repository/central")
            }
            maven {
                url = uri("https://maven.aliyun.com/repository/gradle-plugin")
            }
            maven {
                url = uri("https://maven.aliyun.com/repository/public")
            }
            google()
            mavenCentral()
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    val useOfficialRepos = System.getenv("CI") == "true" ||
        providers.gradleProperty("useOfficialRepos").orNull == "true" ||
        System.getenv("USE_OFFICIAL_REPOS") == "true"
    repositories {
        if (useOfficialRepos) {
            google()
            mavenCentral()
        } else {
            maven {
                url = uri("https://maven.aliyun.com/repository/google")
                isAllowInsecureProtocol = false
            }
            maven {
                url = uri("https://maven.aliyun.com/repository/central")
            }
            maven {
                url = uri("https://maven.aliyun.com/repository/public")
            }
            google()
            mavenCentral()
        }
        // Kadb 的 SPAKE2 Android 实现仅发布在 JitPack；限制到精确 group，避免扩大依赖解析面。
        maven {
            url = uri("https://jitpack.io")
            content { includeGroup("com.github.Flyfish233") }
        }
    }
}

rootProject.name = "WanXiang"
include(":app")
include(":baselineprofile")
include(":core:common")
include(":core:model")
include(":core:database")
include(":core:datastore")
include(":core:network")
include(":core:security")
include(":runtime")
include(":project-template")
include(":tools")
include(":harness")
include(":feature:theme")
include(":feature:components")
include(":feature:home")
include(":feature:chat")
include(":feature:terminal")
include(":feature:workspace")
include(":feature:settings")
include(":feature:developer")
include(":feature:custom_iteration")
include(":feature:onboarding")
include(":feature:navigation")
include(":core:browser")
include(":runtime:browser")
include(":feature:browser")
