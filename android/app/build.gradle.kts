import java.util.Properties

plugins {
    id("com.android.application")
}

// Eigener Release-Schlüssel liegt außerhalb des Projekts (siehe signing.properties.example)
val signingFile = file(System.getenv("VEKTORWERK_SIGNING")
    ?: "${System.getProperty("user.home")}/.config/vektorwerk/signing.properties")
val releaseSigning = Properties().apply { if (signingFile.exists()) signingFile.inputStream().use { load(it) } }

kotlin {
    jvmToolchain(21)
}

// Gemeinsame Weboberfläche aus der PC-App (eine Quelle für beide Versionen)
val webStatic = rootDir.resolve(providers.gradleProperty("vektorwerk.static").get())

android {
    namespace = "de.vektorwerk.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "de.vektorwerk.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        ndk {
            // Moderne Handys; hält die APK klein (OpenCV bringt pro Architektur ~10 MB mit)
            abiFilters += (findProperty("vektorwerk.abis") as String? ?: "arm64-v8a").split(",")
        }
    }

    signingConfigs {
        if (releaseSigning.isNotEmpty()) create("release") {
            storeFile = file(releaseSigning.getProperty("storeFile"))
            storePassword = releaseSigning.getProperty("storePassword")
            keyAlias = releaseSigning.getProperty("keyAlias")
            keyPassword = releaseSigning.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Mit eigenem Schlüssel, falls vorhanden – sonst Debug-Schlüssel (nur für den Eigengebrauch)
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    sourceSets {
        getByName("main") {
            assets.directories.add(webStatic.path)
        }
    }

    packaging {
        jniLibs { useLegacyPackaging = true } // komprimiert -> kleinere APK
    }
}

dependencies {
    implementation("org.opencv:opencv:4.12.0")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    testImplementation("junit:junit:4.13.2")
}
