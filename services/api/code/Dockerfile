FROM rust:bookworm AS builder
WORKDIR /app
COPY code/bluetext-model/ ./code/bluetext-model/
COPY code/bluetext-model-core/ ./code/bluetext-model-core/
COPY code/bluetext-model-macros/ ./code/bluetext-model-macros/
COPY code/clients/ ./code/clients/
COPY model/ ./model/
COPY code/services/api/ ./code/services/api/
WORKDIR /app/code/services/api
RUN cargo build --release

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y ca-certificates && rm -rf /var/lib/apt/lists/*
COPY --from=builder /app/code/services/api/target/release/api /usr/local/bin/api
ENV PORT=3030
EXPOSE 3030
CMD ["api"]
