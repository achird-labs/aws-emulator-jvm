package awsemulator

import java.nio.charset.StandardCharsets.UTF_8

import zio.*
import zio.test.*

object EmuHttpClientSpec extends ZIOSpecDefault {

  private val emulator: ZLayer[Any, Throwable, EmuCore] = ZLayer.scoped(ZIO.fromAutoCloseable(ZIO.attempt(EmuCore.open())))

  override def spec = suite("EmuHttpClientSpec")(
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
