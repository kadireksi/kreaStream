version = 251226

cloudstream {

    description = "Live streams from the Free TV github repository. Forked from the Free-TV plugin in the ItalianProvider repo"
    authors = listOf("kreastream")
    status = 1
    tvTypes = listOf("Live")
    requiresResources = true
    iconUrl = "https://raw.githubusercontent.com/doGior/doGiorsHadEnough/refs/heads/master/TV/television.png"
}

android {
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}

dependencies {
    implementation("com.google.android.material:material:1.12.0")
}
