plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.example.handgame"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.talalqasim.handgaza"
        minSdk = 24
        targetSdk = 36
        // رقم الإصدار يزيد تلقائياً مع كل بناء على جيت هب (المتاجر ترفض رقماً مكرراً)
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0"
    }
    // توقيع نسخة النشر: يُقرأ من أسرار جيت هب، وإن لم توجد يبقى البناء التجريبي يعمل
    val ksPath = System.getenv("KEYSTORE_PATH")
    signingConfigs {
        if (!ksPath.isNullOrEmpty() && File(ksPath).exists()) {
            create("release") {
                storeFile = File(ksPath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("com.google.android.gms:play-services-ads:23.3.0")
}
