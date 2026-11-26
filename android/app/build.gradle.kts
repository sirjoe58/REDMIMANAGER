plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "it.sirjoe.redmimanager"
    compileSdk = 35

    defaultConfig {
        applicationId = "it.sirjoe.redmimanager"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "1.0.4"
    }

    // Firma: la chiave NON e' nel repository. Su GitHub viene letta dai Secrets
    // (ANDROID_KEYSTORE_B64 / ANDROID_KEYSTORE_PASSWORD). Senza, si usa la chiave di debug.
    val ksPath = System.getenv("ANDROID_KEYSTORE_FILE")
    val ksPass = System.getenv("ANDROID_KEYSTORE_PASSWORD")
    val hasKey = !ksPath.isNullOrBlank() && file(ksPath).exists() && !ksPass.isNullOrBlank()
    signingConfigs {
        if (hasKey) {
            create("rilascio") {
                storeFile = file(ksPath!!)
                storePassword = ksPass
                keyAlias = "redmimanager"
                keyPassword = ksPass
            }
        }
    }

    buildTypes {
        debug {
            if (hasKey) signingConfig = signingConfigs.getByName("rilascio")
        }
        release {
            isMinifyEnabled = false
            if (hasKey) signingConfig = signingConfigs.getByName("rilascio")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*",
                "META-INF/*.md", "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/versions/**/module-info.class", "module-info.class"
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.security:security-crypto:1.0.0")
    // NAS (SMB / cartelle di rete Windows), come MailAllegati
    implementation("eu.agno3.jcifs:jcifs-ng:2.1.10")
    // lettura del codice QR mostrato dal programma del PC
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
