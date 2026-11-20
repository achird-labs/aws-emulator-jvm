# Getting started

aws-emulator-jvm is one jar on Maven Central. It bundles the `emu-core` native library for each supported platform,
and `EmuCore.open()` loads the one for the platform it runs on. There is nothing else to install.

!!! note "Before the first release"
    Until a `v*` tag has been released, build from source as described [below](#from-source).

## 1. Add the dependency

```scala
libraryDependencies += "io.github.achird-labs" %% "aws-emulator-jvm" % "0.1.0" % Test
```

Add the SDK modules you use (`dynamodb`, `s3`, `kms`, …) as usual. The binding itself depends only on
`http-client-spi`. It needs JDK 22 or later, where the foreign function API is final.

The jar bundles libraries for these platforms:

| Platform | Bundled as |
|---|---|
| Linux x86_64, glibc 2.35+ | `awsemulator/native/linux-x86_64/libemu_core.so` |
| Linux arm64, glibc 2.35+ | `awsemulator/native/linux-aarch64/libemu_core.so` |
| macOS arm64 | `awsemulator/native/macos-aarch64/libemu_core.dylib` |

`EmuCore.open()` copies the library to a temporary file once per JVM, because the operating system loads libraries
from files, not from inside a jar, and deletes it when the JVM exits. On any other platform it fails with a message
naming the platform; build the library [from source](#from-source) and point at it.

## 2. Allow native access

Fork the test JVM and let it call native code:

```scala
Test / fork := true
Test / javaOptions += "--enable-native-access=ALL-UNNAMED"
```

Without `--enable-native-access` the JDK prints a warning when the library is first linked, and
[JEP 472](https://openjdk.org/jeps/472) plans to make that an error.

### Using a library of your own

To load a library other than the bundled one, built from source or for another platform, name it in the system
property `emu.core.library`, which takes precedence, or pass its path to `EmuCore.open(path)`:

```scala
Test / javaOptions += s"-Demu.core.library=${sys.env("EMU_CORE_LIBRARY")}"
```

Every release also attaches the three libraries, with their `SHA256SUMS`, to its
[GitHub Release](https://github.com/achird-labs/aws-emulator-jvm/releases), for hosts of the C ABI other than the JVM.

## 3. Build a client

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

Depend on the version `publishLocal` printed. A jar built this way bundles no library, so set `emu.core.library`
to the one in `target/release/`. Windows builds are untested.
