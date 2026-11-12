pluginManagement {
    repositories {
        // Mainland mirrors first.
        //
        // A direct hop to repo.maven.apache.org / plugins.gradle.org was dropping
        // parallel TLS handshakes on this machine (`SSL peer shut down
        // incorrectly`), and the mirrors are much faster from China regardless.
        // The upstream repositories stay last so the build still works from
        // anywhere else in the world.
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
    }
}

rootProject.name = "FakeLoc"
include(":app")
