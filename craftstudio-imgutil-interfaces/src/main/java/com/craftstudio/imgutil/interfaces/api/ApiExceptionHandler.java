package com.craftstudio.imgutil.interfaces.api;

import com.craftstudio.imgutil.application.command.TaskException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    @ExceptionHandler(TaskException.class)
    public ResponseEntity<ApiResponse<Void>> task(TaskException e) {
        var builder = ResponseEntity.status(e.status());
        if (e.status() == 429 || e.status() == 503) builder.header("Retry-After", "5");
        return builder.body(ApiResponse.error(e.code(), e.getMessage()));
    }
    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ApiResponse<Void>> invalid(Exception e) {
        log.debug("无效的图形请求", e);
        return ResponseEntity.badRequest().body(ApiResponse.error("INVALID_REQUEST", e instanceof IllegalArgumentException ? e.getMessage() : "JSON 请求无效"));
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> internal(Exception e) {
        log.error("图形接口异常", e);
        return ResponseEntity.internalServerError().body(ApiResponse.error("INTERNAL_ERROR", "服务内部错误，请检查日志"));
    }
}
