@file:Suppress("UnstableApiUsage")

version = 251218

cloudstream {
    description = "Videos, playlists and channels from YouTube"
    authors = listOf("kreastream")

    status = 1

    tvTypes = listOf("Others")

    requiresResources = true

    iconUrl = "https://www.youtube.com/s/desktop/711fd789/img/logos/favicon_144x144.png"
}

android {
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.lagradost:cloudstream3:latest-version")
    implementation("org.schabi.newpipe:extractor:0.28.0")
    implementation("com.squareup.okhttp3:okhttp:4.11.0")
    //noinspection GradleDependency
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.0.4")
}
