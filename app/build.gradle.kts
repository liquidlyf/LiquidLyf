import com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
}

// Blood compatibility and fan-out test vectors, shared with firebase/ and backend/worker/.
val sharedVectorsDir: File = rootDir.resolve("backend/shared")

// The Firestore DTO tests read the allowed field names straight from the rules.
val firestoreRulesFile: File = rootDir.resolve("firebase/firestore.rules")

android {
    namespace = "io.github.liquidlyf"
    compileSdk = 37

    defaultConfig {
        // D9: never change this once people have installed the app.
        applicationId = "io.github.liquidlyf"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0-alpha01"

        // Starts HiltTestApplication, so instrumented tests can use Hilt (src/androidTest).
        testInstrumentationRunner = "io.github.liquidlyf.HiltTestRunner"

        val emulatorHost = providers.gradleProperty("liquidlyf.emulatorHost").getOrElse("127.0.0.1")
        buildConfigField("String", "EMULATOR_HOST", "\"$emulatorHost\"")
    }

    // Three environments (doc 11 §2). A contributor's build has a different applicationId,
    // so it can never reach the production Firebase project.
    flavorDimensions += "env"
    productFlavors {
        create("demo") {
            // Contributors' default: Firebase Emulator Suite only (project demo-liquidlyf),
            // with the committed dummy config in src/demo/google-services.json.
            dimension = "env"
            isDefault = true
            applicationIdSuffix = ".demo"
            buildConfigField("boolean", "USE_FIREBASE_EMULATOR", "true")
            manifestPlaceholders["crashlyticsCollectionEnabled"] = "false"
            // No FCM on the emulators: don't fetch a push token with the dummy config.
            manifestPlaceholders["fcmAutoInitEnabled"] = "false"
        }
        create("dev") {
            // Maintainers' free Spark project liquidlyf-dev. Config is git-ignored:
            // src/dev/google-services.json
            dimension = "env"
            applicationIdSuffix = ".dev"
            buildConfigField("boolean", "USE_FIREBASE_EMULATOR", "false")
            manifestPlaceholders["crashlyticsCollectionEnabled"] = "false"
            manifestPlaceholders["fcmAutoInitEnabled"] = "true"
        }
        create("prod") {
            // Release only. CI writes src/prod/google-services.json.
            dimension = "env"
            buildConfigField("boolean", "USE_FIREBASE_EMULATOR", "false")
            manifestPlaceholders["crashlyticsCollectionEnabled"] = "true"
            manifestPlaceholders["fcmAutoInitEnabled"] = "true"
        }
    }

    buildTypes {
        debug {
            // createDemoDebugUnitTestCoverageReport: Phase 2 asks for >= 90% line coverage
            // on core/domain.
            enableUnitTestCoverage = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Local release builds are signed with the debug key, whose SHA-1 is registered in
            // Firebase, so a prod build can be installed on the test phone (Google sign-in, the
            // live dispatch test). There are no prod debug builds. The release job signs with
            // the real key (Phase 9/10) and sets LIQUIDLYF_RELEASE.
            if (!providers.environmentVariable("LIQUIDLYF_RELEASE").isPresent) {
                signingConfig = signingConfigs.getByName("debug")
            }
            // Only the real release job uploads R8 mappings. PR CI builds prodRelease with a
            // dummy config, where an upload would fail.
            configure<CrashlyticsExtension> {
                mappingFileUploadEnabled = providers.environmentVariable("LIQUIDLYF_RELEASE").isPresent
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // java.time (Instant, Duration) on Android 7.x, which predates it.
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        // English and Bangla only: drops the other languages that libraries bring in.
        localeFilters += listOf("en", "bn")
    }

    lint {
        abortOnError = true
        // English strings land before their Bangla translation; the release job checks
        // Bangla coverage instead (doc 11 §6).
        warning += "MissingTranslation"
        // Digital Asset Links share saved passwords between the app and a website login.
        // liquidlyf.github.io has no login, and saving passwords in the app works without them.
        disable += "CredManMissingDal"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // AGP's Compose Preview tasks need merged resources in unit tests.
        unitTests.isIncludeAndroidResources = true
        // The domain tests check the app against the test vectors it shares with
        // firestore.rules and the Worker, so all three stay in step.
        unitTests.all { test ->
            test.systemProperty("liquidlyf.sharedDir", sharedVectorsDir.absolutePath)
            test.inputs.dir(sharedVectorsDir)
                .withPropertyName("sharedVectors")
                .withPathSensitivity(PathSensitivity.RELATIVE)
            // BdGeoTest checks the bundled area list itself.
            test.inputs.file("src/main/assets/bd-geo.json")
                .withPropertyName("bdGeo")
                .withPathSensitivity(PathSensitivity.RELATIVE)
            test.systemProperty("liquidlyf.rulesFile", firestoreRulesFile.absolutePath)
            test.inputs.file(firestoreRulesFile)
                .withPropertyName("firestoreRules")
                .withPathSensitivity(PathSensitivity.RELATIVE)
        }
    }

    // In-memory fakes shared by the unit tests and the on-phone Compose tests.
    sourceSets {
        getByName("test").kotlin.directories.add("src/sharedTest/java")
        getByName("androidTest").kotlin.directories.add("src/sharedTest/java")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // .proto sources from Firestore's gRPC dependencies; protobuf-lite never reads them.
            excludes += "**/*.proto"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

androidComponents {
    beforeVariants { variant ->
        val flavor = variant.productFlavors.firstOrNull()?.second
        when {
            // No prod debug builds: a debug build can never use production.
            flavor == "prod" && variant.buildType == "debug" -> variant.enable = false
            // dev and prod need their own (git-ignored) Firebase config. Without it, skip the
            // variant instead of failing the whole build.
            flavor == "dev" || flavor == "prod" -> {
                if (!file("src/$flavor/google-services.json").exists()) {
                    logger.info("LiquidLyf: src/$flavor/google-services.json not found, skipping ${variant.name}")
                    variant.enable = false
                }
            }
        }
    }
}

dependencies {
    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Navigation and lifecycle
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)

    // App support
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.messaging)
    implementation(libs.firebase.config)
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.appcheck.playintegrity)
    debugImplementation(libs.firebase.appcheck.debug)

    // Unit tests
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    testImplementation(libs.okhttp.mockwebserver)

    // Instrumented tests (run on the USB phone)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
}
