package com.vertispan.grpc.fetch;

import elemental2.core.Uint8Array;
import jsinterop.base.JsPropertyMap;

/**
 * Callbacks for the transport implementation to signal when something happens with the underlying call.
 */
public interface TransportCallbacks {
    /**
     * Signal that headers have been received from the server.
     *
     * @param status the HTTP status code
     * @param headers the headers received from the server
     */
    void onHeaders(int status, JsPropertyMap<String> headers);

    /**
     * Signal that a chunk of data has been received from the server.
     *
     * @param chunk the raw data read from the server
     */
    void onChunk(Uint8Array chunk);

    /**
     * Signal that the stream has ended, optionally providing an error.
     *
     * @param error the error that ended the stream, or null if there is no error
     */
    void onEnd(Object error);
}
