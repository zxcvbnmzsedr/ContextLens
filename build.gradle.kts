plugins {
    kotlin("jvm") version "1.9.23"
    id("org.jetbrains.intellij") version "1.17.2"
}

group = "com.ztianzeng.contextlens"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(17)
}

intellij {
    version.set("2023.3")
    type.set("IC")
    plugins.set(listOf("java", "Git4Idea"))
}

dependencies {
    implementation("com.google.code.gson:gson:2.10.1")
}

tasks {
    patchPluginXml {
        sinceBuild.set("233")
        untilBuild.set("243.*")
    }

    register<Copy>("copyWebview") {
        group = "build"
        description = "Copy built React webview assets into resources"
        val webviewDist = project.layout.projectDirectory.dir("webview/dist")
        val targetDir = project.layout.projectDirectory.dir("src/main/resources/webview/contextlens")
        from(webviewDist)
        into(targetDir)
        onlyIf { webviewDist.asFile.exists() }
    }

    buildPlugin {
        dependsOn("copyWebview")
    }

    runIde {
        jvmArgs("-Dcontextlens.webview.devserver=http://localhost:4173")
    }
}
