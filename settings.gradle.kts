pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // A libmpv built on this PC (OwnTV_libmpv's buildscripts/wsl_build.sh), only while
        // the build command carries -Powntv.libmpvLocalRepo=<folder> (never a file) — core's :player-core then asks
        // for version "local". Core's settings carry the same lines.
        providers.gradleProperty("owntv.libmpvLocalRepo").orNull?.let { dir ->
            maven {
                name = "LocalLibmpv"
                url = uri(file(dir))
                content { includeVersion("tv.own.owntv", "libmpv", "local") }
            }
        }
        google()
        mavenCentral()
        // OwnTV's own Maven repository — public, no login: tv.own.owntv:core and :player-core
        // (built from https://github.com/ahXN00/OwnTV_Core) and tv.own.owntv:libmpv, the mpv engine
        // (https://github.com/ahXN00/OwnTV_libmpv). Served from OwnTV_Core's gh-pages branch.
        maven {
            name = "OwnTV"
            url = uri("https://ahxn00.github.io/OwnTV_Core/maven")
            content { includeGroup("tv.own.owntv") }
        }
    }
}

// Local development: build against core's own source instead of the published artifact, so a core
// edit reaches this app with no publish step. Gradle substitutes the dependency automatically
// because OwnTV_Core publishes under the same group and artifact ids this app asks for. CI leaves
// owntv.corePath unset and resolves the pinned version instead.
// Set it in ~/.gradle/gradle.properties, never here:  owntv.corePath=E:/MEGA/CODE/AI/OwnTV_Core
// 8K PRO: the older-Fire-TV build (-Pbrand.firetv=true) compiles core from firetv-core/, a copy of
// OwnTV_Core 1.0.69 set to Android 7.1, in place of the published core (which needs Android 8).
if (providers.gradleProperty("brand.firetv").orNull == "true") {
    includeBuild("firetv-core") { name = "firetvcore" }
}
providers.gradleProperty("owntv.corePath").orNull?.takeIf { it.isNotBlank() }?.let { includeBuild(it) }

rootProject.name = "8KProWeb"
include(":app")
// Baseline-profile generator. Test-only module: it ships nothing to users, it records
// the cold-start journey on a device and writes the profile :app packages.
include(":baselineprofile")
 