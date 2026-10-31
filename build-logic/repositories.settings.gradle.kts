// The repositories every LogDate build resolves dependencies from. Both the full build's settings and
// the server container build's settings apply this file, so the container resolves the server from
// exactly the repositories the full build is checked against. It lives in build-logic because the
// container build copies that directory. Google's repository serves only the groups Google
// publishes, and the snapshot repositories serve only snapshot versions, so no release dependency can
// resolve from a snapshot host.
dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        }
        maven {
            url = uri("https://oss.sonatype.org/content/repositories/snapshots/")
            mavenContent { snapshotsOnly() }
        }
        maven {
            // Get latest version from https://androidx.dev/snapshots/builds/
            url = uri("https://androidx.dev/snapshots/builds/13551459/artifacts/repository")
            mavenContent { snapshotsOnly() }
        }
    }
}
