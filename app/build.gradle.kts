// AGP 9 trae soporte de Kotlin integrado: el plugin kotlin("android") ya no
// hace falta y de hecho falla si se aplica.
plugins {
    id("com.android.application")
    kotlin("plugin.compose")
}

android {
    namespace = "peakid.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "peakid.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.6-fase6"

        ndk {
            // ONNX Runtime trae su librería nativa para las CUATRO ABIs, y
            // cada una pesa más que el propio modelo: medido, el APK pasaba de
            // 11.6 a 96.7 MB, de los que solo 15 son el .onnx. Con arm64-v8a
            // se queda en lo razonable.
            //
            // arm64 cubre cualquier móvil de los últimos años. Deja fuera los
            // emuladores x86, que es el precio, y para publicar habrá que
            // partir por ABI en vez de filtrar — pero eso es la fase de
            // distribución, no esta.
            abiFilters += "arm64-v8a"
        }
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        // El .onnx ya viene comprimido: volver a comprimirlo no ahorra nada y
        // obliga a descomprimir 15 MB al abrirlo. Sin comprimir, ORT lo lee del
        // APK sin copiarlo a disco.
        noCompress += "onnx"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
    }
}

// El .onnx (15 MB) NO se versiona, igual que los tiles .hgt y los paquetes de
// region: se copia del repo del motor al empaquetar. Asi hay UNA sola copia en
// disco y es imposible que la del APK se quede atras respecto de la que valida
// el fixture de la cresta.
//
// Va a un directorio GENERADO, no a src/main/assets, para que no aparezca
// nunca en el arbol de fuentes ni tiente a commitearlo.
val motorDir: String = (findProperty("peakid.motor.dir") as String?)
    ?: rootProject.layout.projectDirectory.dir("../peakid").asFile.absolutePath
val modeloOnnx = File(motorDir, "models/segformer_b0_ade.onnx")
val assetsGenerados = layout.buildDirectory.dir("generated/assets")

val copiarModelo = tasks.register<Copy>("copiarModeloOnnx") {
    // Falla, no avisa: un APK sin modelo compila, instala y arranca, y solo
    // falla al pulsar "Detectar". El detector es no negociable, asi que la
    // ausencia del modelo tiene que doler al construir, no al usar.
    doFirst {
        if (!modeloOnnx.isFile) {
            throw GradleException(
                "falta el modelo ${modeloOnnx.path}\n" +
                    "Es el SegFormer-B0 ADE20K validado, y no se versiona (15 MB).\n" +
                    "Apunta a otro sitio con -Ppeakid.motor.dir=<ruta al repo del motor>.",
            )
        }
    }
    from(modeloOnnx)
    into(assetsGenerados)
}

android.sourceSets.getByName("main").assets.directories.add(
    assetsGenerados.get().asFile.absolutePath,
)
tasks.named("preBuild") { dependsOn(copiarModelo) }

dependencies {
    implementation(project(":engine"))

    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")

    // Único lector de EXIF. La orientación se aplica UNA vez, en un solo sitio.
    implementation("androidx.exifinterface:exifinterface:1.4.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
