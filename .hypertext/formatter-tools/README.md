# Native formatter preparation

The repository's `tools.json` and integrity locks own formatter versions. Run the existing `setup.py` preparation command with the repository JDK before committing; ordinary formatting never installs tools. Preparation must finish successfully before `runtime.json` can represent the current recipe. A stale runtime file is not a successful setup result.

## Backup model formatting

The six authored files in `docs/verification/backup-sync` use genuine language formatters. They are not classified as generated evidence:

- `BackupSync.tla` uses the SHA-pinned existing TLC tool's `formatter.Main`, retaining default AST verification.
- The five `.cfg` inputs use the restricted TLC-grammar formatter, followed by both-side parsing with the same pinned `ModelConfig` and exact ordered official lexer/comment/EOF comparison. Declaration order, duplicate lists and substitutions, explicit deadlock controls and comments are preserved. Unsupported syntax fails without emitting partial formatted output.

The adapter takes exact staged bytes on stdin and uses the source path only as filename context. It does not overwrite the input, evaluate a model, start backups or change application settings.

Formal-tool preparation reuses only declared existing cache artifacts and an explicitly selected supported JDK. It refuses missing, changed or ambiguous inputs; it does not download the TLC tool, compiler or runtime dependencies. The Kotlin verifier is compiled once into the ignored local `build` directory, with the exact compiled-artifact inventory checked against the reviewed recipe before reuse. The Python and Kotlin helpers use the existing Ruff and KtLint routes.

Supported configuration values include integers, booleans, strings, model values and nested sets; constant arguments; qualified names; main/module assignments and operator substitutions; and the recognized specification, invariant, property, constraint and control sections. This is not an arbitrary-expression evaluator. The current TLA+ model is self-contained apart from standard modules in the pinned JAR; undeclared local module dependencies fail closed.

Formatting and parser equivalence do not prove model behavior. If authored model inputs change, follow `docs/verification/backup-sync/README.md`: run both fixed configurations and the three specific historical counterexamples, retaining their exact expected outcomes and new source/config/tool hashes. Enabling the formatter routes does not alter those six existing input files or invalidate their retained original evidence.

The contract tests live alongside the formatter helpers. They include the actual five configurations, a required nonidentity valid TLA+ formatting control, the actual model, idempotence, malformed/unsupported input refusal, semantic/token/comment mutation rejection and prepared-cache tampering. Run them only after successful native preparation, using the normal admitted development test procedure.
