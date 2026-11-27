import java.util.Properties

plugins {
    id("com.android.application")
}

// Podpis wydania (wydawca: KlimasTech). Dane klucza NIE sa w repozytorium:
// plik keystore.properties (storeFile, storePassword, keyAlias, keyPassword)
// wskazuje sie zmienna srodowiskowa KLIMASTECH_KEYSTORE_PROPS albo kladzie
// jako android/keystore.properties (jest w .gitignore).
val keystorePropsFile: File? = (System.getenv("KLIMASTECH_KEYSTORE_PROPS")?.let { File(it) }
    ?: rootProject.file("keystore.properties")).takeIf { it.exists() }
val keystoreProps = Properties().apply { keystorePropsFile?.inputStream()?.use { load(it) } }

android {
    namespace = "org.eu.klimastech.denon"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "org.eu.klimastech.denon"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (keystorePropsFile != null) {
            create("klimastech") {
                storeFile = File(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            if (keystorePropsFile != null) {
                signingConfig = signingConfigs.getByName("klimastech")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
