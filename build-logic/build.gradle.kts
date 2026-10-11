import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    `java-gradle-plugin`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.sam.with.receiver)
}

repositories {
    gradlePluginPortal()
}

dependencies {
    compileOnly(gradleKotlinDsl())
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.intellij.platform.gradle.plugin)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        apiVersion = KotlinVersion.KOTLIN_2_2
        languageVersion = KotlinVersion.KOTLIN_2_2
    }
}

samWithReceiver {
    annotation("org.gradle.api.HasImplicitReceiver")
}

gradlePlugin {
    plugins {
        register("sqlDialectPlugin") {
            id = "dev.sort.sql-dialect-plugin"
            implementationClass = "dev.sort.gradle.SqlDialectPlugin"
        }
    }
}
