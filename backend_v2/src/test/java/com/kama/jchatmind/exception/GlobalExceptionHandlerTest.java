package com.kama.jchatmind.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GlobalExceptionHandlerTest {

    @Test
    void shouldReturn503ForAsyncRequestTimeout() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ResponseEntity<Void> response = handler.handleAsyncRequestTimeout(new AsyncRequestTimeoutException());

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertNull(response.getBody());
    }
}
