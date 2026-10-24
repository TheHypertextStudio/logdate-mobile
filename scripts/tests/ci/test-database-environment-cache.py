"""Database fixtures reach forked tests after configuration-cache reuse."""

import json
import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import tomllib
import unittest

ROOT = Path(__file__).resolve().parents[3]
VARIABLES = (
    "DATABASE_URL",
    "DATABASE_USER",
    "DATABASE_PASSWORD",
    "LOGDATE_DIAGNOSTIC_TEST_DATABASE_URL",
    "LOGDATE_DIAGNOSTIC_TEST_DISPOSABLE",
)


class DatabaseEnvironmentCacheTest(unittest.TestCase):
    def test_forked_tests_receive_changed_and_unset_database_environment(self):
        self.exercise_fixture(initialize_environment=True)

    def test_default_forks_keep_unmanaged_environment_current(self):
        self.exercise_fixture(initialize_environment=False)

    def exercise_fixture(self, initialize_environment):
        with tempfile.TemporaryDirectory(prefix="database-environment-cache-") as tmp:
            project = Path(tmp)
            plugin = project / "fixture-plugin"
            implementation = (
                plugin / "src/main/kotlin/app/logdate/TestDatabaseEnvironmentPlugin.kt"
            )
            implementation.parent.mkdir(parents=True)
            implementation.write_bytes(
                (
                    ROOT
                    / "build-logic/src/main/kotlin/app/logdate/TestDatabaseEnvironmentPlugin.kt"
                ).read_bytes()
            )
            (plugin / "settings.gradle.kts").write_text(
                'rootProject.name = "fixture-plugin"\n'
            )
            (plugin / "build.gradle.kts").write_text(
                textwrap.dedent(
                    """\
                    plugins { `kotlin-dsl` }
                    repositories { mavenCentral(); gradlePluginPortal() }
                    gradlePlugin {
                        plugins {
                            register("testDatabaseEnvironment") {
                                id = "app.logdate.test-database-environment"
                                implementationClass = "app.logdate.TestDatabaseEnvironmentPlugin"
                            }
                        }
                    }
                    """
                )
            )
            (project / "settings.gradle.kts").write_text(
                'pluginManagement { includeBuild("fixture-plugin") }\n'
                'rootProject.name = "database-environment-fixture"\n'
            )
            versions = tomllib.loads((ROOT / "gradle/libs.versions.toml").read_text())
            junit = versions["versions"]["junit"]
            fixture_flag = (
                'environment("DATABASE_FIXTURE_FLAG", "true")'
                if initialize_environment
                else ""
            )
            (project / "build.gradle.kts").write_text(
                textwrap.dedent(
                    f"""\
                    plugins {{
                        java
                        id("app.logdate.test-database-environment")
                    }}
                    repositories {{ mavenCentral() }}
                    dependencies {{ testImplementation("junit:junit:{junit}") }}
                    tasks.test {{
                        useJUnit()
                        systemProperty("java.awt.headless", "true")
                        // Match consumers that add flags and initialize the fork environment.
                        {fixture_flag}
                        // Force a fork for every vector without putting passwords in cache keys.
                        inputs.file(layout.projectDirectory.file("expected.properties"))
                        testLogging {{
                            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                        }}
                    }}
                    """
                )
            )
            (project / "gradle.properties").write_text(
                "org.gradle.daemon=false\n"
                "org.gradle.configuration-cache=true\n"
                "org.gradle.caching=true\n"
                "org.gradle.parallel=false\n"
                "org.gradle.workers.max=1\n"
                "org.gradle.jvmargs=-Xmx2048m -XX:MaxMetaspaceSize=1024m -Djava.awt.headless=true\n"
                "kotlin.compiler.execution.strategy=in-process\n"
            )
            source = project / "src/test/java/DatabaseEnvironmentTest.java"
            source.parent.mkdir(parents=True)
            checked_variables = VARIABLES + (
                () if initialize_environment else ("UNMANAGED_FIXTURE_CONTEXT",)
            )
            names = ", ".join(json.dumps(name) for name in checked_variables)
            flag_value = '"true"' if initialize_environment else "null"
            source.write_text(
                textwrap.dedent(
                    f"""\
                    import java.nio.file.Files;
                    import java.nio.file.Path;
                    import java.util.Properties;
                    import org.junit.Test;
                    import static org.junit.Assert.assertEquals;

                    public class DatabaseEnvironmentTest {{
                        @Test public void inheritsCurrentFixture() throws Exception {{
                            Properties expected = new Properties();
                            try (var input = Files.newBufferedReader(Path.of("expected.properties"))) {{
                                expected.load(input);
                            }}
                            for (String name : new String[] {{{names}}}) {{
                                assertEquals(name, expected.getProperty(name), System.getenv(name));
                            }}
                            assertEquals("fixture flag", {flag_value}, System.getenv("DATABASE_FIXTURE_FLAG"));
                        }}
                    }}
                    """
                )
            )
            initial = dict(
                zip(
                    VARIABLES,
                    (
                        "jdbc:postgresql://127.0.0.1:15432/fixture_a",
                        "fixture_user_a",
                        "synthetic_password_a",
                        "postgresql://127.0.0.1:15432/fixture_a",
                        "true",
                    ),
                )
            )
            changed = dict(
                zip(
                    VARIABLES,
                    (
                        "jdbc:postgresql://127.0.0.1:15433/fixture_b",
                        "fixture_user_b",
                        "synthetic_password_b",
                        "postgresql://127.0.0.1:15433/fixture_b",
                        "false",
                    ),
                )
            )
            password_changed = dict(changed, DATABASE_PASSWORD="synthetic_password_c")
            if initialize_environment:
                vectors = (initial, changed, password_changed, {})
            else:
                vectors = (
                    dict(initial, UNMANAGED_FIXTURE_CONTEXT="initial"),
                    dict(initial, UNMANAGED_FIXTURE_CONTEXT="changed"),
                    initial,
                    {},
                )
            for index, values in enumerate(vectors):
                with self.subTest(phase=index):
                    (project / "expected.properties").write_text(
                        "".join(f"{name}={value}\n" for name, value in values.items())
                    )
                    environment = os.environ.copy()
                    for name in (*checked_variables, "DATABASE_FIXTURE_FLAG"):
                        environment.pop(name, None)
                    environment.update(values)
                    result = subprocess.run(
                        [
                            str(ROOT / "gradlew"),
                            "--project-dir",
                            str(project),
                            "test",
                            "--configuration-cache",
                            "--no-daemon",
                            "--max-workers=1",
                            "-Pkotlin.compiler.execution.strategy=in-process",
                            "--console=plain",
                        ],
                        cwd=ROOT,
                        env=environment,
                        text=True,
                        stdout=subprocess.PIPE,
                        stderr=subprocess.STDOUT,
                        timeout=600,
                    )
                    self.assertEqual(result.returncode, 0, result.stdout[-12000:])
                    if index:
                        self.assertIn("Reusing configuration cache.", result.stdout)


if __name__ == "__main__":
    unittest.main()
