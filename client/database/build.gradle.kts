plugins {
    alias(libs.plugins.app.logdate.kmp.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    alias(libs.plugins.kover)
}

kotlin {
    applyDefaultHierarchyTemplate()

    android {
        namespace = "app.logdate.client.database"
        withDeviceTestBuilder {}.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
        androidResources {
            enable = true
        }
    }

    jvm()

    sourceSets {
        commonMain.dependencies {
            // External dependencies
            api(libs.kotlinx.datetime)
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
            api(libs.room.runtime)
            implementation(libs.sqlite.bundled)
            implementation(projects.client.device)
            implementation(libs.napier)
        }
        androidMain.dependencies {
            implementation(libs.koin.android)
            implementation(libs.sqlcipher.android)
        }
        commonTest.dependencies {
            implementation(libs.room.testing)
        }
        findByName("androidHostTest")?.dependencies {
            implementation(libs.kotlin.test.junit)
            implementation(libs.mockk)
            implementation(libs.androidx.datastore.preferences)
        }
        findByName("androidDeviceTest")?.dependencies {
            implementation(libs.kotlin.test.junit)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.room.testing)
            implementation(libs.androidx.test.core)
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.ext.junit)
        }
    }
}

dependencies {
    listOf(
        "kspAndroid",
        "kspJvm",
        "kspIosSimulatorArm64",
        "kspIosArm64",
    ).forEach {
        add(it, libs.room.compiler)
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}
