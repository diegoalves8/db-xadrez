plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.diego.xadrez"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.diego.xadrez"
        minSdk = 24
        targetSdk = 34
        // Cada build no GitHub gera uma versão maior, para atualizar sem desinstalar
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
    }

    signingConfigs {
        create("pessoal") {
            storeFile = file("xadrez.keystore")
            storePassword = "xadrez123"
            keyAlias = "xadrez"
            keyPassword = "xadrez123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("pessoal")
        }
    }

    lint {
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
