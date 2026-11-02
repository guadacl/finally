package com.finallyapp.error;

import com.finallyapp.api.model.Error;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Error> api(ApiException e) {
        return body(e.status(), e.code(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Error> invalid(MethodArgumentNotValidException e) {
        FieldError fe = e.getBindingResult().getFieldError();
        String field = fe != null ? fe.getField() : "";
        String code = switch (field) {
            case "quantity" -> ErrorCodes.INVALID_QUANTITY;
            case "targetPrice" -> ErrorCodes.INVALID_PRICE;
            case "ticker" -> ErrorCodes.INVALID_TICKER;
            default -> ErrorCodes.INVALID_REQUEST;
        };
        String msg = fe != null ? field + ": " + fe.getDefaultMessage() : "Invalid request";
        return body(HttpStatus.BAD_REQUEST, code, msg);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<Error> invalidMethod(HandlerMethodValidationException e) {
        return body(HttpStatus.BAD_REQUEST, ErrorCodes.INVALID_REQUEST, "Invalid request");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Error> unreadable(HttpMessageNotReadableException e) {
        return body(HttpStatus.BAD_REQUEST, ErrorCodes.INVALID_REQUEST, "Malformed or invalid JSON body");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Error> typeMismatch(MethodArgumentTypeMismatchException e) {
        return body(HttpStatus.BAD_REQUEST, ErrorCodes.INVALID_REQUEST, "Invalid value for " + e.getName());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<Error> mediaType(HttpMediaTypeNotSupportedException e) {
        return body(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ErrorCodes.INVALID_REQUEST, e.getMessage());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<Error> method(HttpRequestMethodNotSupportedException e) {
        return body(HttpStatus.METHOD_NOT_ALLOWED, ErrorCodes.INVALID_REQUEST, e.getMessage());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<Error> noResource(NoResourceFoundException e) {
        return body(HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND, "Not found: /" + e.getResourcePath());
    }

    /** SSE client went away: nothing to write back. */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    void asyncGone(AsyncRequestNotUsableException e) {
        log.debug("Async request no longer usable: {}", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Error> unexpected(Exception e) {
        log.error("Unhandled error", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCodes.INTERNAL_ERROR, "Unexpected server error");
    }

    private static ResponseEntity<Error> body(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(new Error(code, message));
    }
}
