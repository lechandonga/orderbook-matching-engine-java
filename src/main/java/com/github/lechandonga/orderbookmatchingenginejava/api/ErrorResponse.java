package com.github.lechandonga.orderbookmatchingenginejava.api;

import com.github.lechandonga.orderbookmatchingenginejava.model.RejectReason;

public record ErrorResponse(RejectReason code, String message) {
}
