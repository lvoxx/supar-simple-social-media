# syntax=docker/dockerfile:1
#
# Build context = apps/go (the single Go module shared by all Go services):
#   docker build -f engagement-service/Dockerfile -t engagement-service apps/go
#
FROM golang:1.26 AS build
WORKDIR /src
ENV CGO_ENABLED=0 GOOS=linux
COPY go.mod go.sum* ./
RUN go mod download
COPY . .
RUN go build -trimpath -ldflags="-s -w" -o /out/engagement-service ./engagement-service

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/engagement-service /engagement-service
EXPOSE 8085
USER nonroot:nonroot
ENTRYPOINT ["/engagement-service"]
