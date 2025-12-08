package com.vertispan.grpc.fetch;

import elemental2.core.Uint8Array;
import jsinterop.base.JsPropertyMap;

/**
 * JsInterop-based transport impl, to get as close as possible to the native APIs that will back the transport. This
 * API makes the assumption that it will be used for grpc-web, so the trailers (if present) will be sent as a payload,
 * not as headers at the end of the stream.
 */
public interface Transport {

    /**
     * Starts the stream, sending metadata to the server.
     *
     * @param metadata the headers to send the server when opening the connection
     */
    void start(JsPropertyMap<String> metadata);

    /**
     * Sends a message to the server.
     *
     * @param msgBytes bytes to send to the server
     */
    void sendMessage(Uint8Array msgBytes);

    /**
     * "Half close" the stream, signaling to the server that no more messages will be sent, but that the client is still
     * open to receiving messages.
     */
    void finishSend();

    /**
     * End the stream, both notifying the server that no more messages will be sent nor received, and preventing the
     * client from receiving any more events.
     */
    void cancel();
}
