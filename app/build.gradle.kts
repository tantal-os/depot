import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "app.tantal.depot"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }
    defaultConfig {
        applicationId = "app.tantal.depot"
        val catalogKey = providers.gradleProperty("depotCatalogKey").orElse("").get()
        if (catalogKey.isNotEmpty()) {
            val der = Base64.getDecoder().decode(catalogKey)
            KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(der))
        }
        buildConfigField("String", "CATALOG_KEY", "\"$catalogKey\"")
        minSdk = 36
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }
}

dependencies {
    implementation(libs.activity.compose)
    implementation(libs.compose.material3)
    testImplementation(libs.junit)
}
