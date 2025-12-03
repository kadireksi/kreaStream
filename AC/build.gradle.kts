version = 1
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

dependencies {
    implementation("com.lagradost:cloudstream-meta:1.4.1")
}

cloudstream {
    authors     = listOf("kreastream")
    language    = "tr"
    description = "ABdullah Çiftçi"
    status  = 1
    tvTypes = listOf("TvSeries", "Live")
    iconUrl = "https://iatv.tmgrup.com.tr/site/v2/i/atv-logo.png"
}

