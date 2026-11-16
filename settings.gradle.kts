pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "Hearthline"
include(":app")

include(":sendspin-shared", ":sendspin-player")
project(":sendspin-shared").projectDir = file("third-party/sendspin/shared")
project(":sendspin-player").projectDir = file("third-party/sendspin/player")
