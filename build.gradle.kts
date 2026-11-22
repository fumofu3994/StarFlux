plugins {
    java
    application
    id("org.javamodularity.moduleplugin") version "1.8.15"
    id("org.openjfx.javafxplugin") version "0.1.0"
    id("org.beryx.jlink") version "2.25.0"
    id("edu.sc.seis.launch4j") version "4.0.0"
}

group = "com.fumofu"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

val junitVersion = "5.12.1"

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(25)
}

application {
    mainModule.set("com.fumofu.starflux")
    mainClass.set("com.fumofu.starflux.app.HelloApplication")
}

javafx {
    version = "21.0.6"
    modules = listOf("javafx.controls", "javafx.fxml")
}

dependencies {
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation("org.junit.jupiter:junit-jupiter-api:${junitVersion}")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:${junitVersion}")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "com.fumofu.starflux.LauncherBootstrap"
    }
}

launch4j {
    mainClassName.set("com.fumofu.starflux.LauncherBootstrap")
    outputDir.set("distributions")
    libraryDir.set(".")
    outfile.set("StarFluxMCLauncher.exe")
    dontWrapJar.set(true)
    copyConfigurable.set(files(tasks.named("jar")))
    headerType.set("gui")
    chdir.set(".")
    stayAlive.set(true)
    errTitle.set("StarFlux MC Launcher")
    downloadUrl.set("https://adoptium.net/temurin/releases/?version=25")
    bundledJrePath.set("%JAVA_HOME%;%PATH%")
    jreMinVersion.set("25")
    requires64Bit.set(true)
    messagesJreNotFoundError.set(
        "未检测到 64 位 Java 25 或更高版本。请在打开的网页下载安装 Java，然后重新运行启动器。"
    )
    messagesJreVersionError.set(
        "检测到的 Java 版本低于 25。请下载安装 64 位 Java 25 或更高版本后重试。"
    )
    productName.set("StarFlux MC Launcher")
    fileDescription.set("StarFlux MC Launcher")
    internalName.set("StarFluxMCLauncher")
    copyright.set("Copyright (c) StarFlux")
    version.set("1.0.0.0")
    textVersion.set(project.version.toString())
}

tasks.named("build") {
    dependsOn("createExe")
}

jlink {
    imageZip.set(layout.buildDirectory.file("distributions/StarFluxMCLauncher-${javafx.platform.classifier}.zip"))
    options.set(listOf("--strip-debug", "--compress", "2", "--no-header-files", "--no-man-pages"))
    launcher {
        name = "StarFluxMCLauncher"
    }
}
