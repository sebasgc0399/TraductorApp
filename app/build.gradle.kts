import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Firma del release: keystore.properties vive fuera de git (ver README). Sin él, el release se firma con la clave debug.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.isFile) keystorePropsFile.inputStream().use { load(it) }
}
// Rutas relativas a la raíz del proyecto; usar "/" también en Windows (la "\" se lee como escape).
val releaseStoreFile = keystoreProps.getProperty("storeFile")?.let { rootProject.file(it) }
if (keystorePropsFile.isFile && releaseStoreFile?.isFile != true) {
    throw GradleException("keystore.properties existe pero no se encuentra storeFile=${keystoreProps.getProperty("storeFile")}")
}
val hasReleaseKey = releaseStoreFile?.isFile == true

android {
    namespace = "com.sebas.traductorapp"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.sebas.traductorapp"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        // Los teléfonos con Android 12+ son arm64: evita empaquetar las librerías nativas de ML Kit de otras arquitecturas.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Sin minify: prima la estabilidad (casi todo el tamaño es de ML Kit).
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (hasReleaseKey) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

ksp {
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.webkit)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.mlkit.text.recognition)

    testImplementation(libs.junit)
}
