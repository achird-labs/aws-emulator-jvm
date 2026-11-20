//! Drives the emulator only through its C ABI, as a JVM binding will.

use emu_core::{emu_dispatch, emu_free, emu_new, emu_response_free, EmuResponse, Emulator};
use std::ffi::CString;

struct Reply {
    status: u16,
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
