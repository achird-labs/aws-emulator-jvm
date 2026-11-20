//! In-process aws-emulator: DynamoDB, S3 and KMS (with the IAM and STS services KMS reads its
//! policies from) behind one router that takes whole buffered HTTP requests.
//!
//! No socket is bound and nothing is encrypted: the request goes straight to the router the
//! standalone binary serves.
//!
//! Usage telemetry is never started, so nothing leaves the process.

use bytes::Bytes;
use engine::InMemoryEngine;
use smithy_server_runtime::MultiServiceRouter;
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
