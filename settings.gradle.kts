pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "peakid-android"

// :engine es Kotlin Multiplatform desde el día uno (ver el plan, Decisión 3).
// Hoy solo declara el target jvm porque en esta máquina no hay ni SDK de
// Android ni macOS; añadir androidTarget() e iosArm64() es una línea cada uno y
// no mueve ni un fichero, que es justamente el motivo de empezar así.
include(":engine")
