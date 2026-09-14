rootProject.name = "campus-parser-template"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()

        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/campus-mobile/campus-parser-kotlin-sdk")
            credentials {
                username = "TOKEN"
                password = providers.gradleProperty("github.token").orNull ?: System.getenv("GPR_TOKEN")
            }
        }
    }
}

include(":app")
