package com.vertispan.grpc.fetch;

/**
 * Exception to signal that trailers have already been received from the server, and no further data is expected.
 */
public class TrailersFinishedException extends Exception {
    public TrailersFinishedException(String message) {
        super(message);
    }
}
