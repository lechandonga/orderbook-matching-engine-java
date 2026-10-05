package com.github.lechandonga.orderbookmatchingenginejava.api;

import com.github.lechandonga.orderbookmatchingenginejava.domain.RejectReason;
import com.github.lechandonga.orderbookmatchingenginejava.event.LedgerCorruptedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(OrderRejectedException.class)
    public ResponseEntity<Map<String, Object>> handleReject(OrderRejectedException exception) {
        RejectReason reason = exception.getReason();
        HttpStatus status = switch (reason) {
            case MISSING_FIELD, MARKET_PRICE_NOT_ALLOWED, INVALID_PRICE, INVALID_QUANTITY ->
                    HttpStatus.BAD_REQUEST;
            case DUPLICATE_ORDER_ID -> HttpStatus.CONFLICT;
            case ORDER_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case ORDER_NOT_ACTIVE, CANCEL_ACCOUNT_MISMATCH -> HttpStatus.CONFLICT;
            case UNKNOWN_ACCOUNT, UNKNOWN_SYMBOL -> HttpStatus.UNPROCESSABLE_ENTITY;
            case INSUFFICIENT_CASH, EXPOSURE_LIMIT_EXCEEDED, INSUFFICIENT_POSITION,
                    MARKET_NOT_TRADEABLE -> HttpStatus.UNPROCESSABLE_ENTITY;
            case LEDGER_CORRUPTED -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return ResponseEntity.status(status).body(body(reason, reason.getDescription()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
                .body(body(RejectReason.MISSING_FIELD, "请求体无法解析或字段类型错误"));
    }

    @ExceptionHandler(LedgerCorruptedException.class)
    public ResponseEntity<Map<String, Object>> handleCorrupt(LedgerCorruptedException e) {
        return ResponseEntity.internalServerError()
                .body(body(RejectReason.LEDGER_CORRUPTED, e.getMessage()));
    }

    private static Map<String, Object> body(RejectReason reason, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accepted", false);
        body.put("rejectReason", reason.name());
        body.put("rejectMessage", message);
        return body;
    }
}
