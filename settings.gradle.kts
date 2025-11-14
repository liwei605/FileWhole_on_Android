pluginManagement {
    repositories {
        // ✅ 官方源优先，保证能解析最新的 Android Gradle Plugin
        google()
        gradlePluginPortal()
        mavenCentral()
        maven { url=uri ("https://jitpack.io") }
        maven { url=uri ("https://maven.aliyun.com/repository/releases") }
        maven { url=uri ("https://maven.aliyun.com/repository/google") }
        maven { url=uri ("https://maven.aliyun.com/repository/central") }
        maven { url=uri ("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url=uri ("https://maven.aliyun.com/repository/public") }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://maven.aliyun.com/repository/releases") }
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "My Application"
include(":app")
