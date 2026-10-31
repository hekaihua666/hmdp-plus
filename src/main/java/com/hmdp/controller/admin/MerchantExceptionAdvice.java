package com.hmdp.controller.admin;

import com.hmdp.dto.Result;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

/** 仅处理后台输入错误，避免修改已有 C 端异常响应约定。 */
@RestControllerAdvice(basePackages = "com.hmdp.controller.admin")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MerchantExceptionAdvice {
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result invalid(IllegalArgumentException exception) { return Result.fail(exception.getMessage()); }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result invalidJson() { return Result.fail("请求格式错误，请仅提交允许编辑的店铺字段"); }
}
