import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Temple Scan"
    versionCode = 77
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"
    theme = "catharsis"

    source {
        lang = "es"
        baseUrl {
            custom("https://aedexnox.com")
        }
        id = 3451196845457607700L
    }
}
