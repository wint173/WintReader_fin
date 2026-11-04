import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// 署名の鍵（GitHub と PC で同じ鍵を使い、どちらで作った APK でも上書きで更新できるようにする）
// PC：プロジェクト直下の keystore.properties（Git には入れない）
// GitHub Actions：環境変数 SIGNING_STORE_FILE / SIGNING_STORE_PASSWORD / SIGNING_KEY_ALIAS / SIGNING_KEY_PASSWORD
val signingProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(key: String, env: String): String? =
    signingProps.getProperty(key)?.takeIf { it.isNotBlank() } ?: System.getenv(env)?.takeIf { it.isNotBlank() }

// GitHub Actions の実行番号（ないときは PC でのビルド）
val ciRun: Int? = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()

android {
    namespace = "com.example.wintreader"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.wintreader"
        minSdk = 29
        targetSdk = 37
        // GitHub で作るたびに番号を上げる（上書き更新のため）。PC でのビルドは 1
        versionCode = ciRun?.plus(1) ?: 1
        versionName = if (ciRun != null) "1.0.$ciRun" else "1.0-dev"

        // アプリ内のアップデート確認に使う GitHub のリポジトリ（「ユーザー名/リポジトリ名」）。
        // GitHub Actions では自動で入る。PC でのビルドでは空（アップデート確認は出さない）
        buildConfigField("String", "GITHUB_REPO", "\"${System.getenv("GITHUB_REPOSITORY") ?: ""}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("wint") {
            val store = signingValue("storeFile", "SIGNING_STORE_FILE")
            if (store != null) {
                storeFile = rootProject.file(store)
                storePassword = signingValue("storePassword", "SIGNING_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "SIGNING_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        // 配る APK は debug の種類で作る（release の最適化はまだ実機で確かめていないため）。
        // 鍵があればそれで署名する（なければ Android の標準のデバッグ鍵）
        debug {
            val wint = signingConfigs.getByName("wint")
            if (wint.storeFile != null) signingConfig = wint
        }
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.webkit)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.jsoup)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}