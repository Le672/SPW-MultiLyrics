import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.jetbrains.kotlin.jvm)
    alias(libs.plugins.jetbrains.kotlin.plugin.serialization)
    alias(libs.plugins.jetbrains.kotlin.kapt)
}

group = "com.spw.multilyrics"
version = "0.1.2"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

dependencies {
    compileOnly(kotlin("stdlib"))
    compileOnly(libs.spw.workshop.api) {
        isTransitive = false
    }
    compileOnly(libs.pf4j)
    kapt(libs.pf4j)

    implementation(libs.kotlinx.serialization.json)
}

// ===== 插件元数据 =====
val pluginClass = "com.spw.multilyrics.MultiLyricsPlugin"
val pluginId = "com.spw.multilyrics"
val pluginName = "MultiLyrics"
val pluginDescription = "为 Salt Player for Windows 从 Apple Music / 网易云 / QQ / 酷狗 / 酷我 / Spotify 等平台在线搜索并匹配歌词。"
val pluginVersion = version
val pluginProvider = "MultiLyrics"
val pluginRepository = "https://github.com/Moriafly/spw-workshop-api"

tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Plugin-Class" to pluginClass,
            "Plugin-Id" to pluginId,
            "Plugin-Name" to pluginName,
            "Plugin-Description" to pluginDescription,
            "Plugin-Version" to pluginVersion,
            "Plugin-Provider" to pluginProvider,
            "Plugin-Has-Config" to "true",
            "Plugin-Open-Source-Url" to pluginRepository,
        )
    }
}

tasks.register<Zip>("plugin") {
    group = "build"
    description = "打包 SPW 创意工坊插件 (zip)。"
    archiveFileName.set("$pluginName-$pluginVersion.zip")
    destinationDirectory.set(layout.buildDirectory.dir("plugins"))

    into("classes") {
        with(tasks.named<Jar>("jar").get())
    }
    dependsOn(configurations.runtimeClasspath)
    into("lib") {
        from({
            configurations.runtimeClasspath
                .get()
                .filter { it.name.endsWith("jar") }
                // SPW 运行时已提供 Kotlin stdlib，避免版本冲突
                .filter { !it.name.startsWith("kotlin-stdlib-") }
        })
    }
    archiveExtension.set("zip")
}

// 拒绝引用 SPW 运行时未提供的 java.net.http 模块
val verifyRuntimeCompatibility by tasks.registering {
    group = "verification"
    description = "拒绝引用 java.net.http（SPW 运行时未包含该模块）。"
    dependsOn(tasks.classes)
    doLast {
        val offenders = fileTree(layout.buildDirectory.dir("classes/kotlin/main")) {
            include("**/*.class")
        }.files.filter { file ->
            file.readBytes().toString(Charsets.ISO_8859_1).contains("java/net/http")
        }
        check(offenders.isEmpty()) {
            "SPW 运行时不包含 java.net.http；以下类违规：${offenders.joinToString { it.name }}"
        }
    }
}

tasks.named("plugin") { dependsOn(verifyRuntimeCompatibility) }
tasks.named("check") { dependsOn(verifyRuntimeCompatibility) }
