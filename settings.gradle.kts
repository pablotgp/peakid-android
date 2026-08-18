pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "peakid-android"

// :engine es Kotlin Multiplatform desde el día uno (ver el plan, Decisión 3).
// Ya declara jvm y androidTarget; iosArm64 se añade cuando haya un macOS, y
// será otra línea sin mover un solo fichero, que era el motivo de empezar así.
include(":engine")
include(":app")
