package io.github.lvoxx.grpc_starter.config;

import io.grpc.*;
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

@Slf4j
@GrpcGlobalServerInterceptor
public class GrpcTracingInterceptor implements ServerInterceptor {

    private static final Metadata.Key<String> TRACE_ID_KEY =
            Metadata.Key.of("x-trace-id", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> SPAN_ID_KEY =
            Metadata.Key.of("x-span-id", Metadata.ASCII_STRING_MARSHALLER);

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {

        String traceId = headers.get(TRACE_ID_KEY);
        String spanId = headers.get(SPAN_ID_KEY);

        if (traceId != null) MDC.put("traceId", traceId);
        if (spanId != null) MDC.put("spanId", spanId);

        try {
            return next.startCall(call, headers);
        } finally {
            MDC.remove("traceId");
            MDC.remove("spanId");
        }
    }
}
