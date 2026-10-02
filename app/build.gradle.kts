plugins {
    id("com.android.application")
}

android {
    namespace = "com.string.iptv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.string.iptv"
        minSdk = 29
        targetSdk = 36
        val appVersion = providers.gradleProperty("appVersion").orElse("0.2.1").get()
        require(appVersion.matches(Regex("\\d+\\.\\d+\\.\\d+")))
        val parts = appVersion.split('.').map(String::toInt)
        require(parts[0] in 0..2000 && parts[1] in 0..999 && parts[2] in 0..999)
        versionCode = parts[0] * 1_000_000 + parts[1] * 1_000 + parts[2]
        versionName = appVersion
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
        buildConfigField("String", "TEST_PLAYLIST_BASE_URL", "\"\"")
        buildConfigField("String", "TEST_UPDATE_BASE_URL", "\"\"")
    }
    val signingFile = providers.environmentVariable("IPTV_STORE_FILE").orNull
    if (signingFile != null) {
        signingConfigs.create("distribution") {
            storeFile = file(signingFile)
            storePassword = providers.environmentVariable("IPTV_STORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("IPTV_KEY_ALIAS").get()
            keyPassword = providers.environmentVariable("IPTV_KEY_PASSWORD").get()
        }
    }
    buildTypes {
        getByName("debug") {
            val fixtureUrl = providers.gradleProperty("testPlaylistBaseUrl").orElse("").get()
            require(fixtureUrl.isEmpty() || fixtureUrl.matches(Regex("https?://[a-zA-Z0-9.:-]+")))
            buildConfigField("String", "TEST_PLAYLIST_BASE_URL", "\"$fixtureUrl\"")
            val updateUrl = providers.gradleProperty("testUpdateBaseUrl").orElse("").get()
            require(updateUrl.isEmpty() || updateUrl.matches(Regex("https?://[a-zA-Z0-9.:-]+")))
            buildConfigField("String", "TEST_UPDATE_BASE_URL", "\"$updateUrl\"")
        }
        getByName("release") { if (signingFile != null) signingConfig = signingConfigs.getByName("distribution") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { buildConfig = true }
    lint { abortOnError = true }
}

dependencies {
    val media3 = "1.11.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.core:core:1.17.0")
    testImplementation("junit:junit:4.13.2")
}
