pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/TelegramMessenger/telegram-login-android")
            content { includeModule("org.telegram", "login-sdk") }
            credentials {
                username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("TELEGRAM_PACKAGES_USER")
                password = providers.gradleProperty("gpr.key").orNull ?: System.getenv("TELEGRAM_PACKAGES_TOKEN")
            }
        }
    }
}

rootProject.name = "MurphAndroid"
include(":app")
