plugins {
    java
}

group = "com.caellab.n00bcocoon"
version = "1.1.0"

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
    filesMatching("plugin.yml") {
        expand("version" to version)
    }
}

tasks.jar {
    archiveFileName.set("N00bCocoon-${version}.jar")
    destinationDirectory.set(file("/tmp/cocoon-build/build/libs"))
}
