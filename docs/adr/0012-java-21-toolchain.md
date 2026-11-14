# 0012. Java 21 toolchain

- Status: Accepted (supersedes [ADR-0001](0001-java-25.md))
- Date: 2026-11-14

## Context
ADR-0001 chose a Java 25 toolchain. The brief asks for "Java 21 (or later)" and for source that builds with Gradle on
Java 21. A requirements walk-through found that the code only uses Java 21 language features and APIs (every source
set compiles with `--release 21`), but one Java 25 feature had crept in: the example server's `main` was
package-private. That's JEP 512, final only in Java 25; in Java 21 it was the preview JEP 445. A plain Java 21 launcher
rejects it ("Main method not found ... please define the main method as public static void main"), which was
reproduced on Temurin 21.0.13.

## Decision
- Target Java 21 through the Gradle toolchain (`java = "21"` in `gradle/libs.versions.toml`). The foojay resolver
  still provisions the JDK when it's missing.
- `main` is `public static void main(String[])` again, which every Java version launches.

## Consequences
- The project matches the brief exactly: `./gradlew build` compiles, tests and runs on a Java 21 runtime (verified:
  the test JVM is the toolchain's JDK 21, and the bytecode is class version 65). The example server starts on JDK 21.
- Hosts on Java 21, the current baseline LTS for many teams, can embed the library without a newer runtime.
- Java 22–25 features are off limits for now: unnamed variables (`_`), compact source files and instance `main`,
  scoped values, stream gatherers and the like. Nothing in the code needed them.
- Benchmarks in `docs/BENCHMARKS.md` were measured on JDK 25 before this change and haven't been re-run on 21.
