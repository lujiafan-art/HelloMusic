/*
 * HelloMusic - Local music server with UDP broadcast and REST API
 * Copyright (C) 2026 Lu Jiafan
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package core;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把「请求体不合法」这类客户端错误转成结构化的 400 响应。
 *
 * <p>Spring 默认的错误响应体里不含具体字段信息，调用方只看到一句 "Bad Request"，
 * 无法定位到底哪个字段写错了——这里补上逐字段的原因。
 *
 * <p>作用范围刻意收窄到请求体解析/校验两类异常，其余异常仍走 Spring 默认处理；
 * 全站统一异常响应是另一项独立的改造。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /** 字段校验失败：逐字段列出原因。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        List<Map<String, String>> details = e.getBindingResult().getFieldErrors().stream()
                .map(ApiExceptionHandler::describe)
                .toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "请求参数校验失败");
        body.put("details", details);
        return ResponseEntity.badRequest().body(body);
    }

    /** 请求体不是合法 JSON，或字段类型对不上（例如把端口写成字符串）。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadableBody(HttpMessageNotReadableException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "请求体不是合法的 JSON，或字段类型不匹配");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    private static Map<String, String> describe(FieldError error) {
        String message = error.getDefaultMessage();
        Map<String, String> detail = new LinkedHashMap<>();
        // getField() 对集合元素会带上下标，如 supportedExtensions[0]
        detail.put("field", error.getField());
        detail.put("message", message != null ? message : "非法取值");
        return detail;
    }
}
