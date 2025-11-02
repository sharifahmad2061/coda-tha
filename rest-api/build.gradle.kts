plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
    application
}

group = "com.sahmad"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    // Ktor Server
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.call.logging)

    // Logging
    implementation(libs.logback.classic)
    implementation(libs.logstash.logback.encoder)

    // Testing
    testImplementation(libs.kotlin.test)
    testImplementation(libs.ktor.server.test.host)
}

application {
    mainClass.set("com.sahmad.restapi.ApplicationKt")
}

tasks.jar {
    manifest {
        attributes(
            "Main-Class" to "com.sahmad.restapi.ApplicationKt",
        )
    }
}

ktor {
    docker {
        jreVersion.set(JavaVersion.VERSION_21)
        localImageName.set("rest-api")
        imageTag.set("latest")
        customBaseImage.set("bellsoft/liberica-openjre-alpine:21.0.8")
    }
}

kotlin {
    jvmToolchain(21)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
