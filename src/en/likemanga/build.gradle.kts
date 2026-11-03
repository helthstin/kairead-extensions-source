import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "LikeManga"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl = "https://mgread.io"
    }

    deeplink {
        host("likemanga.io")
        host("mgread.io")
        path("/..*")
    }
}
