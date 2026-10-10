# No-null source policy

Use explicit alternatives for absence. Item types have an `Any` upper bound. Empty collections are real empty collections, not substitutes for a missing loaded page; domain-specific alternatives carry that distinction.

The independent `null-safety` CI job has two layers and runs for every pull request and main-branch push, without path filters:

1. **Repository-wide lexical check.** Every handwritten `.kt` and `.kts`, including tests and newly added modules, is scanned for nullable syntax (`?`), `null`, `!!`, `*OrNull` calls and null assertions (`requireNotNull` / `checkNotNull` / `assertNotNull`). Comments and string contents are ignored, but executable `${...}` templates are checked. There is no suppression, baseline or per-file allowlist. Generated build/cache directories are excluded.
2. **Compiler type check.** A build-only Kotlin compiler plugin inspects source declarations after type resolution: variables, fields, properties, parameters, function returns and generic bounds. It rejects nullable types, including nested generic arguments and inferred results such as `val item = map[key]`. Star projections are rejected because their readable type can be nullable. Inherited declarations and compiler-generated declarations such as data-class `equals(Any?)` are outside the handwritten-code policy.

The compiler check runs against both modules' JVM production and test compilations with `-PcheckNullTypes`. Their current logic is entirely in common source sets; platform compilation jobs check target compatibility separately. Future platform-only source sets must get equivalent compiler-policy coverage before adding implementation there. Gradle scripts and build tooling receive the lexical check.

Some standard-library helpers widen a lambda return to a nullable type. Use a directly typed `Comparator` with explicit non-null lambda parameter types instead of a selector returning `Comparable<*>?`, and an explicit anonymous function returning `Unit` when a test callback expects a nullable result.

This is a policy on handwritten Kotlin declarations and syntax, not proof that foreign code or platform APIs can never return null at runtime. Keep interop boundaries explicit and review Java/platform types. The compiler checker is tied to the repository's Kotlin version; upgrade it together with its accepting and rejecting compiler fixtures.

Local commands:

```sh
python3 -m unittest discover -s scripts/tests
python3 scripts/check_null_safety.py
./gradlew :null-safety:test :core:compileTestKotlinJvm :compose:compileTestKotlinJvm -PcheckNullTypes
```
