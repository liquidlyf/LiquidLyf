import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Makes the app's Baseline Profile (A32-5, 17 Nov 2026): the code that runs at startup and while
// scrolling Home and Requests, compiled ahead of time so the first screens open faster.
// It runs only in CI (.github/workflows/baseline-profile.yml), on a GitHub-hosted Android 14
// emulator against the Firebase emulators: making a profile needs Android 13+ or a rooted
// phone, and never runs on a maintainer's PC. See README.md.
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "io.github.liquidlyf.baselineprofile"
    compileSdk = 37

    defaultConfig {
        // Baseline Profiles can be collected from Android 9 (API 28) up; we use 34 in CI.
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"

    // The same flavors as :app, so each app variant has its matching generator. Only demo can
    // sign in without a real Firebase project, so CI runs demo (README.md).
    flavorDimensions += "env"
    productFlavors {
        create("demo") { dimension = "env" }
        create("dev") { dimension = "env" }
        create("prod") { dimension = "env" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

androidComponents {
    // The same variants as :app (its build.gradle.kts): no prod debug, and no dev or prod
    // without their git-ignored Firebase config.
    beforeVariants { variant ->
        val flavor = variant.productFlavors.firstOrNull()?.second
        if (flavor == "prod" && variant.buildType == "debug") variant.enable = false
        if ((flavor == "dev" || flavor == "prod") && !rootProject.file("app/src/$flavor/google-services.json").exists()) {
            variant.enable = false
        }
    }
    // The generator needs the app's ID (".demo" for demo): read it from the APK that was built.
    onVariants { variant ->
        val loader = variant.artifacts.getBuiltArtifactsLoader()
        variant.instrumentationRunnerArguments.put("targetAppId", variant.testedApks.map { loader.load(it)?.applicationId ?: "" })
    }
}

baselineProfile {
    // CI's emulator, started by the workflow. No Gradle-managed devices: they'd download a
    // system image on every PC that builds the project.
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
