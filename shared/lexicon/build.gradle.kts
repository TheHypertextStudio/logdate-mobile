import app.logdate.GenerateConformanceFixtures

plugins {
    alias(libs.plugins.app.logdate.lexicon.models)
    alias(libs.plugins.app.logdate.kmp.library)
}

description = "LogDate's data models, generated from its lexicon schemas."

// The models are generated on every build from lexicons/, never checked in: a schema change
// reaches the code with nothing to run by hand. Generation fails the build if a schema is invalid.
lexiconModels {
    schemas.from(rootProject.file("lexicons/app/logdate"), rootProject.file("lexicons/community"))
    basePackage = "app.logdate.lexicon"
    recordIndex = "LogDateRecords"
    schemaIndex = "LogDateSchemas"
}

// The conformance tests run the shared client fixtures and the lexicons they were generated from on
// every target. Both are embedded at build time because common tests run on iOS too, where there's
// no repository to read files from.
val conformanceDirectory = rootProject.layout.projectDirectory.dir("conformance")
val lexiconsDirectory = rootProject.layout.projectDirectory.dir("lexicons")
val generateRecordConformanceFixtures =
    tasks.register<GenerateConformanceFixtures>("generateRecordConformanceFixtures") {
        description = "Embed the conformance manifest and record fixtures in the test sources."
        root.set(conformanceDirectory)
        fixtures.from(rootProject.fileTree(conformanceDirectory) { include("manifest.json", "records/*.json") })
        packageName.set("app.logdate.lexicon")
        objectName.set("RecordConformanceFixtures")
        outputDirectory.set(layout.buildDirectory.dir("generated/conformance/kotlin"))
    }
val generateLexiconSchemaFixtures =
    tasks.register<GenerateConformanceFixtures>("generateLexiconSchemaFixtures") {
        description = "Embed the lexicon schemas in the test sources."
        root.set(lexiconsDirectory)
        fixtures.from(rootProject.fileTree(lexiconsDirectory) { include("app/logdate/**/*.json", "community/**/*.json") })
        packageName.set("app.logdate.lexicon")
        objectName.set("LexiconSchemaFixtures")
        outputDirectory.set(layout.buildDirectory.dir("generated/lexicon-schemas/kotlin"))
    }

kotlin {
    explicitApi()

    android {
        namespace = "app.logdate.lexicon"
    }

    jvm()

    sourceSets {
        commonMain.dependencies {
            api(projects.shared.atprotoLexicon)
        }

        commonTest {
            kotlin.srcDir(generateRecordConformanceFixtures.flatMap { it.outputDirectory })
            kotlin.srcDir(generateLexiconSchemaFixtures.flatMap { it.outputDirectory })
        }
    }
}
