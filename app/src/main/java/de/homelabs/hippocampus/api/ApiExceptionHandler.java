package de.homelabs.hippocampus.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns the domain's {@link IllegalArgumentException}s into a plain 400 with a JSON
 * error body. This is the same error surface the former tool layer had (message
 * text, no stack traces): the adapter re-raises the text as an MCP tool error, so
 * agents see the identical wording. A foreign id deliberately answers exactly like an
 * absent one — same status, same message — so the API leaks no existence information.
 */
@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(exception.getMessage()));
    }

    /** Body of every domain error; {@code error} is the message the tool layer used to raise. */
    record ErrorResponse(String error) {
    }
}
