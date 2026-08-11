pluginManagement {
    repositories {
        // 阿里云镜像代理 Gradle 插件门户与 Maven Central，国内/受限网络更稳定
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/central")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 阿里云镜像代理 Maven Central，国内/受限网络更稳定
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/central")
        // JitPack 托管 com.github.* 坐标（如 spw-workshop-api），优先于 Central 以避开 429
        maven("https://jitpack.io")
        mavenCentral()
    }
}

rootProject.name = "spw-multilyrics"
