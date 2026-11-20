//! Drives the emulator only through its C ABI, as a JVM binding will.

use emu_core::{emu_dispatch, emu_free, emu_new, emu_response_free, EmuResponse, Emulator};
use std::ffi::CString;

struct Reply {
    status: u16,
    headers: String,
    body: String,
}

/// With authentication off the signature is not checked, but the credential scope still names
/// the service the router hands the request to.
fn authorization(service: &str) -> String {
    format!(
        "authorization: AWS4-HMAC-SHA256 Credential=EMULATORPLACEHOLDER/20261119/us-east-1/{service}/aws4_request, \
         SignedHeaders=host;x-amz-date, Signature=0000\r\nx-amz-date: 20261119T000000Z\r\n"
    )
}

fn call(emulator: *mut Emulator, method: &str, uri: &str, headers: &str, body: &[u8]) -> Reply {
    let method = CString::new(method).unwrap();
    let uri = CString::new(uri).unwrap();
    let mut out = std::mem::MaybeUninit::<EmuResponse>::uninit();
    let code = unsafe {
        emu_dispatch(
            emulator,
            method.as_ptr(),
            uri.as_ptr(),
            headers.as_ptr(),
            headers.len(),
            body.as_ptr(),
            body.len(),
            out.as_mut_ptr(),
        )
    };
    assert_eq!(code, 0, "emu_dispatch refused the request");
    let mut response = unsafe { out.assume_init() };
    let reply = unsafe {
        Reply {
            status: response.status,
            headers: String::from_utf8_lossy(std::slice::from_raw_parts(
                response.headers,
                response.headers_len,
            ))
            .into_owned(),
            body: String::from_utf8_lossy(std::slice::from_raw_parts(
                response.body,
                response.body_len,
            ))
            .into_owned(),
        }
    };
    unsafe { emu_response_free(&mut response) };
    reply
}

fn json(service: &str, target: &str, version: &str, body: &str, emulator: *mut Emulator) -> Reply {
    let headers = format!(
        "host: {service}.us-east-1.amazonaws.com\r\ncontent-type: application/x-amz-json-{version}\r\n\
         x-amz-target: {target}\r\n{}",
        authorization(service)
    );
    call(emulator, "POST", "/", &headers, body.as_bytes())
}

#[test]
fn kms_encrypts_and_decrypts_with_a_key_it_created() {
    let emulator = emu_new();
    assert!(!emulator.is_null());

    let created = json("kms", "TrentService.CreateKey", "1.1", "{}", emulator);
    assert_eq!(created.status, 200, "{}", created.body);
    let key_id = serde_json::from_str::<serde_json::Value>(&created.body).unwrap()["KeyMetadata"]
        ["KeyId"]
        .as_str()
        .unwrap()
        .to_string();

    let encrypted = json(
        "kms",
        "TrentService.Encrypt",
        "1.1",
        &format!(r#"{{"KeyId":"{key_id}","Plaintext":"aGVsbG8="}}"#),
        emulator,
    );
    assert_eq!(encrypted.status, 200, "{}", encrypted.body);
    let blob = serde_json::from_str::<serde_json::Value>(&encrypted.body).unwrap()
        ["CiphertextBlob"]
        .as_str()
        .unwrap()
        .to_string();

    let decrypted = json(
        "kms",
        "TrentService.Decrypt",
        "1.1",
        &format!(r#"{{"CiphertextBlob":"{blob}"}}"#),
        emulator,
    );
    assert_eq!(decrypted.status, 200, "{}", decrypted.body);
    assert!(
        decrypted.body.contains(r#""Plaintext":"aGVsbG8=""#),
        "{}",
        decrypted.body
    );

    unsafe { emu_free(emulator) };
}

/// A new table is `CREATING` and refuses item writes until it turns `ACTIVE`, as in DynamoDB,
/// so a client polls `DescribeTable` first.
fn wait_until_active(describe: impl Fn() -> Reply) {
    for _ in 0..50 {
        let described = describe();
        assert_eq!(described.status, 200, "{}", described.body);
        if described.body.contains(r#""TableStatus":"ACTIVE""#) {
            return;
        }
        std::thread::sleep(std::time::Duration::from_millis(20));
    }
    panic!("the table never became ACTIVE");
}

#[test]
fn dynamodb_reads_back_an_item_and_refuses_a_failed_condition() {
    let emulator = emu_new();
    let ddb = |target: &str, body: &str| {
        json(
            "dynamodb",
            &format!("DynamoDB_20120810.{target}"),
            "1.0",
            body,
            emulator,
        )
    };

    let table = ddb(
        "CreateTable",
        r#"{"TableName":"orders","BillingMode":"PAY_PER_REQUEST",
            "AttributeDefinitions":[{"AttributeName":"pk","AttributeType":"S"}],
            "KeySchema":[{"AttributeName":"pk","KeyType":"HASH"}]}"#,
    );
    assert_eq!(table.status, 200, "{}", table.body);
    wait_until_active(|| ddb("DescribeTable", r#"{"TableName":"orders"}"#));

    let put = r#"{"TableName":"orders","Item":{"pk":{"S":"a"},"v":{"N":"1"}},"ConditionExpression":"attribute_not_exists(pk)"}"#;
    let first = ddb("PutItem", put);
    assert_eq!(first.status, 200, "{}", first.body);

    let again = ddb("PutItem", put);
    assert_eq!(again.status, 400);
    assert!(
        again.body.contains("ConditionalCheckFailedException"),
        "{}",
        again.body
    );

    let got = ddb(
        "GetItem",
        r#"{"TableName":"orders","Key":{"pk":{"S":"a"}}}"#,
    );
    assert_eq!(got.status, 200, "{}", got.body);
    assert!(got.body.contains(r#""v":{"N":"1"}"#), "{}", got.body);

    unsafe { emu_free(emulator) };
}

#[test]
fn s3_returns_the_object_it_stored() {
    let emulator = emu_new();
    let headers = |extra: &str| {
        format!(
            "host: s3.us-east-1.amazonaws.com\r\n{extra}{}",
            authorization("s3")
        )
    };

    let bucket = call(emulator, "PUT", "/reports", &headers(""), b"");
    assert_eq!(bucket.status, 200, "{}", bucket.body);

    let put = call(
        emulator,
        "PUT",
        "/reports/daily/1.json",
        &headers("content-type: application/json\r\nx-amz-content-sha256: UNSIGNED-PAYLOAD\r\n"),
        br#"{"ok":false}"#,
    );
    assert_eq!(put.status, 200, "{}", put.body);
    assert!(
        put.headers.to_ascii_lowercase().contains("etag:"),
        "{}",
        put.headers
    );

    let got = call(emulator, "GET", "/reports/daily/1.json", &headers(""), b"");
    assert_eq!(got.status, 200, "{}", got.body);
    assert_eq!(got.body, r#"{"ok":false}"#);

    unsafe { emu_free(emulator) };
}

#[test]
fn two_emulators_share_no_state() {
    let first = emu_new();
    let second = emu_new();
    let list = |emulator| json("kms", "TrentService.ListKeys", "1.1", "{}", emulator).body;

    json("kms", "TrentService.CreateKey", "1.1", "{}", first);
    assert!(list(first).contains("KeyId"));
    assert!(!list(second).contains("KeyId"), "{}", list(second));

    unsafe {
        emu_free(first);
        emu_free(second);
    }
}

#[test]
fn a_malformed_request_is_refused_without_touching_out() {
    let emulator = emu_new();
    let method = CString::new("POST").unwrap();
    let uri = CString::new("/").unwrap();
    let headers = b"no-colon-here\r\n";
    let mut out = std::mem::MaybeUninit::<EmuResponse>::uninit();
    let code = unsafe {
        emu_dispatch(
            emulator,
            method.as_ptr(),
            uri.as_ptr(),
            headers.as_ptr(),
            headers.len(),
            std::ptr::null(),
            0,
            out.as_mut_ptr(),
        )
    };
    assert_eq!(code, -1);
    unsafe { emu_free(emulator) };
}
