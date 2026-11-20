# Services and addressing

`emu-core` links five aws-emulator services into one router over one in-memory engine:

| Service | Use it for |
|---|---|
| DynamoDB | tables and items |
| S3 | buckets and objects |
| KMS | keys, encryption and decryption |
| IAM, STS | KMS reads its policies from them |

The behaviour is upstream aws-emulator's, at the commit `Cargo.toml` pins. Other upstream services can be added to
`emu-core` the same way; see [Development](../development.md#adding-a-service).

## Endpoint, region and credentials

Any values work, but the SDK insists on all three.

- **Endpoint.** Give every client an `endpointOverride`. It is never resolved, so a reserved name such as
  `http://emulator.invalid` makes it obvious that no network is involved. Use `http`: there is no TLS to negotiate.
- **Region.** Any region. The tests use `us-east-1`.
- **Credentials.** Any static credentials. Request authentication is off, so signatures are never verified. Do not
  leave the SDK on its default provider chain: on a machine with no AWS credentials, it fails before sending anything.

The router picks the service from the request itself, as the standalone server does: the service named in the
signature's credential scope, for one. So one emulator serves clients of every service at once.

## DynamoDB

A new table is `CREATING`, as in AWS, and refuses item writes until it is `ACTIVE`. Poll `DescribeTable` after
`CreateTable`:

```scala
ZIO.fromCompletableFuture(client.describeTable(_.tableName(name)))
  .map(_.table().tableStatus())
  .filterOrFail(_ == TableStatus.ACTIVE)(IllegalStateException(s"table $name never became ACTIVE"))
  .retry(Schedule.spaced(20.millis) && Schedule.recurs(50))
```

Errors arrive as the SDK's usual exceptions, such as `ConditionalCheckFailedException` for a failed
`ConditionExpression`.

## S3

Use path-style addressing: `.forcePathStyle(true)`. That is what the tests use. Virtual-host style, which puts the
bucket in the host name, is untested.

## KMS

`CreateKey`, `Encrypt` and `Decrypt` work as in AWS. Keys live in the emulator that created them, like every other
resource.
