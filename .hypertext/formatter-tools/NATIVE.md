# Native formatter ownership

Native formatter setup and execution require Python 3.11 or newer. The shared
formatting engine retains its own Python requirement.

`gradle/libs.versions.toml` owns `ktlintEngine`. Gradle and `native.py` read that
same value; `.editorconfig` owns the style. The helper verifies the official
artifact checksum before executing cached bytes. Changing the catalog engine
requires reviewing its new artifact checksum in the helper, then running setup.
Formatting during a commit never downloads a missing tool.

Run `python3 .hypertext/formatter-tools/test_native.py` to verify stdin formatting,
configuration discovery, failure preservation, and deliberate checksum review.
