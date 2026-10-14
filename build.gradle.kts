plugins {
    java
}

group = "com.caellab.n00bcocoon"
version = "20261015014231"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.processResources {
    // 不声明输入的话，只改 version 时这个任务会被判定 UP-TO-DATE 跳过，plugin.yml 里的版本号就留在旧值
    inputs.property("version", version)
    filesMatching("plugin.yml") {
        expand("version" to version)
    }
}

tasks.jar {
    archiveFileName.set("N00bCocoon-${version}.jar")
}
