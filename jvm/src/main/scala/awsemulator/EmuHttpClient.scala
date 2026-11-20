package awsemulator

import java.io.ByteArrayInputStream

import scala.jdk.CollectionConverters.*

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
}
