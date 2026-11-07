import org.gradle.api.tasks.testing.logging.TestExceptionFormat

// Núcleo en Kotlin puro: sin Android, para poder probarlo con JUnit sin emulador.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
    testLogging {
        events("failed")
        showStandardStreams = false
        // En CI interesa la traza completa, no solo el nombre de la prueba.
        exceptionFormat = TestExceptionFormat.FULL
    }
    // Resumen al final: sin él, el log de CI no dice cuántas pruebas se ejecutaron.
    afterSuite(
        KotlinClosure2<TestDescriptor, TestResult, Unit>({ suite, resultado ->
            if (suite.parent == null) {
                println(
                    "Pruebas del núcleo: ${resultado.testCount} ejecutadas, " +
                        "${resultado.successfulTestCount} bien, ${resultado.failedTestCount} fallidas, " +
                        "${resultado.skippedTestCount} saltadas",
                )
            }
        }),
    )
}
