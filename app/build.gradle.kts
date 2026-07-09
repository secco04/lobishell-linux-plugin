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
        versionCode =  9
        versionName = "1.9"

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
    // The main app binds whichever a connection selects. Because the plugin DEFINES a custom
    // permission and two installed apps may NOT define the same permission name (Android rejects
    // the 2nd install with INSTALL_FAILED_DUPLICATE_PERMISSION), the permission name is
    // flavor-specific via the ${pluginPermission} manifest placeholder; the main app holds both.
    // The bind-action string stays identical across flavors (main app disambiguates by setPackage).
    flavorDimensions += "root"
    productFlavors {
        create("standard") {
            dimension = "root"
            buildConfigField("boolean", "SUPPORTS_ROOT_CONTAINERS", "false")
            manifestPlaceholders["pluginPermission"] = "de.lobianco.saftssh.linux.READ_BINARY"
            manifestPlaceholders["pluginLabel"] = "LobiShell Linux Plugin"
            manifestPlaceholders["pluginPermLabel"] = "Access LobiShell Linux Plugin"
        }
        create("root") {
            dimension = "root"
            applicationIdSuffix = ".root"
            buildConfigField("boolean", "SUPPORTS_ROOT_CONTAINERS", "true")
            versionNameSuffix = "-root"
            manifestPlaceholders["pluginPermission"] = "de.lobianco.saftssh.linux.root.READ_BINARY"
            manifestPlaceholders["pluginLabel"] = "LobiShell Linux Plugin (Root)"
            manifestPlaceholders["pluginPermLabel"] = "Access LobiShell Linux Plugin (Root)"
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
