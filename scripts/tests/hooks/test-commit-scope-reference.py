import json
import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[3]
REGISTRY = ROOT / ".hypertext/commits.json"
REFERENCE = ROOT / "docs/reference/standards/commit-scopes.md"
# The formatter pads each cell to its column width, so cells are matched without their padding.
ROW = re.compile(r"^\|\s*`([^`]+)`\s*\|\s*(.*?)\s*\|\s*(.*?)\s*\|\s*(.*?)\s*\|$")


def table_rows(text):
    """Return {scope: (description, includes, excludes)} for the scope table."""
    rows = {}
    for line in text.splitlines():
        match = ROW.match(line)
        if match:
            scope, description, includes, excludes = match.groups()
            if scope in rows:
                rows[scope] = None
            else:
                rows[scope] = (description, includes, excludes)
    return rows


def differences(registry, text):
    """Describe every way the reference table disagrees with the registry."""
    rows = table_rows(text)
    problems = []
    for scope, entry in registry["scopes"].items():
        if scope not in rows:
            problems.append("missing row for " + scope)
            continue
        if rows[scope] is None:
            problems.append("duplicate row for " + scope)
            continue
        expected = (
            entry["description"],
            "; ".join(entry["includes"]),
            "; ".join(entry["excludes"]),
        )
        for label, want, have in zip(
            ("description", "includes", "excludes"), expected, rows[scope]
        ):
            if want != have:
                problems.append(
                    scope
                    + " "
                    + label
                    + ": registry has '"
                    + want
                    + "', page has '"
                    + have
                    + "'"
                )
    for scope in rows:
        if scope not in registry["scopes"]:
            problems.append("unregistered scope " + scope)
    return problems


class CommitScopeReferenceTests(unittest.TestCase):
    def setUp(self):
        self.registry = json.loads(REGISTRY.read_text())
        self.text = REFERENCE.read_text()

    def test_reference_matches_registry(self):
        self.assertEqual([], differences(self.registry, self.text))

    def test_wrong_description_is_reported(self):
        scope = next(iter(self.registry["scopes"]))
        description = self.registry["scopes"][scope]["description"]
        altered = self.text.replace("| " + description + " ", "| Something else ", 1)
        self.assertNotEqual(self.text, altered)
        self.assertTrue(
            any(
                scope + " description" in problem
                for problem in differences(self.registry, altered)
            )
        )

    def test_missing_row_is_reported(self):
        scope = next(iter(self.registry["scopes"]))
        kept = [
            line
            for line in self.text.splitlines()
            if not (ROW.match(line) and ROW.match(line).group(1) == scope)
        ]
        problems = differences(self.registry, "\n".join(kept))
        self.assertIn("missing row for " + scope, problems)

    def test_unregistered_row_is_reported(self):
        extra = self.text + "\n| `invented` | Invented | nothing | nothing |\n"
        self.assertIn("unregistered scope invented", differences(self.registry, extra))


if __name__ == "__main__":
    unittest.main()
