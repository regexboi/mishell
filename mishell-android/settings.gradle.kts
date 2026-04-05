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
    }
}

rootProject.name = "mishell"
include(":app")
include(
    ":fluid-markdown",
    ":markwon-core",
    ":markwon-ext-latex",
    ":markwon-ext-strikethrough",
    ":markwon-ext-tables",
    ":markwon-ext-tasklist",
    ":markwon-html",
    ":markwon-image",
    ":markwon-inline-parser",
    ":markwon-syntax-highlight"
)

project(":fluid-markdown").projectDir = file("third_party/fluidmarkdown/fluid-markdown")
project(":markwon-core").projectDir = file("third_party/fluidmarkdown/markwon-core")
project(":markwon-ext-latex").projectDir = file("third_party/fluidmarkdown/markwon-ext-latex")
project(":markwon-ext-strikethrough").projectDir =
    file("third_party/fluidmarkdown/markwon-ext-strikethrough")
project(":markwon-ext-tables").projectDir = file("third_party/fluidmarkdown/markwon-ext-tables")
project(":markwon-ext-tasklist").projectDir = file("third_party/fluidmarkdown/markwon-ext-tasklist")
project(":markwon-html").projectDir = file("third_party/fluidmarkdown/markwon-html")
project(":markwon-image").projectDir = file("third_party/fluidmarkdown/markwon-image")
project(":markwon-inline-parser").projectDir = file("third_party/fluidmarkdown/markwon-inline-parser")
project(":markwon-syntax-highlight").projectDir =
    file("third_party/fluidmarkdown/markwon-syntax-highlight")
