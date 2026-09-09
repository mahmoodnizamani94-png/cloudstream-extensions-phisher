import org.jetbrains.kotlin.konan.properties.Properties

version = 15

android {
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
    defaultConfig {
        val properties = Properties()
        val localProp = project.rootProject.file("local.properties")
        if (localProp.exists()) {
            properties.load(localProp.inputStream())
        }
        val tmdbApiKey = properties.getProperty("TMDB_API")?.takeIf { it.isNotBlank() && it != "null" }
            ?: "1865f43a0549ca50d341dd9ab8b29f49"
        android.buildFeatures.buildConfig=true
        buildConfigField("String", "TMDB_API", "\"$tmdbApiKey\"")
    }
}

cloudstream {
    language = "en"

     description = "[!] Requires Setup \n- Allows you to use any Stremio addon by pasting their manifest.json url"
     authors = listOf("Hexated,phisher98,erynith")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "TvSeries",
        "Movie",
        "Torrent"
    )
    requiresResources = true
    iconUrl = "https://files.catbox.moe/ol63rm.png"
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    val cloudstream by configurations
    cloudstream("com.lagradost:cloudstream3:pre-release")
}
