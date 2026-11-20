package awsemulator

import java.lang.foreign.{Arena, FunctionDescriptor, Linker, MemoryLayout, MemorySegment, SymbolLookup, ValueLayout}
import java.lang.invoke.MethodHandle
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Path, Paths}
import java.util.concurrent.locks.ReentrantReadWriteLock

/** One response from the emulator. Header names are lower case; a repeated header keeps every value. */
final case class EmuReply(status: Int, headers: Map[String, List[String]], body: Array[Byte])

/** One in-process aws-emulator over the `emu-core` C ABI. Two instances never share state.
  *
  * `dispatch` is safe to call from several threads at once. `close` waits for running calls and frees the emulator;
  * any call after it throws `IllegalStateException`.
  */
final class EmuCore private (native: EmuCore.Native, emulator: MemorySegment) extends AutoCloseable {
  private val lock   = ReentrantReadWriteLock()
  private var closed = false

  def dispatch(method: String, uri: String, headers: Seq[(String, String)], body: Array[Byte]): EmuReply = {
    lock.readLock().lock()
    try {
      if (closed) throw IllegalStateException("the emulator is closed")
      val arena = Arena.ofConfined()
      try {
        val block = EmuCore.headerBlock(headers)
        val out   = arena.allocate(EmuCore.ResponseLayout)
        val code  = native.dispatch
          .invokeWithArguments(
            emulator,
            arena.allocateFrom(method),
            arena.allocateFrom(uri),
            bytes(arena, block),
            Long.box(block.length.toLong),
            bytes(arena, body),
            Long.box(body.length.toLong),
            out
          )
          .asInstanceOf[Integer]
        if (code != 0) throw IllegalArgumentException(s"the emulator could not read the request: $method $uri")
        try read(out)
        finally native.responseFree.invokeWithArguments(out)
      } finally arena.close()
    } finally lock.readLock().unlock()
  }

  override def close(): Unit = {
    lock.writeLock().lock()
    try
      if (!closed) {
        closed = true
        native.free.invokeWithArguments(emulator)
      }
    finally lock.writeLock().unlock()
  }

  private def bytes(arena: Arena, value: Array[Byte]): MemorySegment =
    if (value.isEmpty) MemorySegment.NULL
    else arena.allocateFrom(ValueLayout.JAVA_BYTE, value*)

  private def read(out: MemorySegment): EmuReply = {
    val status  = java.lang.Short.toUnsignedInt(out.get(ValueLayout.JAVA_SHORT, EmuCore.StatusOffset))
    val headers = buffer(out, EmuCore.HeadersOffset)
    val body    = buffer(out, EmuCore.BodyOffset)
    EmuReply(status, EmuCore.parseHeaders(String(headers, UTF_8)), body)
  }

  private def buffer(out: MemorySegment, offset: Long): Array[Byte] = {
    val length = out.get(ValueLayout.JAVA_LONG, offset + ValueLayout.ADDRESS.byteSize())
    if (length == 0) Array.emptyByteArray
    else out.get(ValueLayout.ADDRESS, offset).reinterpret(length).toArray(ValueLayout.JAVA_BYTE)
  }
}

object EmuCore {

  /** The system property naming the native library [[open]] loads. */
  val LibraryProperty = "emu.core.library"

  /** A new emulator from the library named by [[LibraryProperty]]. */
  def open(): EmuCore =
    open(
      Paths.get(
        Option(System.getProperty(LibraryProperty))
          .getOrElse(throw IllegalStateException(s"set -D$LibraryProperty to the emu-core native library"))
      )
    )

  /** A new emulator from the native library at `library`. */
  def open(library: Path): EmuCore = {
    val native   = Native.load(library)
    val emulator = native.create.invokeWithArguments().asInstanceOf[MemorySegment]
    if (emulator == MemorySegment.NULL) throw IllegalStateException("the emulator could not start")
    EmuCore(native, emulator)
  }

  /** `struct EmuResponse { uint16_t status; uint8_t *headers; size_t headers_len; uint8_t *body; size_t body_len; }` */
  private val ResponseLayout: MemoryLayout = MemoryLayout.structLayout(
    ValueLayout.JAVA_SHORT.withName("status"),
    MemoryLayout.paddingLayout(6),
    ValueLayout.ADDRESS.withName("headers"),
    ValueLayout.JAVA_LONG.withName("headers_len"),
    ValueLayout.ADDRESS.withName("body"),
    ValueLayout.JAVA_LONG.withName("body_len")
  )
  private val StatusOffset  = 0L
  private val HeadersOffset = 8L
  private val BodyOffset    = 24L

  private final case class Native(create: MethodHandle, free: MethodHandle, dispatch: MethodHandle, responseFree: MethodHandle)

  private object Native {
    def load(library: Path): Native = {
      val linker  = Linker.nativeLinker()
      val symbols = SymbolLookup.libraryLookup(library, Arena.global())
      def handle(name: String, descriptor: FunctionDescriptor): MethodHandle =
        linker.downcallHandle(
          symbols.find(name).orElseThrow(() => IllegalStateException(s"$library does not export $name")),
          descriptor
        )
      import ValueLayout.{ADDRESS, JAVA_INT, JAVA_LONG}
      Native(
        create = handle("emu_new", FunctionDescriptor.of(ADDRESS)),
        free = handle("emu_free", FunctionDescriptor.ofVoid(ADDRESS)),
        dispatch = handle(
          "emu_dispatch",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS)
        ),
        responseFree = handle("emu_response_free", FunctionDescriptor.ofVoid(ADDRESS))
      )
    }
  }

  private[awsemulator] def headerBlock(headers: Seq[(String, String)]): Array[Byte] =
    headers.iterator
      .map { (name, value) =>
        if (name.exists(c => c == '\r' || c == '\n' || c == ':') || value.exists(c => c == '\r' || c == '\n'))
          throw IllegalArgumentException(s"header $name cannot be framed as a single line")
        s"$name: $value\r\n"
      }
      .mkString
      .getBytes(UTF_8)

  private[awsemulator] def parseHeaders(block: String): Map[String, List[String]] =
    block
      .split("\r\n")
      .iterator
      .filter(_.nonEmpty)
      .map { line =>
        val colon = line.indexOf(':')
        line.substring(0, colon).trim.toLowerCase -> line.substring(colon + 1).trim
      }
      .toList
      .groupMap(_._1)(_._2)
}
