val kotlinVersion: String by project
val logbackVersion: String by project
val ktorVersion: String by project
val exposedVersion: String by project
val kotlinCsvVersion: String by project
val mariadbVersion: String by project
val hikariVersion: String by project
val jsoupVersion: String by project
val mockkVersion: String by project
val coroutinesTestVersion: String by project
val testContainersVersion: String by project
val testContainersLatestVersion: String by project
plugins {
    kotlin("jvm") version "2.2.20"
    id("io.ktor.plugin") version "3.3.1"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.20"
    id("org.sonarqube") version "7.2.2.6593"
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

kotlin {
    jvmToolchain(17)
}


version = "0.0.1"

application {
    mainClass = "medicapp.server.ApplicationKt"
}

repositories {
    mavenCentral()
}

dependencies {

    // Projet base de donnée
    implementation("com.github.doyaaaaaken:kotlin-csv-jvm:$kotlinCsvVersion")

    // Ktor serveur
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")

    // Plugins Ktor utiles
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-config-yaml:$ktorVersion") // pour permettre à ktor de pouvoir lire les fichiers yaml
    implementation("io.ktor:ktor-server-swagger:$ktorVersion") // pour pouvoir ajouter une documentation swagger
    implementation("ch.qos.logback:logback-classic:$logbackVersion")
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")

    // Base de données
    implementation("org.mariadb.jdbc:mariadb-java-client:$mariadbVersion")
    implementation("com.zaxxer:HikariCP:$hikariVersion")

    // Exposed (ORM / DSL SQL)
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-kotlin-datetime:$exposedVersion")
    implementation("io.ktor:ktor-server-cors:$ktorVersion")
    implementation("io.ktor:ktor-server-rate-limit:$ktorVersion") // limitation du débit par client
    implementation("io.ktor:ktor-server-forwarded-header:$ktorVersion") // IP réelle du client derrière Caddy

    // Scraping
    implementation("org.jsoup:jsoup:$jsoupVersion")

    // Tests
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.ktor:ktor-client-core:$ktorVersion")
    testImplementation("io.ktor:ktor-client-cio:$ktorVersion")
    testImplementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    testImplementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    testImplementation("io.ktor:ktor-client-json:$ktorVersion")
    testImplementation("io.ktor:ktor-client-serialization:$ktorVersion")

    testImplementation("io.mockk:mockk:$mockkVersion")

    // Testcontainers pour tests d'intégration avec MariaDB
    testImplementation("org.testcontainers:testcontainers:$testContainersLatestVersion")
    testImplementation("org.testcontainers:mariadb:$testContainersVersion")
    testImplementation("org.testcontainers:junit-jupiter:$testContainersVersion")
    // Cette dépendance a été mise à jour récemment mais le reste des modules ne sont pas encore au même niveau
    // Normalement on préfère utiliser la même version pour tous mais le problème est que pour testcontainers:1.21.4
    // il y a des vulnérabilitées donc on prefère le mettre si cela ne casse pas la compatibilité avec les autres dépendances

    // Coroutines test support
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesTestVersion")
}

application {
    mainClass.set("medicapp.server.ApplicationKt")
}

tasks.withType<Test> {
    useJUnitPlatform()
}