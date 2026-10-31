package com.hmdp.controller.platform;

import com.hmdp.dto.Result;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

/** 仅约束平台输入，不改变已有商户端和消费者端的错误约定。 */
@RestControllerAdvice(basePackages = "com.hmdp.controller.platform")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PlatformExceptionAdvice {
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result invalid(IllegalArgumentException error) {
        return Result.fail(error.getMessage());
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result invalidJson() {
        return Result.fail("请求格式错误，请仅提交该接口允许的字段");
    }
}
