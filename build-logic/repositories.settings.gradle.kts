// The repositories every LogDate build resolves dependencies from. Both the full build's settings and
// the server container build's settings apply this file, so the container resolves the server from
// exactly the repositories the full build is checked against. It lives in build-logic because the
// container build copies that directory. Google's repository serves only the groups Google
// publishes.
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
    }
}
