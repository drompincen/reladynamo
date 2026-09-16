# Java 11 verification

The Java 11 claim has two halves, and only one of them is provable by the compiler.

## Compilation — enforced on every build

`maven.compiler.release=11` compiles against the Java 11 API signatures, and `scripts/check.sh`
asserts every emitted adapter class is **class-file major ≤ 55**. That gate has been green since the
skeleton existed.

`release` is the only correct setting here: `source`/`target` constrain syntax and bytecode but still
expose the build JDK's newer APIs, so `Stream.toList()` compiles happily and then throws
`NoSuchMethodError` at runtime.

## Execution — verified 2026-09-13

`release=11` does **not** prove the code runs on a Java 11 VM. That was verified separately:

```
openjdk version "11.0.32.1" 2026-08-18
OpenJDK Runtime Environment Temurin-11.0.32.1+1
```

`reladynamo-core` — **110 tests, 0 failures** on a real Temurin JDK 11: the XML parser, key strategy,
mapping validator, temporal encoder, query planner and its adversarial fuzzer, and the data bridge.

### Reproducing it

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

`reladynamo-ddb` and `reladynamo-test-kit` **cannot** run on Linux ARM here. They depend on DynamoDB
Local, which is a JNI wrapper over SQLite, and `com.almworks.sqlite4java` publishes natives only for:

```
linux-amd64  linux-i386  osx  win32-x64  win32-x86
```

There is no `linux-aarch64` native, so DynamoDB Local cannot start. This is an environment limit, not
an adapter one: those modules compile to class-file 55 like the rest and use no API above Java 11.

**Honest status:** Java 11 execution is proven for the core module and inferred — from bytecode level
and API surface — for the DynamoDB modules. Closing that gap needs either an x86-64 Linux runner or a
DynamoDB Local alternative with an ARM native, and belongs in CI rather than here.
