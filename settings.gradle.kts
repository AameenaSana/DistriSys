plugins {
    // Lets the Java 21 toolchain be downloaded automatically if it isn't installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    // Declare repositories once, here; modules may not add their own.
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories { mavenCentral() }
}

rootProject.name = "distrisys"

include("proto", "erasure", "storage-node")
