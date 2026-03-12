package io.github.lvoxx.user_service.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.github.lvoxx.user_service.dto.UpdateProfileRequest;
import io.github.lvoxx.user_service.dto.VerificationRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

class DtoValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    // ── UpdateProfileRequest ──────────────────────────────────────────────────

    @Test
    void updateProfileRequest_givenDisplayNameExceeds100Chars_hasViolation() {
        String tooLong = "a".repeat(101);
        UpdateProfileRequest req = new UpdateProfileRequest(tooLong, null, null, null, null, null);

        Set<ConstraintViolation<UpdateProfileRequest>> violations = validator.validate(req);

        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> "displayName".equals(v.getPropertyPath().toString()));
    }

    @Test
    void updateProfileRequest_givenBioExceeds500Chars_hasViolation() {
        String tooLong = "b".repeat(501);
        UpdateProfileRequest req = new UpdateProfileRequest(null, tooLong, null, null, null, null);

        Set<ConstraintViolation<UpdateProfileRequest>> violations = validator.validate(req);

        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> "bio".equals(v.getPropertyPath().toString()));
    }

    @Test
    void updateProfileRequest_givenWebsiteUrlExceeds200Chars_hasViolation() {
        String tooLong = "https://example.com/" + "x".repeat(181);
        UpdateProfileRequest req = new UpdateProfileRequest(null, null, tooLong, null, null, null);

        Set<ConstraintViolation<UpdateProfileRequest>> violations = validator.validate(req);

        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> "websiteUrl".equals(v.getPropertyPath().toString()));
    }

    @Test
    void updateProfileRequest_givenLocationExceeds100Chars_hasViolation() {
        String tooLong = "c".repeat(101);
        UpdateProfileRequest req = new UpdateProfileRequest(null, null, null, tooLong, null, null);

        Set<ConstraintViolation<UpdateProfileRequest>> violations = validator.validate(req);

        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> "location".equals(v.getPropertyPath().toString()));
    }

    @Test
    void updateProfileRequest_givenValidData_hasNoViolations() {
        UpdateProfileRequest req = new UpdateProfileRequest(
                "Alice", "Short bio", "https://alice.dev", "Hanoi", "1990-01-01", false);

        Set<ConstraintViolation<UpdateProfileRequest>> violations = validator.validate(req);

        assertThat(violations).isEmpty();
    }

    @Test
    void updateProfileRequest_givenAllNullFields_hasNoViolations() {
        UpdateProfileRequest req = new UpdateProfileRequest(null, null, null, null, null, null);

        Set<ConstraintViolation<UpdateProfileRequest>> violations = validator.validate(req);

        assertThat(violations).isEmpty();
    }

    // ── VerificationRequest ───────────────────────────────────────────────────

    @Test
    void verificationRequest_givenBlankDocumentMediaId_hasViolation() {
        VerificationRequest req = new VerificationRequest("", "IDENTITY");

        Set<ConstraintViolation<VerificationRequest>> violations = validator.validate(req);

        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> "documentMediaId".equals(v.getPropertyPath().toString()));
    }

    @Test
    void verificationRequest_givenInvalidType_hasViolation() {
        VerificationRequest req = new VerificationRequest("doc-id-123", "PERSONAL");

        Set<ConstraintViolation<VerificationRequest>> violations = validator.validate(req);

        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> "type".equals(v.getPropertyPath().toString()));
    }

    @Test
    void verificationRequest_givenValidIdentityType_hasNoViolations() {
        VerificationRequest req = new VerificationRequest("doc-id-123", "IDENTITY");

        Set<ConstraintViolation<VerificationRequest>> violations = validator.validate(req);

        assertThat(violations).isEmpty();
    }

    @Test
    void verificationRequest_givenValidBusinessType_hasNoViolations() {
        VerificationRequest req = new VerificationRequest("doc-id-456", "BUSINESS");

        Set<ConstraintViolation<VerificationRequest>> violations = validator.validate(req);

        assertThat(violations).isEmpty();
    }

    @Test
    void verificationRequest_givenBothFieldsBlank_hasTwoViolations() {
        VerificationRequest req = new VerificationRequest("", "");

        Set<ConstraintViolation<VerificationRequest>> violations = validator.validate(req);

        // @NotBlank on documentMediaId, @NotBlank + @Pattern on type
        assertThat(violations.size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void verificationRequest_givenNullDocumentMediaId_hasViolation() {
        VerificationRequest req = new VerificationRequest(null, "IDENTITY");

        Set<ConstraintViolation<VerificationRequest>> violations = validator.validate(req);

        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> "documentMediaId".equals(v.getPropertyPath().toString()));
    }
}
