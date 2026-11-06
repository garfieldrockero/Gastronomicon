// Solo repositorios de Google y Maven Central, y cada uno limitado a lo que le
// toca servir: así una dependencia no puede colarse desde el repositorio que no es.
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
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
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "Gastronomicon"
include(":app")
include(":core")
