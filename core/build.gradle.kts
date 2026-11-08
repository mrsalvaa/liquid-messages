// SPDX-License-Identifier: GPL-3.0-or-later
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Lógica sin Android: números, búsqueda, conversaciones, largo de los SMS, códigos y enlaces, y
// política de elección de chip. Se prueba en la JVM.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.libphonenumber)
    testImplementation(libs.kotlin.test)
}
