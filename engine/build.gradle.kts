plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

kotlin {
    jvm()

    sourceSets {
        // kotlinx.serialization solo para leer manifest.json. El resto del
        // motor no tiene dependencias: las fórmulas se implementan a mano,
        // igual que en Python, para que queden bajo el control de sus tests.
        // Un parser de JSON no es una fórmula y escribirlo a mano sería peor.
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// Los paquetes de región no se versionan (decenas de MB). Los tests dorados
// que los necesitan leen esta ruta; el defecto es la disposición real en
// disco, con el repo del motor como hermano de este.
val packsDir: String = (findProperty("peakid.packs.dir") as String?)
    ?: rootProject.layout.projectDirectory.dir("../peakid/packs").asFile.absolutePath

tasks.withType<Test>().configureEach {
    systemProperty("peakid.packs.dir", packsDir)
    // El informe de casos dorados inactivos se imprime por stdout: sin esto
    // Gradle se lo traga y el aviso no serviría de nada, que es justo lo que
    // este mecanismo viene a evitar.
    testLogging {
        showStandardStreams = true
        events("skipped", "failed")
    }
}
