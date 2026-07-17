plugins {
    kotlin("jvm") version "2.4.20-Beta1"
    id("com.gradleup.shadow") version "8.3.0"
}

repositories {
    mavenLocal()
    mavenCentral()
    maven {
        name = "papermc-repo"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
    maven {
        name = "azisaba-repo"
        url = uri("https://repo.azisaba.net/repository/maven-public/")
    }
    maven {
        name = "sonatype"
        url = uri("https://oss.sonatype.org/content/groups/public/")
    }
    maven {
        name = "jitpack-repo"
        url = uri("https://jitpack.io")
    }
    maven {
        name = "minebench"
        url = uri("https://repo.minebench.de/")
    }
    maven {
        name = "codemc-repo"
        url = uri("https://repo.codemc.io/repository/maven-public/")
    }
}

dependencies {
    compileOnly("com.destroystokyo.paper:paper-api:1.13.2-R0.1-SNAPSHOT")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7")
    compileOnly("de.epiceric:ShopChest:1.13-SNAPSHOT")
    compileOnly("com.acrobot.chestshop:chestshop:3.12.2")
    compileOnly("Jobs:jobs:5.2.6.6")
    compileOnly("net.azisaba:CraftGUI:1.15.2+1.1.1")
    compileOnly(fileTree("libs") {
        include("*.jar")
    })
    implementation("com.zaxxer:HikariCP:4.0.3")
    implementation("mysql:mysql-connector-java:8.0.33")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("redis.clients:jedis:5.1.0")
}

kotlin {
    jvmToolchain(8)
}

tasks {
    build {
        dependsOn(shadowJar)
    }

    processResources {
        val props = mapOf("version" to version, "description" to project.description)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
}
