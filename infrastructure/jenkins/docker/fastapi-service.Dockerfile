# syntax=docker/dockerfile:1
# Dockerfile — FastAPI / AI service (multi-stage Python)
# Place this file at: python-services/<service-name>/Dockerfile
#
#  ────────────────────────────────────────────────────────────────────────────────────────────────
# | ONLY USED FOR REFERENCE BUILDING IN JENKINS;                                                   |
# | NOT USED BY JENKINS PIPELINE (which builds from repo root with contextPath set to service dir) |
#  ────────────────────────────────────────────────────────────────────────────────────────────────
#
# Build args:
#   SERVICE_NAME, IMAGE_TAG, BUILD_DATE, VCS_REF

# ── Stage 1: Dependency builder ───────────────────────────────────────────────
FROM python:3.12-slim AS builder

# Install build tools needed for native extensions (e.g. psycopg2-binary, numpy)
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
        build-essential \
        libpq-dev \
        curl \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /install

# Copy requirements first for layer cache
COPY requirements.txt ./

# Install into a prefix directory so we can COPY it cleanly to the final stage
RUN pip install --upgrade pip \
 && pip install --prefix=/install/packages \
                --no-cache-dir \
                -r requirements.txt

# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
FROM python:3.12-slim AS runtime

# Security: minimal runtime deps, no build tools
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
        libpq5 \
        curl \
 && rm -rf /var/lib/apt/lists/*

# Run as non-root
RUN groupadd --system --gid 1001 sssm \
 && useradd  --system --uid 1001 --gid sssm --no-create-home appuser

WORKDIR /app

# Copy installed packages from builder
COPY --from=builder /install/packages /usr/local

# Copy application source
COPY app/ ./app/

# OCI labels
ARG BUILD_DATE
ARG VCS_REF
ARG SERVICE_NAME
ARG IMAGE_TAG
LABEL org.opencontainers.image.created="${BUILD_DATE}"  \
      org.opencontainers.image.revision="${VCS_REF}"    \
      org.opencontainers.image.title="${SERVICE_NAME}"  \
      org.opencontainers.image.version="${IMAGE_TAG}"   \
      maintainer="platform-team@sssm.com"

USER appuser

# Uvicorn — workers controlled by UVICORN_WORKERS env var (default 2)
# Port controlled by SERVER_PORT env var (default 8090)
ENV UVICORN_WORKERS=2  \
    SERVER_PORT=8090   \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

EXPOSE 8090

HEALTHCHECK --interval=30s --timeout=10s --start-period=90s --retries=3 \
    CMD curl -sf http://localhost:${SERVER_PORT}/api/v1/health || exit 1

CMD ["sh", "-c", \
     "uvicorn app.main:app \
      --host 0.0.0.0 \
      --port ${SERVER_PORT} \
      --workers ${UVICORN_WORKERS} \
      --loop uvloop \
      --access-log \
      --log-level info"]
