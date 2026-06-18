# syntax=docker/dockerfile:1
#
# Build context = apps/go (the single Go module shared by all Go services):
#   docker build -f messaging-service/Dockerfile -t messaging-service apps/go
#
FROM golang:1.26 AS build
WORKDIR /src
ENV CGO_ENABLED=0 GOOS=linux
COPY go.mod go.sum* ./
RUN go mod download
COPY . .
RUN go build -trimpath -ldflags="-s -w" -o /out/messaging-service ./messaging-service

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/messaging-service /messaging-service
EXPOSE 8087
USER nonroot:nonroot
ENTRYPOINT ["/messaging-service"]
