# aws-emulator-jvm

[aws-emulator](https://github.com/aws/aws-emulator) inside the JVM process: DynamoDB, S3 and KMS behind a Rust C ABI,
bound to the AWS SDK for Java through the foreign function API. No Docker, no ports, no TLS.

```sh
cargo build -p emu-core
```

Apache 2.0.
