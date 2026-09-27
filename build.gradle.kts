plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}

// Optional local output directory for machines where sync software locks build artifacts.
providers.gradleProperty("weather.externalBuildRoot").orNull?.let { outputRoot ->
    allprojects {
        layout.buildDirectory.set(file("$outputRoot/${project.name}"))
    }
}
