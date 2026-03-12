package io.github.lvoxx.media_service.web.handler;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

import io.github.lvoxx.common_core.model.ApiResponse;
import io.github.lvoxx.common_core.util.ReactiveContextUtil;
import io.github.lvoxx.media_service.service.MediaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

/**
 * WebFlux functional handler cho upload và quản lý media asset.
 *
 * <p>
 * Luồng xử lý upload (bất đồng bộ, không block reactor thread):
 * <ol>
 * <li>Nhận multipart file — lưu metadata vào DB với trạng thái
 * {@code PROCESSING}</li>
 * <li>Trả về 202 Accepted ngay lập tức kèm {@code mediaId}</li>
 * <li>Pipeline chạy trên {@code Schedulers.boundedElastic()}:
 * compress/transcode → safety check → upload S3 → cập nhật
 * {@code READY}</li>
 * <li>Phát sự kiện Avro {@code media.upload.completed} hoặc
 * {@code media.upload.failed}</li>
 * </ol>
 *
 * <p>
 * Giới hạn file:
 * <ul>
 * <li>Image: max 10 MB, định dạng JPEG/PNG/WEBP/GIF</li>
 * <li>Video: max 512 MB, định dạng MP4/MOV</li>
 * <li>Audio: max 50 MB, định dạng MP3/AAC/OGG</li>
 * </ul>
 *
 * @see MediaService
 */
@Component
@RequiredArgsConstructor
@Tag(name = "Media", description = "Upload và quản lý ảnh, video, audio, file đính kèm")
@SecurityRequirement(name = "bearerAuth")
public class MediaHandler {

    private final MediaService mediaService;

    /**
     * Upload file media. Xử lý bất đồng bộ — trả về ngay sau khi nhận file.
     * Dùng {@code GET /media/{mediaId}/status} để kiểm tra trạng thái xử lý.
     *
     * @param req multipart form-data với field {@code file} và optional
     *            {@code ownerType}
     *            (USER | POST | COMMENT | MESSAGE | GROUP, default USER)
     * @return 202 Accepted với
     *         {@link io.github.lvoxx.media_service.application.dto.MediaUploadResponse}
     *         chứa {@code mediaId} và trạng thái {@code PROCESSING}
     */
    @Operation(summary = "Upload media", description = "Upload file qua multipart/form-data. Xử lý bất đồng bộ — kiểm tra trạng thái "
            + "qua GET /media/{mediaId}/status. "
            + "Phát Avro `media.upload.completed` hoặc `media.upload.failed` sau khi xử lý xong.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "File đã được nhận, đang xử lý"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Định dạng file không được hỗ trợ"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "413", description = "File vượt quá giới hạn kích thước"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "Rate limit exceeded — max 20 uploads/phút")
    })
    public Mono<ServerResponse> upload(ServerRequest req) {
        return ReactiveContextUtil.getCurrentUser()
                .flatMap(p -> req.multipartData()
                        .map(mp -> (FilePart) mp.toSingleValueMap().get("file"))
                        .flatMap(file -> mediaService.upload(p.userId(), "USER", file)))
                .flatMap(r -> ServerResponse.status(HttpStatus.ACCEPTED)
                        .bodyValue(ApiResponse.success(r)));
    }

    /**
     * Lấy thông tin chi tiết của một media asset theo ID.
     *
     * @param req path: {@code mediaId} — UUID của asset
     * @return 200 với
     *         {@link io.github.lvoxx.media_service.application.dto.MediaUploadResponse},
     *         404 nếu không tồn tại hoặc đã xoá
     */
    @Operation(summary = "Lấy thông tin media", description = "Trả về metadata đầy đủ của asset: URL, kích thước, trạng thái, thời lượng (video).")
    @Parameter(name = "mediaId", in = ParameterIn.PATH, description = "UUID của media asset", required = true)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Thông tin media"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Media không tồn tại hoặc đã xoá")
    })
    public Mono<ServerResponse> getById(ServerRequest req) {
        UUID id = UUID.fromString(req.pathVariable("mediaId"));
        return mediaService.getById(id)
                .flatMap(r -> ServerResponse.ok().bodyValue(ApiResponse.success(r)));
    }

    /**
     * Kiểm tra trạng thái xử lý của một media asset.
     * Trạng thái có thể là: {@code PROCESSING | READY | REJECTED | DELETED}.
     *
     * @param req path: {@code mediaId}
     * @return 200 với MediaUploadResponse kèm trường {@code status}
     */
    @Operation(summary = "Kiểm tra trạng thái xử lý", description = "Polling endpoint — trả về trạng thái PROCESSING | READY | REJECTED | DELETED.")
    @Parameter(name = "mediaId", in = ParameterIn.PATH, description = "UUID media cần kiểm tra", required = true)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Trạng thái hiện tại của asset")
    public Mono<ServerResponse> getStatus(ServerRequest req) {
        return getById(req);
    }

    /**
     * Xoá mềm media asset. Chỉ owner hoặc ADMIN được phép.
     * Xoá trên S3 và cập nhật trạng thái thành {@code DELETED}.
     *
     * @param req path: {@code mediaId}
     * @return 204 No Content, 403 nếu không phải owner
     */
    @Operation(summary = "Xoá media", description = "Soft delete trên DB và xoá file trên S3. Chỉ owner hoặc ADMIN.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Đã xoá thành công")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Không phải owner của media này")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Media không tồn tại")
    public Mono<ServerResponse> delete(ServerRequest req) {
        UUID id = UUID.fromString(req.pathVariable("mediaId"));
        return ReactiveContextUtil.getCurrentUser()
                .flatMap(p -> mediaService.softDelete(p.userId(), id))
                .then(ServerResponse.status(HttpStatus.NO_CONTENT).build());
    }
}
