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
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
        buildConfigField("String", "TEST_PLAYLIST_BASE_URL", "\"\"")
    }
    buildTypes {
        getByName("debug") {
            val fixtureUrl = providers.gradleProperty("testPlaylistBaseUrl").orElse("").get()
            require(fixtureUrl.isEmpty() || fixtureUrl.matches(Regex("https?://[a-zA-Z0-9.:-]+")))
            buildConfigField("String", "TEST_PLAYLIST_BASE_URL", "\"$fixtureUrl\"")
        }
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
    testImplementation("junit:junit:4.13.2")
}
