plugins {
    alias(libs.plugins.app.logdate.kmp.library)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.compose.compiler)
    // TODO: Re-enable once we can figure out why Modifier is an unresolved reference
    alias(libs.plugins.dokka)
}

val checkPrivateModalContract by tasks.registering {
    group = "verification"
    description = "Checks that private app windows respect the app lock."
    val sources =
        listOf("client", "app/compose-main").map { directory ->
            rootProject.fileTree(directory) {
                include("**/src/*Main/**/*.kt")
                exclude("**/build/**")
            }
        }
    inputs.files(sources)
    val sourceRoot = rootProject.projectDir
    doLast {
        val directModal =
            Regex(
                "androidx\\.compose\\.(?:material3\\.(?:AlertDialog|DatePickerDialog|DropdownMenu|ModalBottomSheet|BasicAlertDialog|TooltipBox)|ui\\.window\\.(?:Dialog|Popup))\\b",
            )
        val allowed =
            setOf(
                "client/ui/src/commonMain/kotlin/app/logdate/ui/common/PrivateModals.kt",
                "client/ui/src/commonMain/kotlin/app/logdate/ui/common/PrivateTooltip.kt",
                "app/compose-main/src/androidMain/kotlin/app/logdate/client/launch/ApplicationStartupGate.kt",
            )
        val failures =
            sources.flatMap { it.files }.filter { it.relativeTo(sourceRoot).invariantSeparatorsPath !in allowed }.flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    if (directModal.containsMatchIn(line)) {
                        "${file.relativeTo(
                            sourceRoot,
                        )}:${index + 1}: Use app.logdate.ui.common modal components so app lock hides private windows."
                    } else {
                        null
                    }
                }
            }
        check(failures.isEmpty()) { failures.joinToString("\n") }
    }
}

tasks.matching { it.name == "check" || it.name == "ktlintCheck" }.configureEach {
    dependsOn(checkPrivateModalContract)
}

kotlin {
    android {
        namespace = "app.logdate.client.ui"
        androidResources {
            enable = true
        }
        optimization {
            consumerKeepRules.apply {
                publish = true
                file("proguard-rules.pro")
            }
        }
    }

    jvm("desktop")

    sourceSets {
        val desktopMain by getting

        commonMain.dependencies {
            // Project dependencies
            api(projects.client.theme)
            api(projects.client.text)
            implementation(projects.client.media)
            implementation(projects.client.repository)
            implementation(projects.client.awareness)
            implementation(projects.client.util)
            implementation(projects.client.sensor)
            implementation(projects.shared.model)
            implementation(libs.napier)
            // Compose plugin dependencies
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material.icons.extended)
            implementation(libs.compose.material3)
            implementation(libs.compose.material3.adaptive.navigation.suite)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.ui.tooling.preview)
            // External dependencies
            implementation(libs.material3.adaptive)
            implementation(libs.material3.adaptive.layout)
            implementation(libs.material3.adaptive.navigation)
            implementation(libs.nav3.runtime)
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.kotlinx.serialization.core)
            implementation(libs.coil.compose)
            implementation(libs.multiplatform.markdown.renderer)
            implementation(libs.androidx.graphics.shapes)
            // Koin
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            implementation(libs.androidx.protolayout.material3)
            implementation(libs.androidx.tiles.renderer)
            implementation(libs.compose.ui.tooling.preview)
            implementation(libs.kotlinx.datetime)
            // TODO: Move this to proper shared module
            implementation(libs.google.maps.compose)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.window)
            // FileProvider, for dragging media out to other apps.
            implementation(libs.androidx.core.ktx)
        }
        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
        }
        val desktopTest by getting
        desktopTest.resources.srcDir(rootProject.file("test-fixtures"))
        desktopTest.dependencies {
            // Measures shared layouts headlessly on the JVM.
            implementation(libs.compose.ui.test)
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.ui.tooling)
}

// TODO: Possibly move assets to separate module
compose.resources {
    publicResClass = true
    generateResClass = always
    packageOfResClass = "logdate.client.ui.generated.resources"
}
