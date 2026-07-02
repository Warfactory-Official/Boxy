package com.golem.boxy.vss.common.processing;

public record PendingRequest(int requestId, int cx, int cz, RequestType type) {
}
