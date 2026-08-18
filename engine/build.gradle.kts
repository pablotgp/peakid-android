// Desde AGP 9, `com.android.library` es incompatible con el plugin
// multiplatform: el target Android de un módulo KMP se declara con
// `com.android.kotlin.multiplatform.library` y su bloque `androidLibrary`.
plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.kotlin.multiplatform.library")
}

// Una sola version para escritorio y Android: si divergen, el criterio de
// aceptacion deja de significar lo que dice. La cresta se compara con
// tolerancia el cuanto del modelo justamente porque dos COMPILACIONES de ORT
// no dan bit a bit lo mismo; dos VERSIONES distintas serian otra cosa.
val ONNX_VERSION = "1.20.0"

kotlin {
    jvm()

    android {
        namespace = "peakid.engine"
        compileSdk = 37
        minSdk = 26
    }

    sourceSets {
        // kotlinx.serialization solo para leer manifest.json y escribir el
        // .align.json. El resto del motor no tiene dependencias: las fórmulas
        // se implementan a mano, igual que en Python, para que queden bajo el
        // control de sus tests. Un parser de JSON no es una fórmula y
        // escribirlo a mano sería peor.
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        // ONNX Runtime: MISMA API Java (`ai.onnxruntime.*`) en escritorio y en
        // Android, solo cambia el artefacto. Por eso el código de inferencia se
        // escribe UNA vez en jvmMain y Android lo reutiliza — que es lo que
        // permite comprobar el criterio de aceptación (las seis fotos contra
        // cresta_referencia.json) en la JVM, sin dispositivo, y dejar el móvil
        // para confirmar que su compilación de ORT coincide.
        jvmMain.dependencies {
            implementation("com.microsoft.onnxruntime:onnxruntime:$ONNX_VERSION")
        }
        androidMain.dependencies {
            implementation("com.microsoft.onnxruntime:onnxruntime-android:$ONNX_VERSION")
        }
        // El acceso a ficheros de jvmMain (RandomAccessFile) vale igual en
        // Android: es el MISMO java.io. Por eso androidMain lo reutiliza en vez
        // de duplicar un `actual`.
        androidMain.get().kotlin.srcDir("src/jvmMain/kotlin")
    }
}

// Los paquetes de región no se versionan (decenas de MB). Los tests dorados
// que los necesitan leen esta ruta; el defecto es la disposición real en
// disco, con el repo del motor como hermano de este.
val packsDir: String = (findProperty("peakid.packs.dir") as String?)
    ?: rootProject.layout.projectDirectory.dir("../peakid/packs").asFile.absolutePath

// El criterio de aceptacion de la fase 6 vive en el repo del MOTOR
// (tests/data/cresta_referencia.json, models/*.onnx y las seis fotos). No se
// copia aqui: una copia se desincroniza y los dos lados acabarian
// comparandose con cosas distintas.
val motorDir: String = (findProperty("peakid.motor.dir") as String?)
    ?: rootProject.layout.projectDirectory.dir("../peakid").asFile.absolutePath

tasks.withType<Test>().configureEach {
    systemProperty("peakid.packs.dir", packsDir)
    systemProperty("peakid.motor.dir", motorDir)
    // el modelo son 15 MB y las fotos hasta 12 Mpx: la imagen de trabajo en
    // Double son ~33 MB por foto, mas los mapas de coste
    maxHeapSize = "2g"
    // El informe de casos dorados inactivos se imprime por stdout: sin esto
    // Gradle se lo traga y el aviso no serviría de nada, que es justo lo que
    // este mecanismo viene a evitar.
    testLogging {
        showStandardStreams = true
        events("skipped", "failed")
    }
}
