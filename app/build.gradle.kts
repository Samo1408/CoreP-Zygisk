import com.android.build.api.dsl.ApplicationExtension

plugins {
    alias(libs.plugins.android.application)
    }

configure<ApplicationExtension> {
    ndkVersion = providers.gradleProperty("ndkVersion").orElse("28.2.13676358").get()
    namespace = "org.lsposed.corepatch"
    compileSdk = 37

    val releaseSigningPropertyNames = listOf(
        "releaseStoreFile",
        "releaseStorePassword",
        "releaseKeyAlias",
        "releaseKeyPassword",
    )
    val hasReleaseSigningProperties =
        releaseSigningPropertyNames.all { providers.gradleProperty(it).isPresent }

    defaultConfig {
        applicationId = "org.lsposed.corepatch"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "2.0-zg"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigningProperties) {
                storeFile = rootProject.file(providers.gradleProperty("releaseStoreFile").get())
                storePassword = providers.gradleProperty("releaseStorePassword").get()
                keyAlias = providers.gradleProperty("releaseKeyAlias").get()
                keyPassword = providers.gradleProperty("releaseKeyPassword").get()
            }
        }
    }

    buildTypes {
        release {
            @Suppress("UnstableApiUsage")
            vcsInfo.include = false
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs["release"].takeIf { hasReleaseSigningProperties }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "**"
        }
        jniLibs {
            pickFirsts += setOf("**/libshadowhook.so", "**/libshadowhook_nothing.so")
        }
        dex {
            useLegacyPackaging = true
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
            version = "3.28.1"
        }
    }

    buildFeatures {
        buildConfig = true
        prefab = true
    }
}

val deleteAppMetadata = tasks.register("deleteAppMetadata") {
    val appMetadataFile =
        file("build/intermediates/app_metadata/release/writeReleaseAppMetadata/app-metadata.properties")
    doLast {
        appMetadataFile.writeText(
            ""
        )
    }
}

afterEvaluate {
    tasks.named("writeReleaseAppMetadata") {
        finalizedBy(deleteAppMetadata)
    }
}

dependencies {
    implementation("org.lsposed.lsplant:lsplant-standalone:6.4")
    implementation("com.bytedance.android:shadowhook:2.0.1")
}



val zygiskModuleZip by tasks.registering(Zip::class) {
    dependsOn("assembleRelease")
    archiveFileName.set("CorePatch-Zygisk-${project.version}.zip")
    destinationDirectory.set(layout.buildDirectory.dir("dist"))

    from(rootProject.file("module")) { into("") }

    doFirst {
        val staging = layout.buildDirectory.dir("zygisk-staging").get().asFile
        staging.deleteRecursively()
        val nativeOut = staging.resolve("zygisk")
        nativeOut.mkdirs()
        val privApk = staging.resolve("system/priv-app/CorePatch")
        privApk.mkdirs()

        val apk = layout.buildDirectory.file("outputs/apk/release/app-release.apk").get().asFile
        check(apk.isFile) { "Release APK not found: $apk" }
        apk.copyTo(privApk.resolve("CorePatch.apk"), overwrite = true)

        // If LSPlant/ShadowHook are delivered as shared prefab libraries, expose
        // those exact binaries through the Magisk system overlay so the Zygisk
        // library can resolve its DT_NEEDED dependencies inside system_server.
        java.util.zip.ZipFile(apk).use { zip ->
            zip.entries().asSequence()
                .filter { it.name.startsWith("lib/") && it.name.endsWith(".so") }
                .forEach { entry ->
                    val parts = entry.name.split("/")
                    if (parts.size != 3) return@forEach
                    val abi = parts[1]
                    val libDir = when (abi) {
                        "arm64-v8a", "x86_64", "riscv64" -> staging.resolve("system/lib64")
                        "armeabi-v7a", "x86" -> staging.resolve("system/lib")
                        else -> return@forEach
                    }
                    libDir.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        libDir.resolve(parts[2]).outputStream().use { output -> input.copyTo(output) }
                    }
                }
        }

        val cxxRoot = layout.buildDirectory.dir("intermediates").get().asFile
        val found = cxxRoot.walkTopDown()
            .filter { it.isFile && it.name == "libcorepatch_zygisk.so" }
            .toList()

        check(found.isNotEmpty()) {
            "libcorepatch_zygisk.so was not produced by CMake"
        }

        found.forEach { so ->
            val abi = so.parentFile.name
            so.copyTo(nativeOut.resolve("$abi.so"), overwrite = true)
        }
    }

    from(layout.buildDirectory.dir("zygisk-staging")) { into("") }
}
