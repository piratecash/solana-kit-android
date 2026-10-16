import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("androidx.room")
    id("maven-publish")
}

// The Room 2.7.2 fixtures' expected contents, shared by the host, desktop and device tests.
val sharedFixtureDir = "src/test/sharedFixture"

room {
    schemaDirectory("$projectDir/schemas")
}

kotlin {
    androidTarget {
        publishLibraryVariants("release")
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    // 21, not 17: sqlcipher-room-jvm and sqlcipher-driver publish Java 21 bytecode only.
    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
    }

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        // Not commonMain: the kit is JVM code shared by the two JVM-backed targets only.
        val jvmCommonMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation(libs.sol4k)
                // com.solana.core.Account is part of the public Signer constructor.
                api(libs.solanakt)
                implementation(libs.kermit)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.okhttp)
                implementation(libs.okhttp.logging.interceptor)
                implementation(libs.retrofit)
                implementation(libs.adapter.rxjava2)
                implementation(libs.converter.gson)
                implementation(libs.converter.scalars)
                implementation(libs.gson)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.coroutines.rx2)
                implementation(libs.room.runtime)
            }
        }
        androidMain {
            // Sources come from AGP's own `main` source set; adding them here too would
            // list the same file in two fragments.
            dependsOn(jvmCommonMain)
            dependencies {
                implementation(libs.room.ktx)
                implementation(libs.kotlinx.coroutines.android)
                // Public API exposes its exceptions and DatabaseMigrationResult.
                api(libs.sqlcipher.room)
            }
        }
        val desktopMain by getting {
            kotlin.srcDir("src/main/java")
            dependsOn(jvmCommonMain)
            dependencies {
                api(libs.sqlcipher.room)
            }
        }

        val androidUnitTest by getting {
            kotlin.srcDir(sharedFixtureDir)
            dependencies {
                implementation(libs.junit)
                implementation(libs.mockk)
            }
        }
        // Runs on a device only; never part of the published AAR.
        val androidInstrumentedTest by getting {
            kotlin.srcDir(sharedFixtureDir)
            dependencies {
                implementation(libs.androidx.test.junit)
                implementation(libs.androidx.test.espresso.core)
            }
        }
        val desktopTest by getting {
            kotlin.srcDir(sharedFixtureDir)
            resources.srcDir("src/test/resources")
            dependencies {
                implementation(libs.junit)
                implementation(libs.mockwebserver)
                // Writes the plaintext pre-migration files the schema-policy tests open.
                implementation(libs.sqlite.bundled)
                // Reads and stages encrypted files directly; aligned with sqlcipher-room.
                implementation(libs.sqlcipher.driver)
            }
        }
    }
}

dependencies {
    add("kspAndroid", libs.room.compiler)
    add("kspDesktop", libs.room.compiler)
    // KMP naming trap: kspAndroidTest is the JVM unit-test source set.
    add("kspAndroidTest", libs.room.compiler)
}

android {
    namespace = "io.horizontalsystems.solanakit"
    compileSdk = 35

    sourceSets {
        // The Room 2.7.2 fixtures; read-only, shared with the host tests.
        getByName("androidTest").resources.srcDir("src/test/resources")
    }

    defaultConfig {
        minSdk = 26

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        targetSdk = 35
    }
    testOptions {
        targetSdk = 35
    }
}

val bcprovVersionsToRedirect = listOf(
    "bcprov-jdk15on:1.65",
    "bcprov-jdk15on:1.65.01",
    "bcprov-jdk15on:1.66",
    "bcprov-jdk15on:1.70",
    "bcprov-jdk15to18:1.65",
    "bcprov-jdk15to18:1.65.01",
    "bcprov-jdk15to18:1.68",
    "bcprov-jdk15to18:1.69",
)

configurations.all {
    resolutionStrategy.dependencySubstitution {
        bcprovVersionsToRedirect.forEach { moduleId ->
            val (name, version) = moduleId.split(":")
            substitute(module("org.bouncycastle:$name:$version"))
                .using(module("org.bouncycastle:bcprov-jdk15to18:1.80"))
        }
    }
}

// AGP's "test" lifecycle task only aggregates Android unit tests; wire in the desktop target too.
afterEvaluate {
    tasks.named("test") {
        dependsOn("desktopTest")
    }
}
