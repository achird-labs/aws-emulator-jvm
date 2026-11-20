package awsemulator

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{CompletableFuture, ExecutorService, Executors}

import org.reactivestreams.{Publisher, Subscriber, Subscription}
import scala.jdk.CollectionConverters.*

import software.amazon.awssdk.http.async.{AsyncExecuteRequest, SdkAsyncHttpClient}
import software.amazon.awssdk.http.{
  AbortableInputStream,
  ExecutableHttpRequest,
  HttpExecuteRequest,
  HttpExecuteResponse,
  SdkHttpClient,
  SdkHttpRequest,
  SdkHttpResponse
}
import software.amazon.awssdk.utils.http.SdkHttpUtils

/** A blocking SDK HTTP client that hands every request to `emulator` instead of a socket. Closing the client leaves
  * the emulator open: whoever opened it closes it.
  */
final class EmuHttpClient(emulator: EmuCore) extends SdkHttpClient {
  override def prepareRequest(request: HttpExecuteRequest): ExecutableHttpRequest =
    new ExecutableHttpRequest {
      override def call(): HttpExecuteResponse = {
        val body  = request.contentStreamProvider().map(_.newStream().readAllBytes()).orElse(Array.emptyByteArray)
        val reply = EmuSdkHttp.dispatch(emulator, request.httpRequest(), body)
        HttpExecuteResponse
          .builder()
          .response(EmuSdkHttp.response(reply))
          .responseBody(AbortableInputStream.create(ByteArrayInputStream(reply.body)))
          .build()
      }

      override def abort(): Unit = ()
    }

  override def clientName(): String = "aws-emulator"

  override def close(): Unit = ()
}

/** The asynchronous counterpart of [[EmuHttpClient]]. Native calls block, so they run on this client's own platform
  * threads rather than the caller's or a virtual thread's carrier.
  */
final class EmuAsyncHttpClient(emulator: EmuCore) extends SdkAsyncHttpClient {
  private val executor: ExecutorService = Executors.newCachedThreadPool { runnable =>
    val thread = Thread(runnable, "emu-http")
    thread.setDaemon(true)
    thread
  }

  override def execute(request: AsyncExecuteRequest): CompletableFuture[Void] = {
    val handler = request.responseHandler()
    val done    = CompletableFuture[Void]()
    EmuSdkHttp
      .collect(request.requestContentPublisher())
      .thenApplyAsync(body => EmuSdkHttp.dispatch(emulator, request.request(), body), executor)
      .whenComplete { (reply, failure) =>
        if (failure != null) {
          handler.onError(failure)
          done.completeExceptionally(failure)
        } else {
          handler.onHeaders(EmuSdkHttp.response(reply))
          handler.onStream(EmuSdkHttp.SinglePublisher(ByteBuffer.wrap(reply.body), () => done.complete(null)))
        }
      }
    done
  }

  override def clientName(): String = "aws-emulator"

  override def close(): Unit = executor.shutdown()
}

private object EmuSdkHttp {
  def dispatch(emulator: EmuCore, request: SdkHttpRequest, body: Array[Byte]): EmuReply = {
    val uri     = request.getUri
    val path    = Option(uri.getRawPath).filter(_.nonEmpty).getOrElse("/")
    val target  = Option(uri.getRawQuery).fold(path)(query => s"$path?$query")
    val headers = request.headers().asScala.toSeq.flatMap((name, values) => values.asScala.map(name -> _))
    val host    = Option.when(!headers.exists(_._1.equalsIgnoreCase("host")))("host" -> authority(request))
    emulator.dispatch(request.method().name(), target, host.toSeq ++ headers, body)
  }

  def response(reply: EmuReply): SdkHttpResponse =
    SdkHttpResponse
      .builder()
      .statusCode(reply.status)
      .headers(reply.headers.map((name, values) => name -> values.asJava).asJava)
      .build()

  private def authority(request: SdkHttpRequest): String =
    if (SdkHttpUtils.isUsingStandardPort(request.protocol(), request.port())) request.host()
    else s"${request.host()}:${request.port()}"

  /** The whole request body. A request without one has no publisher. */
  def collect(publisher: Publisher[ByteBuffer]): CompletableFuture[Array[Byte]] = {
    val body = CompletableFuture[Array[Byte]]()
    if (publisher == null) body.complete(Array.emptyByteArray)
    else
      publisher.subscribe(new Subscriber[ByteBuffer] {
        private val buffer = java.io.ByteArrayOutputStream()

        override def onSubscribe(subscription: Subscription): Unit = subscription.request(Long.MaxValue)

        override def onNext(chunk: ByteBuffer): Unit = {
          val bytes = new Array[Byte](chunk.remaining())
          chunk.get(bytes)
          buffer.write(bytes)
        }

        override def onError(failure: Throwable): Unit = body.completeExceptionally(failure)

        override def onComplete(): Unit = body.complete(buffer.toByteArray)
      })
    body
  }

  /** Emits `chunk` (when it has any bytes) then completes, and runs `finished` once the subscriber has seen it all. */
  final class SinglePublisher(chunk: ByteBuffer, finished: () => Unit) extends Publisher[ByteBuffer] {
    override def subscribe(subscriber: Subscriber[? >: ByteBuffer]): Unit =
      subscriber.onSubscribe(new Subscription {
        private val delivered = AtomicBoolean(false)

        override def request(n: Long): Unit =
          if (n <= 0) subscriber.onError(IllegalArgumentException(s"requested $n items; the rule is n > 0"))
          else if (delivered.compareAndSet(false, true)) {
            if (chunk.hasRemaining) subscriber.onNext(chunk)
            subscriber.onComplete()
            finished()
          }

        override def cancel(): Unit = if (delivered.compareAndSet(false, true)) finished()
      })
  }
}
