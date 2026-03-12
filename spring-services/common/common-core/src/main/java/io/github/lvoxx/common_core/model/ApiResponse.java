package io.github.lvoxx.common_core.model;

public record ApiResponse<T>(
        boolean success,
        T data,
        ErrorResponse error,
        ApiMeta meta) {
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, data, null, ApiMeta.now());
    }

    public static <T> ApiResponse<T> error(ErrorResponse error) {
        return new ApiResponse<>(false, null, error, ApiMeta.now());
    }

    public static ApiResponse<Void> successEmpty() {
        return new ApiResponse<>(true, null, null, ApiMeta.now());
    }
}
