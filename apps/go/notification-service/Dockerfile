# syntax=docker/dockerfile:1
#
# Build context = apps/go (the single Go module shared by all Go services):
#   docker build -f notification-service/Dockerfile -t notification-service apps/go
#
FROM golang:1.26 AS build
WORKDIR /src
ENV CGO_ENABLED=0 GOOS=linux
COPY go.mod go.sum* ./
RUN go mod download
COPY . .
RUN go build -trimpath -ldflags="-s -w" -o /out/notification-service ./notification-service

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/notification-service /notification-service
EXPOSE 8084
USER nonroot:nonroot
ENTRYPOINT ["/notification-service"]
