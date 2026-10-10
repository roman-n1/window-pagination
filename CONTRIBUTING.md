# Contributing

Keep pagination mechanics independent of application models and persistence libraries. Add a reproducing test before changing behavior. Tests for concurrent operations must distinguish stale completions from the currently owned request.

All handwritten Kotlin source, including tests and build scripts, follows the [no-null policy](docs/null-safety.md). Do not introduce a baseline or inline suppression. Prefer explicit domain alternatives to magic strings or sentinel IDs.

Run the syntax policy, compiler policy and relevant target tests before sending a pull request. Shared-code changes require platform compilation checks; JVM success alone does not establish Native or browser behavior. Changes to the compiler policy require both rejecting and accepting compiler fixtures.

Do not publish application code, credentials, internal endpoints or customer data in examples, issues or tests.
