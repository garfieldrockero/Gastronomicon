// Solo repositorios de Google y Maven Central, y cada uno limitado a lo que le
// toca servir: así una dependencia no puede colarse desde el repositorio que no es.
pluginManagement {
    repositories {
        // AndroidX y Android solo desde Google; si no está ahí, no se busca en Central.
        exclusiveContent {
            forRepository { google() }
            filter {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        // Último recurso y solo para los plugins de Kotlin, que también están en Maven
        // Central: sin este filtro, cualquier plugin podría llegar desde aquí.
        gradlePluginPortal {
            content {
                includeGroupByRegex("org\\.jetbrains\\.kotlin.*")
            }
        }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository { google() }
            filter {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("androidx.*")
            }
        }
        // El resto (incluido com.google.*) viene de Maven Central.
        mavenCentral()
    }
}

rootProject.name = "Gastronomicon"
include(":app")
include(":core")
