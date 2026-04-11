package org.buaa.rag.common.web;

import java.util.List;

import org.buaa.rag.common.enums.BaseErrorCode;
import org.buaa.rag.common.convention.exception.AbstractException;
import org.buaa.rag.common.convention.result.Result;
import org.buaa.rag.common.convention.result.Results;
import org.springframework.stereotype.Component;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * 全局异常拦截，将各类异常统一转换为 {@link Result} 响应。
 */
@Slf4j
@Component("globalExceptionHandlerByAdmin")
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<?> handleValidation(HttpServletRequest req, MethodArgumentNotValidException ex) {
        List<FieldError> fieldErrors = ex.getBindingResult().getFieldErrors();
        String detail = fieldErrors.isEmpty() ? "参数校验失败" : fieldErrors.get(0).getDefaultMessage();
        log.error("[{}] {} 参数校验异常: {}", req.getMethod(), fullUrl(req), detail);
        return Results.failure(BaseErrorCode.CLIENT_ERROR.code(), detail != null ? detail : "参数校验失败");
    }

    @ExceptionHandler(AbstractException.class)
    public Result<?> handleBusiness(HttpServletRequest req, AbstractException ex) {
        log.error("[{}] {} 业务异常: {}", req.getMethod(), req.getRequestURL(), ex.toString(), ex.getCause());
        return Results.failure(ex);
    }

    @ExceptionHandler(Throwable.class)
    public Result<?> handleUnexpected(HttpServletRequest req, Throwable ex) {
        log.error("[{}] {} 未知异常", req.getMethod(), fullUrl(req), ex);
        if (ex instanceof AbstractException biz) {
            return Results.failure(biz);
        }
        return Results.failure();
    }

    private String fullUrl(HttpServletRequest req) {
        String qs = req.getQueryString();
        return (qs == null || qs.isEmpty())
                ? req.getRequestURL().toString()
                : req.getRequestURL().append('?').append(qs).toString();
    }
}
