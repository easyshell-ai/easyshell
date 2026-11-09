package com.easyshell.server.common.exception;

import com.easyshell.server.common.exception.WorkflowRiskBlockedException;
import com.easyshell.server.common.exception.WorkflowValidationFailedException;
import com.easyshell.server.common.exception.WorkflowVersionConflictException;
import com.easyshell.server.common.result.R;
import com.easyshell.server.model.vo.WorkflowRiskBlockedVO;
import com.easyshell.server.model.vo.WorkflowValidateResultVO;
import com.easyshell.server.model.vo.WorkflowVersionConflictVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Business failures carry the HTTP status they should surface with. Returning HTTP 200
     * with code=401 in the body hides brute force and authorization failures from any
     * monitoring that looks at status codes; the body still carries the full envelope so the
     * frontend can show the message.
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<R<Void>> handleBusinessException(BusinessException e) {
        log.warn("Business exception: {}", e.getMessage());
        R<Void> body = R.fail(e.getCode(), e.getMessage());
        HttpStatus status = resolveStatus(e.getCode());
        return ResponseEntity.status(status).body(body);
    }

    @ExceptionHandler(AuthorizationDeniedException.class)
    public ResponseEntity<R<Void>> handleAuthorizationDenied(AuthorizationDeniedException e) {
        log.warn("Authorization denied: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(R.fail(403, "Access denied"));
    }

    /**
     * 草稿乐观锁冲突：HTTP 409 + 三选一数据契约（§6.1）。
     * 响应体只有契约本身，不含任何定义内容（避免把他人草稿回显给冲突方）。
     */
    @ExceptionHandler(WorkflowVersionConflictException.class)
    public ResponseEntity<R<WorkflowVersionConflictVO>> handleVersionConflict(
            WorkflowVersionConflictException e) {
        log.warn("Workflow draft version conflict: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(R.of(409, "VERSION_CONFLICT: " + e.getConflict().getMessage(), e.getConflict()));
    }

    /**
     * 发布校验未通过：HTTP 422 + issues[]（§6.1 / §6.2 WORKFLOW_VALIDATION_FAILED）。
     */
    @ExceptionHandler(WorkflowValidationFailedException.class)
    public ResponseEntity<R<WorkflowValidateResultVO>> handleValidationFailed(
            WorkflowValidationFailedException e) {
        log.warn("Workflow validation failed: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(R.of(422, e.getMessage(), e.getResult()));
    }

    /**
     * 风险门禁：449 响应体（只含等级与模式串，禁止命令原文）。
     * HTTP 200 + body code 449，与 TaskController 的既有 449 约定一致。
     */
    @ExceptionHandler(WorkflowRiskBlockedException.class)
    public ResponseEntity<R<WorkflowRiskBlockedVO>> handleRiskBlocked(WorkflowRiskBlockedException e) {
        log.warn("Workflow risk gate blocked: {}", e.getMessage());
        return ResponseEntity.ok(R.of(449, e.getMessage(), e.getBlocked()));
    }

    private static HttpStatus resolveStatus(int code) {
        if (code >= 400 && code <= 599) {
            try {
                return HttpStatus.valueOf(code);
            } catch (IllegalArgumentException ignored) {
                // fall through
            }
        }
        return HttpStatus.OK;
    }

    /** Unknown path or endpoint: 404, not an internal error. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<R<Void>> handleNoResource(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(R.fail(404, "Not found"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation failed");
        return R.fail(400, message);
    }

    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleBindException(BindException e) {
        String message = e.getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Bind failed");
        return R.fail(400, message);
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public R<Void> handleException(Exception e) {
        log.error("Unexpected exception", e);
        return R.fail("Internal server error");
    }
}
