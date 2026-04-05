FROM rust:bookworm AS builder
RUN apt-get update && apt-get install -y pkg-config libssl-dev git && rm -rf /var/lib/apt/lists/*
WORKDIR /build
RUN git clone --depth 1 https://github.com/resonatehq/resonate.git .
RUN cargo build --release

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y ca-certificates && rm -rf /var/lib/apt/lists/*
COPY --from=builder /build/target/release/resonate /usr/local/bin/resonate
EXPOSE 8001 9090
CMD ["resonate", "serve", "--server-bind", "0.0.0.0", "--server-port", "8001"]
