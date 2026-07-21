plugins {
    // AGP 9.x has built-in Kotlin support — the org.jetbrains.kotlin.android plugin must NOT be
    // applied (it errors out). The `kotlin { compilerOptions { } }` DSL below is provided by AGP.
    id("com.android.application")
}

android {
    namespace = "de.lobianco.saftssh.linux"
    compileSdk = 37

    defaultConfig {
        applicationId = "de.lobianco.saftssh.linux"
        minSdk = 26
        targetSdk = 37
        versionCode =  14
        versionName = "1.14"

        externalNativeBuild {
            cmake {
                cppFlags("")
            }
        }
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    // Two flavors with DIFFERENT applicationIds so both can be installed side by side:
    //  - standard: de.lobianco.saftssh.linux       (proot, no root, Play-Store-safe)
    //  - root:     de.lobianco.saftssh.linux.root  (real root chroot, GitHub-only)
    // The main app binds whichever a connection selects. Bind authorization is a caller-package
    // check in LinuxSessionService.onBind, not a custom Android permission (see its doc for why —
    // a "dangerous" custom permission's grant dialog renders as a generic, alarming "perform an
    // unknown action" on modern Android, regardless of our own android:label).
    // The bind-action string stays identical across flavors (main app disambiguates by setPackage).
    flavorDimensions += "root"
    productFlavors {
        create("standard") {
            dimension = "root"
            buildConfigField("boolean", "SUPPORTS_ROOT_CONTAINERS", "false")
            manifestPlaceholders["pluginLabel"] = "LobiShell Linux Plugin"
        }
        create("root") {
            dimension = "root"
            applicationIdSuffix = ".root"
            buildConfigField("boolean", "SUPPORTS_ROOT_CONTAINERS", "true")
            versionNameSuffix = "-root"
            manifestPlaceholders["pluginLabel"] = "LobiShell Linux Plugin (Root)"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    packaging {
        jniLibs {
            // Extract the bundled native helpers (libproot.so / libproot-loader.so) to the app's
            // nativeLibraryDir as real files — that is the only app directory the SELinux policy lets
            // us execute from (app data dirs are noexec on API 29+). Required for proot to run.
            useLegacyPackaging = true
        }
    }
}

// AGP 9.x / Kotlin 2.x: set the JVM target via the Kotlin compilerOptions DSL (kotlinOptions removed).
kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    // For .tar.xz / .tar.gz rootfs extraction (proot-distro Debian rootfs).
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")
}
