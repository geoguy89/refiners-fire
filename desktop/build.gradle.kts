import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
}

compose.desktop {
    application {
        mainClass = "com.geoguy89.refinersfire.MainKt"
        jvmArgs += listOf(
            "-Xmx512m",
            // Shown in Options. CI sets the build number.
            "-Drefiners.version=2.6.1",
            "-Drefiners.build=${System.getenv("REFINERS_BUILD_NUMBER") ?: "0"}",
        )

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Dmg, TargetFormat.Deb)
            // The upgradeUuid below is unchanged, so installs of the old name upgrade in place.
            packageName = "Refiners Fire"
            packageVersion = "2.6.1"
            description = "Refiner's Fire: burn away the dross"
            vendor = "geoguy89"
            copyright = "Fonts under the SIL OFL. Scripture from the NLT, Tyndale House Foundation."
            modules("java.desktop", "java.logging", "jdk.unsupported")

            windows {
                menu = true
                menuGroup = "Refiner's Fire"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "6f1c2a5e-4d0b-4b8e-9a51-3c7e1f0a9b62"
                iconFile.set(project.file("icons/refinersfire.ico"))
            }
            linux {
                iconFile.set(project.file("icons/refinersfire.png"))
            }
        }
    }
}
