FROM rust:bookworm AS builder
WORKDIR /app
COPY clients/ ./clients/
COPY models/ ./models/
COPY services/api/ ./services/api/
WORKDIR /app/services/api
RUN cargo build --release

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y ca-certificates && rm -rf /var/lib/apt/lists/*
COPY --from=builder /app/services/api/target/release/api /usr/local/bin/api
ENV PORT=3030
EXPOSE 3030
CMD ["api"]
