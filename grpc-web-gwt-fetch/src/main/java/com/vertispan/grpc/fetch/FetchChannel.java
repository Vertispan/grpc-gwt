package com.vertispan.grpc.fetch;

import elemental2.core.JsArray;
import elemental2.core.JsIIterableResult;
import elemental2.core.JsIteratorIterable;
import elemental2.core.Uint8Array;
import elemental2.dom.AbortController;
import elemental2.dom.Blob;
import elemental2.dom.DomGlobal;
import elemental2.dom.Headers;
import elemental2.dom.ReadableStreamDefaultReader;
import elemental2.dom.RequestInit;
import elemental2.dom.URL;
import jsinterop.base.JsPropertyMap;

/**
 * Browser implementation of gRPC channel, using fetch to implement grpc-web. This implementation is only suitable for
 * unary and server-streaming calls, as fetch does not support client streaming in a useful way.
 * <p>
 * Unlike conventional Channel implementations, it does not matter if the channel is shared or not, as the browser itself
 * will handle h2 internally - it isn't even visible to this class if h2 is in use or not. It is still a best practice
 * to reuse the channel where possible, to allow for changing to a different implementation as appropriate.
 */
public class FetchChannel extends AbstractGrpcWebChannel {

    /**
     * Creates an instance to communicate with the given server. The path component will be ignored, in favor of
     * the particular gRPC service being called.
     *
     * @param server the server URL
     */
    public FetchChannel(URL server) {
        super(server);
    }

    @Override
    protected Transport createTransport(String method, URL url, TransportCallbacks callbacks) {
        // A fetch()-based transport needs to accumulate the URL, headers, and first/only message before invoking
        // fetch(), at least until browsers support a client that can both stream sending and receiving at the same
        // time. Once finishSend() is called, we send the accumulated messages (should be exactly one).
        final RequestInit init = RequestInit.create();

        init.setMethod(method);
        final AbortController abortController = new AbortController();
        init.setSignal(abortController.signal);
        JsArray<Blob.ConstructorBlobPartsArrayUnionType> messages = JsArray.of();

        return new Transport() {
            @Override
            public void start(JsPropertyMap<String> metadata) {
                init.setHeaders(metadata);
            }

            @Override
            public void sendMessage(Uint8Array msgBytes) {
                messages.push(Blob.ConstructorBlobPartsArrayUnionType.of(msgBytes));
            }

            @Override
            public void finishSend() {
                init.setBody(new Blob(messages));
                DomGlobal.fetch(url, init).then(response -> {
                    if (!abortController.signal.aborted) {
                        callbacks.onHeaders(response.status, readHeaders(response.headers));
                        assert response.body != null : "Browser should always include a response body";
                        if (response.status != 200) {
                            callbacks.onEnd("HTTP error " + response.status + ": " + response.statusText);
                        } else {
                            // gRPC server only sends a body with 200 status
                            this.readFromStream(response.body.getReader().asReadableStreamDefaultReader());
                        }
                    }
                    return null;
                }).catch_(error -> {
                    if (!abortController.signal.aborted) {
                        callbacks.onEnd("fetch() failed: " + error);
                        abortController.abort();
                    }
                    return null;
                });
            }

            @Override
            public void cancel() {
                abortController.abort();
            }

            /**
             * Continue reading from the stream until done.
             *
             * @param reader   the stream to read from
             */
            private void readFromStream(final ReadableStreamDefaultReader<Uint8Array> reader) {
                if (abortController.signal.aborted) {
                    return;
                }

                reader.read().then(result -> {
                    if (result.isDone()) {
                        if (!abortController.signal.aborted) {
                            callbacks.onEnd("Stream closed without trailers");
                            abortController.abort();
                        }
                        return null;
                    }

                    callbacks.onChunk(result.getValue());

                    //read the next chunk
                    readFromStream(reader);
                    return null;
                }, fail -> {
                    if (!abortController.signal.aborted) {
                        callbacks.onEnd("Stream closed without trailers");
                        abortController.abort();
                    }
                    return null;
                });
            }
        };
    }

    private JsPropertyMap<String> readHeaders(final Headers headers) {
        JsPropertyMap<String> headersMap = JsPropertyMap.of();
        final JsIteratorIterable<String, Object, Object> keys = headers.keys();
        JsIIterableResult<String> next = keys.next();
        while (!next.isDone()) {
            final String key = next.getValue();
            final String value = headers.get(key);
            headersMap.set(key, value);
            next = keys.next();
        }
        return headersMap;
    }
}
