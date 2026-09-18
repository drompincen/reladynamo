# Java 11 verification

The Java 11 claim has two halves, and only one of them is provable by the compiler.

## Compilation — enforced on every build

`maven.compiler.release=11` compiles against the Java 11 API signatures, and `scripts/check.sh`
asserts every emitted adapter class is **class-file major ≤ 55**. That gate has been green since the
skeleton existed.

`release` is the only correct setting here: `source`/`target` constrain syntax and bytecode but still
expose the build JDK's newer APIs, so `Stream.toList()` compiles happily and then throws
`NoSuchMethodError` at runtime.

## Execution — verified in CI on every push

`release=11` does **not** prove the code runs on a Java 11 VM. The `adapter (JDK 11,
reladynamo-core)` leg of `.github/workflows/build.yml` proves it, on every push and pull request:

```
mvn -B clean test -pl reladynamo-core -am     # on Temurin 11, ubuntu-latest
```

That runs the whole current `reladynamo-core` suite — the XML parser, mapping validator, key
strategy, temporal encoder, query planner, its adversarial fuzzer, and the data bridge — on a real
Java 11 VM, and the job fails if any of it does not.

It builds `reladynamo-core` and the parent POM only, and that restriction is not a convenience: see
[What is NOT covered](#what-is-not-covered-and-why) below.

### First verified 2026-09-13, by hand

```
openjdk version "11.0.32.1" 2026-08-18
OpenJDK Runtime Environment Temurin-11.0.32.1+1
```

`reladynamo-core` — **110 tests, 0 failures** on a real Temurin JDK 11 (the suite has grown since;
CI runs whatever it is now). Reproducing that locally:

The `mvn` on PATH in this environment is **Windows Maven** reached through WSL interop, so a
WSL-native JDK is invisible to it — pointing surefire at a Linux `java` fails with a mangled
`C:\tmp\...` path. Running on a Linux JDK needs a Linux Maven too:

```bash
curl -sSL -o jdk11.tar.gz \
  "https://api.adoptium.net/v3/binary/latest/11/ga/linux/aarch64/jdk/hotspot/normal/eclipse"
tar xzf jdk11.tar.gz
curl -sSL -o maven.tar.gz \
  "https://archive.apache.org/dist/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.tar.gz"
tar xzf maven.tar.gz

export JAVA_HOME=$PWD/jdk-11.0.32.1+1
export PATH=$JAVA_HOME/bin:$PATH
apache-maven-3.9.9/bin/mvn -B -o \
  -Dmaven.repo.local=/mnt/c/Users/drom/.m2/repository \
  -pl reladynamo-core -am test
```

This machine is **aarch64** — use the `aarch64` binary, not `x64`, or the JDK fails with
`Exec format error`.

## What is NOT covered, and why

`reladynamo-ddb` and `reladynamo-test-kit` are **not** built or run on JDK 11 anywhere, and cannot
be as they stand. A JDK 11 *compiler* rejects them outright:

```
[ERROR] reladynamo-test-kit/src/main/java/io/reladynamo/testkit/LocalDynamoDb.java:[3,52]
        cannot access com.amazonaws.services.dynamodbv2.local.main.ServerRunner
[ERROR]   bad class file: .../DynamoDBLocal-2.5.3.jar(...ServerRunner.class)
[ERROR]     class file has wrong version 61.0, should be 55.0
```

`LocalDynamoDb` is test-kit **main** code and imports `ServerRunner` and `DynamoDBProxyServer`
directly, and DynamoDBLocal 2.5.3 is compiled to class-file 61 (Java 17). So the floor for
test-kit, for `reladynamo-ddb` (whose tests depend on test-kit) and for demos 01 and 03 is **JDK
17**, which is what CI now uses for them. This was the defect behind four consecutive red CI runs:
the JDK 11 legs never reached a test, they failed in `javac`.

### The gap, stated plainly

Running `reladynamo-ddb`'s tests on a Java 11 VM is *possible in principle* — DynamoDB Local would
run out-of-process on a 17+ JVM and the test JVM on 11 would talk to it over HTTP — but not with this
harness. `LocalDynamoDb.start()` always starts an in-process `DynamoDBProxyServer` and offers no way
to point a client at an external endpoint, and the class-file 61 imports mean the module cannot be
compiled by a JDK 11 compiler even if it did. Closing the gap means changing test-kit's main API, not
configuring CI, so it is deliberately left open.

**Honest status.** Java 11 is verified in two separate, non-overlapping halves:

| Claim | Scope | Proved by |
|---|---|---|
| Compiles to Java 11 API + bytecode | **every** module | `maven.compiler.release=11`, plus the `java11-floor` gate in `scripts/check.sh`, which reads the major version byte of every class under `reladynamo-*/target/classes` and fails above 55 |
| Executes on a real Java 11 VM | `reladynamo-core` only | `adapter (JDK 11, reladynamo-core)` in CI |
| Executes on a real Java 11 VM | `reladynamo-ddb`, `reladynamo-test-kit` | **not proved** — inferred from bytecode level and API surface only. See the gap above. |

### A second, unrelated limit: Linux ARM

The DynamoDB modules also cannot run on Linux ARM at all. DynamoDB Local is a JNI wrapper over
SQLite, and `com.almworks.sqlite4java` publishes natives only for:

```
linux-amd64  linux-i386  osx  win32-x64  win32-x86
```

There is no `linux-aarch64` native, so DynamoDB Local cannot start on an ARM dev box. That is an
environment limit, not an adapter one, and it is why the DynamoDB suites belong in CI:
`ubuntu-latest` is x86-64.
