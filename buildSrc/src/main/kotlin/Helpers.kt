/******************************************************************************
 *                                                                            *
 * Copyright (C) 2021 by nekohasekai <contact-sagernet@sekai.icu>             *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 * the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                       *
 *                                                                            *
 * This program is distributed in the hope that it will be useful,            *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 * GNU General Public License for more details.                               *
 *                                                                            *
 * You should have received a copy of the GNU General Public License          *
 * along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                            *
 ******************************************************************************/

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.CommonExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.FilterConfiguration
import com.android.build.api.variant.impl.VariantOutputImpl
import org.apache.tools.ant.filters.StringInputStream
import org.gradle.api.JavaVersion
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByType
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Properties
import kotlin.io.encoding.Base64

private val Project.android: CommonExtension
    get() = extensions.getByName("android") as CommonExtension

private val Project.androidApp: ApplicationExtension
    get() = extensions.getByType<ApplicationExtension>()

private val Project.androidComponents: ApplicationAndroidComponentsExtension
    get() = extensions.getByType<ApplicationAndroidComponentsExtension>()

private lateinit var metadata: Properties
private lateinit var localProperties: Properties

fun Project.requireMetadata(): Properties {
    if (!::metadata.isInitialized) {
        metadata = Properties().apply {
            load(rootProject.file("version.properties").inputStream())
        }
    }
    return metadata
}

fun Project.requireLocalProperties(): Properties {
    if (!::localProperties.isInitialized) {
        localProperties = Properties()

        val base64 = System.getenv("LOCAL_PROPERTIES")
        if (!base64.isNullOrBlank()) {
            localProperties.load(StringInputStream(String(Base64.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL).decode(base64))))
        } else if (project.rootProject.file("local.properties").exists()) {
            localProperties.load(rootProject.file("local.properties").inputStream())
        }
    }
    return localProperties
}

fun Project.setupCommon(projectName: String = "") {
    android.apply {
        buildToolsVersion = "37.0.0"
        compileSdk = 37
        defaultConfig.minSdk = if (projectName.lowercase() == "naive") 24 else 21
        compileOptions.sourceCompatibility = JavaVersion.VERSION_21
        compileOptions.targetCompatibility = JavaVersion.VERSION_21
        lint.showAll = true
        lint.checkAllWarnings = true
        lint.checkReleaseBuilds = false
        lint.warningsAsErrors = true
        packaging.jniLibs.useLegacyPackaging = true
        // Do not strip symbols by AGP to improve reproducibility. Symbols are manually stripped in advanced.
        packaging.jniLibs.keepDebugSymbols.add("**/*.so")
        packaging.resources.excludes.addAll(
            listOf(
                "**/*.kotlin_*",
                "/META-INF/*.version",
                "/META-INF/androidx/**",
                "/META-INF/native/**",
                "/META-INF/native-image/**",
                "/META-INF/INDEX.LIST",
                "DebugProbesKt.bin",
                "com/**",
                "org/**",
                "**/*.java",
                "**/*.proto",
            )
        )
    }
}

fun Project.setupAppCommon(projectName: String = "") {
    setupCommon(projectName)

    val lp = requireLocalProperties()
    val keystorePwd = lp.getProperty("KEYSTORE_PASS") ?: System.getenv("KEYSTORE_PASS")
    val alias = lp.getProperty("ALIAS_NAME") ?: System.getenv("ALIAS_NAME")
    val pwd = lp.getProperty("ALIAS_PASS") ?: System.getenv("ALIAS_PASS")
    val releaseKeyStore = rootProject.file("release.keystore")
    val releaseSigningValues = listOf(keystorePwd, alias, pwd)
    val hasReleaseSigningConfig = releaseKeyStore.isFile &&
        releaseSigningValues.all { !it.isNullOrBlank() }
    val hasPartialReleaseSigningConfig = releaseKeyStore.exists() ||
        releaseSigningValues.any { !it.isNullOrBlank() }

    if (hasPartialReleaseSigningConfig && !hasReleaseSigningConfig) {
        throw GradleException(
            "Production signing requires release.keystore, KEYSTORE_PASS, ALIAS_NAME, and ALIAS_PASS"
        )
    }

    androidApp.apply {
        if (hasReleaseSigningConfig) {
            signingConfigs.create("release") {
                storeFile = releaseKeyStore
                storePassword = keystorePwd!!
                keyAlias = alias!!
                keyPassword = pwd!!
                enableV3Signing = true
            }
        }

        defaultConfig.targetSdk = 37
        defaultConfig.ndk.abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a"))
        buildTypes.getByName("release") {
            @Suppress("UnstableApiUsage")
            vcsInfo.include = false
            isDebuggable = false
            isJniDebuggable = false
            // Prefer the private production key when configured. CI builds without that
            // secret still need a verifiably signed APK for installation and testing.
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
            ndk.debugSymbolLevel = "NONE"
        }
        buildTypes.getByName("debug") {
            applicationIdSuffix = "debug"
            isDebuggable = true
            isJniDebuggable = true
        }

        tasks.register("printMobileTinaReleaseCertificateSha256") {
            group = "verification"
            description = "Prints the SHA-256 digest of the certificate used by the release build."
            doLast {
                val signing = buildTypes.getByName("release").signingConfig
                    ?: throw GradleException("Release signing configuration is missing")
                val store = signing.storeFile
                    ?: throw GradleException("Release signing keystore is missing")
                val storePassword = signing.storePassword
                    ?: throw GradleException("Release signing store password is missing")
                val keyAlias = signing.keyAlias
                    ?: throw GradleException("Release signing alias is missing")
                val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                    store.inputStream().use { load(it, storePassword.toCharArray()) }
                }
                val certificate = keyStore.getCertificate(keyAlias)
                    ?: throw GradleException("Release signing certificate is missing")
                val digest = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
                println(digest.joinToString("") { "%02x".format(it.toInt() and 0xff) })
            }
        }
        dependenciesInfo.includeInApk = false
        dependenciesInfo.includeInBundle = false
        @Suppress("UnstableApiUsage")
        bundle.language.enableSplit = false
        @Suppress("UnstableApiUsage")
        bundle.abi.enableSplit = false
        if (gradle.startParameter.taskNames.isNotEmpty() && gradle.startParameter.taskNames.any { it.lowercase().contains("assemble") }) {
            splits.abi.apply {
                isEnable = true
                isUniversalApk = true
                reset()
                include("armeabi-v7a", "arm64-v8a")
            }
        }
    }
}

fun Project.setupPlugin(projectName: String) {
    setupAppCommon(projectName)

    val propPrefix = projectName.uppercase()
    val verName = requireMetadata().getProperty("${propPrefix}_VERSION_NAME").trim()
    val verCode = requireMetadata().getProperty("${propPrefix}_VERSION").trim().toInt()

    androidApp.apply {
        defaultConfig.versionName = verName
        defaultConfig.versionCode = verCode
        flavorDimensions.add("vendor")
        productFlavors.create("oss")
    }
    androidComponents.apply {
        onVariants { variant ->
            variant.outputs.forEach { output ->
                (output as? VariantOutputImpl)?.let { variantOutputImpl ->
                    val versionName = variantOutputImpl.versionName.orNull.orEmpty()
                    variantOutputImpl.outputFileName.set(variantOutputImpl.outputFileName.get()
                        .replace(project.name, "${projectName}-plugin-$versionName")
                        .replace("-release", "")
                        .replace("-oss", "")
                    )
                }
            }
        }
    }
}

fun Project.setupApp() {
    val pkgName = requireMetadata().getProperty("PACKAGE_NAME").trim()
    val verName = requireMetadata().getProperty("VERSION_NAME").trim()
    val verCode = requireMetadata().getProperty("VERSION_CODE").trim().toInt() * 5
    val expectedSigner = (providers.gradleProperty("MOBILETINA_EXPECTED_SIGNER_SHA256").orNull
        ?: System.getenv("MOBILETINA_EXPECTED_SIGNER_SHA256"))
        ?.trim()
        ?.lowercase()
        .orEmpty()
    val buildsRelease = gradle.startParameter.taskNames.any { task ->
        val name = task.lowercase()
        name.contains("release") && (name.contains("assemble") || name.contains("bundle"))
    }
    if (buildsRelease && !expectedSigner.matches(Regex("[0-9a-f]{64}"))) {
        throw GradleException(
            "MOBILETINA_EXPECTED_SIGNER_SHA256 must contain the release certificate SHA-256"
        )
    }
    val signerMask = intArrayOf(
        33, 197, 143, 183, 9, 250, 151, 1,
        250, 140, 242, 89, 207, 138, 195, 173,
        218, 240, 200, 252, 157, 40, 122, 81,
        229, 169, 26, 42, 1, 168, 35, 177,
    )
    val nativeMask = intArrayOf(
        109, 19, 167, 76, 242, 137, 53, 222,
        81, 184, 15, 195, 122, 230, 36, 149,
        209, 72, 187, 2, 111, 172, 115, 224,
        25, 132, 215, 62, 161, 91, 201, 38,
    )
    val signerToken = if (expectedSigner.matches(Regex("[0-9a-f]{64}"))) {
        expectedSigner.chunked(2).mapIndexed { index, hex ->
            hex.toInt(16) xor signerMask[index]
        }.joinToString("") { "%02x".format(it and 0xff) }
    } else {
        ""
    }
    val nativeSignerToken = if (expectedSigner.matches(Regex("[0-9a-f]{64}"))) {
        expectedSigner.chunked(2).mapIndexed { index, hex ->
            hex.toInt(16) xor signerMask[index] xor nativeMask[index]
        }.joinToString("") { "%02x".format(it and 0xff) }
    } else {
        ""
    }
    setupAppCommon()
    androidApp.apply {
        defaultConfig.applicationId = pkgName
        defaultConfig.versionCode = verCode
        defaultConfig.versionName = verName
        defaultConfig.externalNativeBuild.cmake.arguments.add(
            "-DMOBILETINA_NATIVE_TOKEN=$nativeSignerToken"
        )
        // Keep one stable English/LTR layout regardless of the device locale. Branded
        // MobileTina labels intentionally remain Persian in unqualified base resources.
        defaultConfig.resourceConfigurations.add("en")
        buildTypes.getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            buildConfigField(
                "String",
                "MOBILETINA_SIGNER_TOKEN",
                "\"$signerToken\""
            )
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                file("proguard-rules.pro")
            )
        }
        buildTypes.getByName("debug") {
            buildConfigField("String", "MOBILETINA_SIGNER_TOKEN", "\"\"")
        }
        buildFeatures.aidl = true
        buildFeatures.buildConfig = true
        buildFeatures.viewBinding = true
        compileOptions.isCoreLibraryDesugaringEnabled = true
        flavorDimensions.add("vendor")
        productFlavors.create("oss") {
            minSdk = 23
        }
        productFlavors.create("legacy") {
            minSdk = 21
            proguardFiles("proguard-rules-legacy.pro")
        }
        tasks.register("downloadAssets") {
            downloadAssets(update = false)
        }
        tasks.register("updateAssets") {
            downloadRootCAList()
            downloadAssets(update = true)
        }
        externalNativeBuild.cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    androidComponents.apply {
        onVariants { variant ->
            variant.outputs.forEach { output ->
                when (output.filters.find { it.filterType == FilterConfiguration.FilterType.ABI }?.identifier) {
                    "arm64-v8a" -> output.versionCode.set(verCode + 4)
                    "x86_64" -> output.versionCode.set(verCode + 3)
                    "armeabi-v7a" -> output.versionCode.set(verCode + 2)
                    "x86" -> output.versionCode.set(verCode + 1)
                }
                (output as? VariantOutputImpl)?.let { variantOutputImpl ->
                    val versionName = variantOutputImpl.versionName.orNull.orEmpty()
                    variantOutputImpl.outputFileName.set(variantOutputImpl.outputFileName.get()
                        .replace(project.name, "Exclave-$versionName")
                        .replace("-release", "")
                        .replace("-oss", "")
                    )
                }
            }
        }
    }
    tasks.configureEach {
        if (name.contains("preBuild")) {
            dependsOn(":app:exportLibraryDefinitionsOssRelease")
            dependsOn(":app:exportLibraryDefinitionsLegacyRelease")
        }
    }
    if (tasks.findByPath(":app:exportLibraryDefinitionsLegacyRelease") != null
        && tasks.findByPath(":app:exportLibraryDefinitionsOssRelease") != null) {
        tasks.named(":app:exportLibraryDefinitionsLegacyRelease") {
            mustRunAfter(":app:exportLibraryDefinitionsOssRelease")
        }
    }
}

// MobileTina test builds package ARMv7 and ARMv8 in one APK.
