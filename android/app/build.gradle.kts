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

// Pliki strony (app/static) sa wbudowywane w APK jako assets/www; przy pierwszym
// uruchomieniu aplikacja kopiuje je do pamieci wewnetrznej. Jedno zrodlo prawdy -
// katalog app/static serwera (bez service-worker.js: w aplikacji niepotrzebny).
val kopiujStrone = tasks.register<Copy>("kopiujStrone") {
    from(rootProject.file("../app/static")) { exclude("service-worker.js") }
    into(layout.projectDirectory.dir("src/main/assets/www"))
}
tasks.named("preBuild") { dependsOn(kopiujStrone) }

android {
    namespace = "org.eu.klimastech.denon"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "org.eu.klimastech.denon"
        minSdk = 24
        targetSdk = 37
        versionCode = 2
        versionName = "1.1"
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
