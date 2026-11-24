import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Temple Scan"
    versionCode = 15
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"
    theme = "madara"

    source {
        lang = "es"
        baseUrl {
            custom("https://aedexnox.com")
        }
        versionId = 4
    }
}
