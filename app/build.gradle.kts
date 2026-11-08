import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Desde AGP 9 el soporte de Kotlin viene integrado en el plugin de Android: ya no se
// aplica `org.jetbrains.kotlin.android` (con AGP 9 aplicarlo da error).
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Clave de firma fija, que CI saca de los secretos del repositorio. Android no deja
// actualizar una app firmada con otra clave: habría que desinstalar, y al desinstalar
// se pierden los ajustes y el token. Si los secretos aún no existen, se firma con la
// clave de depuración para que la compilación siga funcionando.
val rutaClaveFirma: String? = providers.environmentVariable("FIRMA_KEYSTORE_PATH").orNull

android {
    namespace = "io.github.garfieldrockero.gastronomicon"
    // Las bibliotecas de AndroidX actuales exigen compilar contra la API 37. targetSdk se
    // queda en 36: compilar contra una API nueva no cambia cómo se comporta la app.
    compileSdk = 37
    // Fijada a propósito: es la que AGP 9.4 usa por defecto. Los scripts de CI leen este
    // mismo valor para usar siempre las mismas herramientas de build-tools, en vez de
    // la más alta que traiga el runner ese día.
    buildToolsVersion = "36.0.0"

    defaultConfig {
        // No cambiar nunca: es la identidad de la app en el móvil. Si cambia, se instala
        // como otra app distinta y no ve los datos de la anterior.
        applicationId = "io.github.garfieldrockero.gastronomicon"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        rutaClaveFirma?.let { ruta ->
            create("fija") {
                storeFile = file(ruta)
                storePassword = providers.environmentVariable("FIRMA_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("FIRMA_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("FIRMA_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (rutaClaveFirma != null) "fija" else "debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
}
