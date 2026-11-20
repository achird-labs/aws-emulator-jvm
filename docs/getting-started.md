# Getting started

aws-emulator-jvm ships as two artifacts: the `aws-emulator-jvm` jar on Maven Central, and the `emu-core` native
library, attached for each platform to every
[GitHub Release](https://github.com/achird-labs/aws-emulator-jvm/releases). The jar does not bundle the library: you
fetch it once and point the test JVM at it.

!!! note "Before the first release"
    Until a `v*` tag has been released, build both from source as described [below](#from-source).

## 1. Add the dependency

```scala
libraryDependencies += "io.github.achird-labs" %% "aws-emulator-jvm" % "0.1.0" % Test
```

Add the SDK modules you use (`dynamodb`, `s3`, `kms`, …) as usual. The binding itself depends only on
`http-client-spi`. It needs JDK 22 or later, where the foreign function API is final.

## 2. Fetch the native library

Download the library for your platform from the release that matches the jar's version, and check it against the
release's `SHA256SUMS`:

| Platform | Release asset |
|---|---|
| Linux x86_64, glibc 2.35+ | `libemu_core-linux-x86_64.so` |
| Linux arm64, glibc 2.35+ | `libemu_core-linux-aarch64.so` |
| macOS arm64 | `libemu_core-macos-aarch64.dylib` |

```sh
VERSION=0.1.0 ASSET=libemu_core-linux-x86_64.so
BASE="https://github.com/achird-labs/aws-emulator-jvm/releases/download/v$VERSION"
curl -fsSLO "$BASE/$ASSET"
curl -fsSL "$BASE/SHA256SUMS" | grep " $ASSET\$" | sha256sum -c -
export EMU_CORE_LIBRARY="$PWD/$ASSET"
```

Other platforms build the library [from source](#from-source).

## 3. Point the test JVM at the library

The binding needs two JVM options: the library's path, and permission to call native code.

```scala
Test / fork := true
Test / javaOptions ++= Seq(
  "--enable-native-access=ALL-UNNAMED",
  s"-Demu.core.library=${sys.env("EMU_CORE_LIBRARY")}"
)
```

Without `--enable-native-access` the JDK prints a warning when the library is first linked, and
[JEP 472](https://openjdk.org/jeps/472) plans to make that an error.
If you would rather not use the system property, pass the path directly: `EmuCore.open(Path.of(...))`.

## 4. Build a client

```scala
import awsemulator.{EmuAsyncHttpClient, EmuCore}
import java.net.URI
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient

val emulator = EmuCore.open()
val dynamoDb = DynamoDbAsyncClient
  .builder()
  .httpClient(EmuAsyncHttpClient(emulator))
  .endpointOverride(URI.create("http://emulator.invalid"))
  .region(Region.US_EAST_1)
  .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
  .build()

// ... use the client ...

dynamoDb.close() // closes the client and its threads
emulator.close() // frees the emulator; the client never closes it for you
```

Any endpoint, region and credentials work: the endpoint is never resolved, and the emulator does not check
signatures. [SDK clients](guide/clients.md) explains the choices; [Services and addressing](guide/services.md) lists
what each service expects.

## With ZIO Test

The project's own tests share one emulator per suite as a layer:

```scala
val emulator: ZLayer[Any, Throwable, EmuCore] =
  ZLayer.scoped(ZIO.fromAutoCloseable(ZIO.attempt(EmuCore.open())))

override def spec = suite("my service")(/* tests that ZIO.service[EmuCore] */)
  .provideSomeShared[Scope](emulator)
```

A test that needs a clean slate opens its own `EmuCore` in a scope; emulators never share state.

## From source

You need Rust stable with cargo, which must reach `github.com/aws/aws-emulator` and crates.io; a JDK 22 or later; and
sbt 1.12. The first build compiles upstream, which takes several minutes; later builds are incremental.

```sh
git clone https://github.com/achird-labs/aws-emulator-jvm
cd aws-emulator-jvm
cargo build --release -p emu-core   # target/release/libemu_core.so, .dylib or emu_core.dll
sbt publishLocal                    # prints the snapshot version it publishes
```

Depend on the version `publishLocal` printed, and point `EMU_CORE_LIBRARY` at the library in `target/release/`.
Windows builds are untested.
