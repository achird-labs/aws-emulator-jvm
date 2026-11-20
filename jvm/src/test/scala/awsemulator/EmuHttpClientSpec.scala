package awsemulator

import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8

import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import zio.*
import zio.test.*

object EmuHttpClientSpec extends ZIOSpecDefault {

  private val endpoint    = URI.create("http://emulator.invalid")
  private val credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("EMULATOR", "EMULATOR"))

  private val emulator: ZLayer[Any, Throwable, EmuCore] = ZLayer.scoped(ZIO.fromAutoCloseable(ZIO.attempt(EmuCore.open())))

  override def spec = suite("EmuHttpClientSpec")(
    test("S3 returns the object it stored through the blocking client") {
      for {
        emu <- ZIO.service[EmuCore]
        s3 <- ZIO.fromAutoCloseable(ZIO.attempt {
                S3Client
                  .builder()
                  .httpClient(EmuHttpClient(emu))
                  .endpointOverride(endpoint)
                  .forcePathStyle(true)
                  .region(Region.US_EAST_1)
                  .credentialsProvider(credentials)
                  .build()
              })
        _ <- ZIO.attempt(s3.createBucket(_.bucket("reports")))
        _ <- ZIO.attempt(s3.putObject(_.bucket("reports").key("daily/1.json"), RequestBody.fromString("""{"ok":false}""")))
        got <- ZIO.attempt(s3.getObjectAsBytes(_.bucket("reports").key("daily/1.json")).asUtf8String())
      } yield assertTrue(got == """{"ok":false}""")
    },
    test("a closed emulator refuses requests instead of touching freed memory") {
      for {
        emu    <- ZIO.attempt(EmuCore.open())
        _      <- ZIO.attempt(emu.close())
        failed <- ZIO.attempt(emu.dispatch("GET", "/", Seq.empty, Array.emptyByteArray)).flip
      } yield assertTrue(failed.isInstanceOf[IllegalStateException])
    },
    test("a header that cannot be framed as one line is refused before it reaches the emulator") {
      for {
        emu    <- ZIO.service[EmuCore]
        failed <- ZIO.attempt(emu.dispatch("GET", "/", Seq("x-evil" -> "a\r\nx-injected: b"), Array.emptyByteArray)).flip
      } yield assertTrue(failed.isInstanceOf[IllegalArgumentException])
    },
    test("response headers keep every value of a repeated header") {
      val parsed = EmuCore.parseHeaders("ETag: \"1\"\r\nVary: a\r\nvary: b\r\n")
      assertTrue(parsed("etag") == List("\"1\""), parsed("vary") == List("a", "b"))
    }
  ).provideSomeShared[Scope](emulator)
}
