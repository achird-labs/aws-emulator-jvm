package awsemulator

import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8

import scala.jdk.CollectionConverters.*

import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model.*
import software.amazon.awssdk.services.kms.KmsAsyncClient
import software.amazon.awssdk.services.s3.S3Client
import zio.*
import zio.test.*

object EmuHttpClientSpec extends ZIOSpecDefault {

  private val endpoint    = URI.create("http://emulator.invalid")
  private val credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("EMULATOR", "EMULATOR"))

  private val emulator: ZLayer[Any, Throwable, EmuCore] = ZLayer.scoped(ZIO.fromAutoCloseable(ZIO.attempt(EmuCore.open())))

  private def dynamoDb(emulator: EmuCore): ZIO[Scope, Throwable, DynamoDbAsyncClient] =
    ZIO.fromAutoCloseable(ZIO.attempt {
      DynamoDbAsyncClient
        .builder()
        .httpClient(EmuAsyncHttpClient(emulator))
        .endpointOverride(endpoint)
        .region(Region.US_EAST_1)
        .credentialsProvider(credentials)
        .build()
    })

  private def attr(value: String) = AttributeValue.fromS(value)

  /** A new table is CREATING and refuses item writes until it is ACTIVE, as in DynamoDB. */
  private def createTable(client: DynamoDbAsyncClient, name: String): Task[Unit] =
    ZIO.fromCompletableFuture(
      client.createTable(
        CreateTableRequest
          .builder()
          .tableName(name)
          .billingMode(BillingMode.PAY_PER_REQUEST)
          .attributeDefinitions(AttributeDefinition.builder().attributeName("pk").attributeType("S").build())
          .keySchema(KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build())
          .build()
      )
    ) *> ZIO
      .fromCompletableFuture(client.describeTable(DescribeTableRequest.builder().tableName(name).build()))
      .map(_.table().tableStatus())
      .filterOrFail(_ == TableStatus.ACTIVE)(IllegalStateException(s"table $name never became ACTIVE"))
      .retry(Schedule.spaced(20.millis) && Schedule.recurs(50))
      .unit

  override def spec = suite("EmuHttpClientSpec")(
    test("DynamoDB stores an item and refuses a failed condition through the async client") {
      for {
        emu    <- ZIO.service[EmuCore]
        client <- dynamoDb(emu)
        _      <- createTable(client, "orders")
        put = PutItemRequest
                .builder()
                .tableName("orders")
                .item(Map("pk" -> attr("a"), "v" -> AttributeValue.fromN("1")).asJava)
                .conditionExpression("attribute_not_exists(pk)")
                .build()
        _     <- ZIO.fromCompletableFuture(client.putItem(put))
        again <- ZIO.fromCompletableFuture(client.putItem(put)).flip
        got <- ZIO.fromCompletableFuture(
                 client.getItem(GetItemRequest.builder().tableName("orders").key(Map("pk" -> attr("a")).asJava).build())
               )
      } yield assertTrue(
        again.isInstanceOf[ConditionalCheckFailedException],
        got.item().get("v").n() == "1"
      )
    },
    test("concurrent writes through one emulator all land") {
      for {
        emu    <- ZIO.service[EmuCore]
        client <- dynamoDb(emu)
        _      <- createTable(client, "concurrent")
        _ <- ZIO.foreachParDiscard(1 to 64) { i =>
               ZIO.fromCompletableFuture(
                 client.putItem(PutItemRequest.builder().tableName("concurrent").item(Map("pk" -> attr(s"k$i")).asJava).build())
               )
             }
        count <- ZIO.fromCompletableFuture(client.scan(ScanRequest.builder().tableName("concurrent").build())).map(_.count())
      } yield assertTrue(count == 64)
    },
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
    test("KMS decrypts what it encrypted with a key it created") {
      for {
        emu <- ZIO.service[EmuCore]
        kms <- ZIO.fromAutoCloseable(ZIO.attempt {
                 KmsAsyncClient
                   .builder()
                   .httpClient(EmuAsyncHttpClient(emu))
                   .endpointOverride(endpoint)
                   .region(Region.US_EAST_1)
                   .credentialsProvider(credentials)
                   .build()
               })
        key       <- ZIO.fromCompletableFuture(kms.createKey(_ => ())).map(_.keyMetadata().keyId())
        encrypted <- ZIO.fromCompletableFuture(kms.encrypt(_.keyId(key).plaintext(SdkBytes.fromUtf8String("hello"))))
        decrypted <- ZIO.fromCompletableFuture(kms.decrypt(_.ciphertextBlob(encrypted.ciphertextBlob())))
      } yield assertTrue(decrypted.plaintext().asUtf8String() == "hello")
    },
    test("two emulators share no state") {
      ZIO.scoped {
        for {
          first  <- ZIO.fromAutoCloseable(ZIO.attempt(EmuCore.open()))
          second <- ZIO.fromAutoCloseable(ZIO.attempt(EmuCore.open()))
          one    <- dynamoDb(first)
          other  <- dynamoDb(second)
          _      <- createTable(one, "only-in-first")
          tables <- ZIO.fromCompletableFuture(other.listTables()).map(_.tableNames().asScala.toList)
        } yield assertTrue(tables.isEmpty)
      }
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
