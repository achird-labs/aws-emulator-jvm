//! In-process aws-emulator for hosts that cannot link Rust: DynamoDB, S3 and KMS (with the IAM
//! and STS services KMS reads its policies from) behind a byte-level C ABI.
//!
//! A host builds one [`Emulator`] with [`emu_new`], hands it whole HTTP requests through
//! [`emu_dispatch`] and frees what it got back with [`emu_response_free`] and [`emu_free`]. No
//! socket is bound and nothing is encrypted: the request goes straight to the router the
//! standalone binary serves.
//!
//! Usage telemetry is never started, so nothing leaves the process.

use bytes::Bytes;
use engine::InMemoryEngine;
use smithy_server_runtime::MultiServiceRouter;
use std::ffi::{c_char, CStr};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::Arc;

/// Upstream's own figure for the thread that assembles the router: its generated deserializers
/// are deep enough to overflow a default-sized stack.
const ASSEMBLY_STACK_SIZE: usize = 64 * 1024 * 1024;

/// Upstream's figure for the threads that handle requests.
const WORKER_STACK_SIZE: usize = 16 * 1024 * 1024;

/// One emulator over a fresh in-memory engine. Two emulators never share state.
pub struct Emulator {
    router: MultiServiceRouter,
    runtime: tokio::runtime::Runtime,
}

impl Emulator {
    /// Assembles the router on a thread sized for it, as upstream's binary does.
    pub fn new() -> std::io::Result<Self> {
        let router = std::thread::Builder::new()
            .name("emu-assembly".to_string())
            .stack_size(ASSEMBLY_STACK_SIZE)
            .spawn(build_router)?
            .join()
            .map_err(|_| std::io::Error::other("assembling the router panicked"))?;
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .thread_name("emu-worker")
            .thread_stack_size(WORKER_STACK_SIZE)
            .enable_all()
            .build()?;
        Ok(Self { router, runtime })
    }

    /// Handles one buffered request. Safe to call from several threads at once.
    pub fn dispatch(&self, request: http::Request<Bytes>) -> http::Response<Bytes> {
        let router = self.router.clone();
        self.runtime
            .block_on(
                async move { tokio::spawn(async move { router.dispatch(request).await }).await },
            )
            .unwrap_or_else(|join_error| {
                plain_response(500, format!("the emulator panicked: {join_error}"))
            })
    }
}

fn build_router() -> MultiServiceRouter {
    let engine = Arc::new(InMemoryEngine::new());
    let mut router = MultiServiceRouter::new();
    router
        .register_service(
            service_com_amazonaws_dynamodb_dynamodb_20120810::EmulatedService::new(engine.clone()),
        )
        .register_service(service_com_amazonaws_s3_amazons3::EmulatedService::new(
            engine.clone(),
        ))
        .register_service(
            service_com_amazonaws_kms_trentservice::EmulatedService::new(engine.clone()),
        )
        .register_service(
            service_com_amazonaws_iam_awsidentitymanagementv20100508::EmulatedService::new(
                engine.clone(),
            ),
        )
        .register_service(
            service_com_amazonaws_sts_awssecuritytokenservicev20110615::EmulatedService::new(
                engine,
            ),
        )
        .set_request_authentication(false);
    router
}

fn plain_response(status: u16, message: String) -> http::Response<Bytes> {
    let mut response = http::Response::new(Bytes::from(message));
    *response.status_mut() =
        http::StatusCode::from_u16(status).unwrap_or(http::StatusCode::INTERNAL_SERVER_ERROR);
    response
}

/// A response handed to the host. `headers` is `name: value\r\n` lines. Both buffers belong to
/// the emulator until [`emu_response_free`].
#[repr(C)]
pub struct EmuResponse {
    pub status: u16,
    pub headers: *mut u8,
    pub headers_len: usize,
    pub body: *mut u8,
    pub body_len: usize,
}

/// A new emulator, or null if it could not start.
#[no_mangle]
pub extern "C" fn emu_new() -> *mut Emulator {
    match catch_unwind(Emulator::new) {
        Ok(Ok(emulator)) => Box::into_raw(Box::new(emulator)),
        _ => std::ptr::null_mut(),
    }
}

/// Frees an emulator from [`emu_new`]. Null is ignored.
///
/// # Safety
/// `emulator` is null or came from [`emu_new`], is not used again, and no call on it is running.
#[no_mangle]
pub unsafe extern "C" fn emu_free(emulator: *mut Emulator) {
    if !emulator.is_null() {
        drop(Box::from_raw(emulator));
    }
}

/// Handles one request. `method` and `uri` are NUL-terminated; `headers` is `headers_len` bytes
/// of `name: value\r\n` lines. Returns 0 and fills `out`, or -1 if the request could not be
/// read, in which case `out` is untouched.
///
/// # Safety
/// `emulator` came from [`emu_new`]; every pointer is valid for its length (a length of 0 allows
/// null); `out` is writable.
#[no_mangle]
pub unsafe extern "C" fn emu_dispatch(
    emulator: *const Emulator,
    method: *const c_char,
    uri: *const c_char,
    headers: *const u8,
    headers_len: usize,
    body: *const u8,
    body_len: usize,
    out: *mut EmuResponse,
) -> i32 {
    if emulator.is_null() || method.is_null() || uri.is_null() || out.is_null() {
        return -1;
    }
    let outcome = catch_unwind(AssertUnwindSafe(|| {
        let request = read_request(
            CStr::from_ptr(method),
            CStr::from_ptr(uri),
            slice(headers, headers_len),
            slice(body, body_len),
        )?;
        Some((*emulator).dispatch(request))
    }));
    match outcome {
        Ok(Some(response)) => {
            out.write(into_emu_response(response));
            0
        }
        _ => -1,
    }
}

/// Frees the buffers of a response filled by [`emu_dispatch`].
///
/// # Safety
/// `response` was filled by [`emu_dispatch`] and is not freed twice.
#[no_mangle]
pub unsafe extern "C" fn emu_response_free(response: *mut EmuResponse) {
    if response.is_null() {
        return;
    }
    let response = &mut *response;
    free_buffer(response.headers, response.headers_len);
    free_buffer(response.body, response.body_len);
    response.headers = std::ptr::null_mut();
    response.body = std::ptr::null_mut();
}

unsafe fn slice<'a>(pointer: *const u8, len: usize) -> &'a [u8] {
    if len == 0 {
        &[]
    } else {
        std::slice::from_raw_parts(pointer, len)
    }
}

fn read_request(
    method: &CStr,
    uri: &CStr,
    headers: &[u8],
    body: &[u8],
) -> Option<http::Request<Bytes>> {
    let mut builder = http::Request::builder()
        .method(method.to_str().ok()?)
        .uri(uri.to_str().ok()?);
    for line in std::str::from_utf8(headers)
        .ok()?
        .split("\r\n")
        .filter(|line| !line.is_empty())
    {
        let (name, value) = line.split_once(':')?;
        builder = builder.header(name.trim(), value.trim());
    }
    builder.body(Bytes::copy_from_slice(body)).ok()
}

fn into_emu_response(response: http::Response<Bytes>) -> EmuResponse {
    let (parts, body) = response.into_parts();
    let mut headers = Vec::new();
    for (name, value) in &parts.headers {
        headers.extend_from_slice(name.as_str().as_bytes());
        headers.extend_from_slice(b": ");
        headers.extend_from_slice(value.as_bytes());
        headers.extend_from_slice(b"\r\n");
    }
    let (headers, headers_len) = leak(headers);
    let (body, body_len) = leak(body.to_vec());
    EmuResponse {
        status: parts.status.as_u16(),
        headers,
        headers_len,
        body,
        body_len,
    }
}

fn leak(bytes: Vec<u8>) -> (*mut u8, usize) {
    let boxed = bytes.into_boxed_slice();
    let len = boxed.len();
    (Box::into_raw(boxed).cast::<u8>(), len)
}

unsafe fn free_buffer(pointer: *mut u8, len: usize) {
    if !pointer.is_null() {
        drop(Box::from_raw(std::ptr::slice_from_raw_parts_mut(
            pointer, len,
        )));
    }
}
