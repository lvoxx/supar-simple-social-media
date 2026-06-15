# syntax=docker/dockerfile:1
#
# Build context = apps/go (the single Go module shared by all Go services):
#   docker build -f timeline-service/Dockerfile -t timeline-service apps/go
#
FROM golang:1.26 AS build
WORKDIR /src
ENV CGO_ENABLED=0 GOOS=linux
COPY go.mod go.sum* ./
RUN go mod download
COPY . .
RUN go build -trimpath -ldflags="-s -w" -o /out/timeline-service ./timeline-service

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/timeline-service /timeline-service
EXPOSE 8080
USER nonroot:nonroot
ENTRYPOINT ["/timeline-service"]
