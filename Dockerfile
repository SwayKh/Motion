# Multi-stage build for CineCut
FROM golang:1.26-bookworm AS builder

WORKDIR /app

# Copy go mod and sum first for caching
COPY go.mod go.sum ./
RUN go mod download

# Copy source
COPY . .

# Build
RUN CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -ldflags="-s -w" -o cinecut .

# Runtime stage
FROM debian:bookworm-slim

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && \
    apt-get install -y --no-install-recommends ca-certificates ffmpeg && \
    rm -rf /var/lib/apt/lists/*

# Create non-root user
RUN groupadd -r cinecut && useradd -r -g cinecut -m -d /app cinecut

WORKDIR /app

# Copy binary and static files
COPY --from=builder /app/cinecut /app/cinecut
COPY --from=builder /app/web /app/web

# Create data directories
RUN mkdir -p /app/data /app/uploads /app/outputs && \
    chown -R cinecut:cinecut /app

USER cinecut

EXPOSE 8080

ENV PORT=8080
ENV APP_ENV=production
ENV SECURE_COOKIES=true

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/health || exit 1

ENTRYPOINT ["/app/cinecut"]
