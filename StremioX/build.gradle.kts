import org.jetbrains.kotlin.konan.properties.Properties

// use an integer for version numbers
version = 26

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

dependencies {
    implementation("com.google.android.material:material:1.13.0")
}

cloudstream {
    language = "en"
    // All of these properties are optional, you can safely remove them

     description = "[!] Requires Setup \n- StremioX allows you to use stream addons \n- StremioC allows you to use catalog addons"
     authors = listOf("Hexated,phisher98")

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
    )
    requiresResources = true
    iconUrl = "https://raw.githubusercontent.com/hexated/cloudstream-extensions-hexated/master/StremioX/icon.png"
}
