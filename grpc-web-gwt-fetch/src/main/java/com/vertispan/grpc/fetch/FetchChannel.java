package com.vertispan.grpc.fetch;


import com.google.common.io.BaseEncoding;
import com.google.common.util.concurrent.MoreExecutors;
import elemental2.core.DataView;
import elemental2.core.JsIIterableResult;
import elemental2.core.JsIteratorIterable;
import elemental2.core.Uint8Array;
import elemental2.dom.AbortController;
import elemental2.dom.Blob;
import elemental2.dom.DomGlobal;
import elemental2.dom.Headers;
import elemental2.dom.ReadableStreamDefaultReader;
import elemental2.dom.RequestInit;
import elemental2.dom.Response;
import elemental2.dom.URL;
import elemental2.promise.Promise;
import io.grpc.Attributes;
import io.grpc.CallCredentials;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.Context;
import io.grpc.Drainable;
import io.grpc.InternalMetadata;
import io.grpc.InternalStatus;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.SecurityLevel;
import io.grpc.Status;
import org.gwtproject.nio.TypedArrayHelper;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * Browser implementation of gRPC channel, using fetch to implement grpc-web. This implementation is only suitable for
 * unary and server-streaming calls, as fetch does not support client streaming in a useful way.
 * <p>
 * Unlike conventional Channel implementations, it does not matter if the channel is shared or not, as the browser itself
 * will handle h2 internally - it isn't even visible to this class if h2 is in use or not. It is still a best practice
 * to reuse the channel where possible, to allow for changing to a different implementation as appropriate.
 */
public class FetchChannel extends Channel {
    private static final Logger log = Logger.getLogger(FetchChannel.class.getName());
    private static final BaseEncoding BASE64 = BaseEncoding.base64().omitPadding();
    private static int nextRequestId = 0;

    private final URL server;

    /**
     * Creates an instance to communicate with the given server. The path component will be ignored, in favor of
     * the particular gRPC service being called.
     *
     * @param server the server URL
     */
    public FetchChannel(URL server) {
        this.server = server;
    }

    @Override
    public <RequestT, ResponseT> ClientCall<RequestT, ResponseT> newCall(MethodDescriptor<RequestT, ResponseT> methodDescriptor, CallOptions callOptions) {
        Metadata baseMetadata = new Metadata();
        CallCredentials.RequestInfo reqInfo = new CallCredentials.RequestInfo() {
            @Override
            public MethodDescriptor<?, ?> getMethodDescriptor() {
                return methodDescriptor;
            }

            @Override
            public SecurityLevel getSecurityLevel() {
                return null;
            }

            @Override
            public String getAuthority() {
                return server.host;
            }

            @Override
            public Attributes getTransportAttrs() {
                return null;
            }
        };
        AtomicReference<Status> failure = new AtomicReference<>(null);
        if (callOptions.getCredentials() != null) {
            //TODO this isn't async, but probably should be
            callOptions.getCredentials().applyRequestMetadata(reqInfo, callOptions.getExecutor(), new CallCredentials.MetadataApplier() {
                @Override
                public void apply(final Metadata metadata) {
                    baseMetadata.merge(metadata);
                }

                @Override
                public void fail(final Status status) {
                    failure.set(status);
                }
            });
        }

        ClientCall<RequestT, ResponseT> call = new ClientCall<>() {
            private final AbortController abortController = new AbortController();

            private final RequestInit init = RequestInit.create();
            private final URL url = new URL(server);

            private final GrpcWebMessageDeframer<ResponseT> deframer = new GrpcWebMessageDeframer<>(methodDescriptor);

            private Listener<ResponseT> responseListener;

            private boolean closed = false;
            private final int requestId = nextRequestId++;

            // Easy prefix for searching in browser logs
            private String prefix() {
                return "grpc-web[" + requestId + "]: ";
            }

            @Override
            public void start(Listener<ResponseT> responseListener, Metadata headers) {
                if (failure.get() != null) {
                    responseListener.onClose(failure.get(), new Metadata());
                    return;
                }
                this.responseListener = responseListener;
                url.pathname = methodDescriptor.getFullMethodName();

                baseMetadata.merge(headers);
                final Headers reqHeaders = makeHeaders(baseMetadata);
                init.setHeaders(reqHeaders);
                init.getHeaders().asHeaders().append("content-type", "application/grpc-web+proto");
                init.getHeaders().asHeaders().append("x-grpc-web", "1");

                // Technically, this should check methodDescriptor.isSafe(), but most of the ecosystem doesn't support
                // GET methods anyway, so we hard-code to POST.
                init.setMethod("POST");
            }

            @Override
            public void request(int numMessages) {
                // no-op, it doesn't appear browsers can do this kind of backpressure
            }

            @Override
            public void cancel(@Nullable String message, @Nullable Throwable cause) {
                abortController.abort(message);
            }

            @Override
            public void halfClose() {
                // No-op, we only support unary and server-streaming, so we half closed when we sent.
            }

            @Override
            public void sendMessage(RequestT message) {
                final List<Uint8Array> payloads = frame(message, methodDescriptor.getRequestMarshaller());
                Blob.ConstructorBlobPartsArrayUnionType[] payloadArr = new Blob.ConstructorBlobPartsArrayUnionType[payloads.size()];
                int size = 0;
                for (int i = 0; i < payloads.size(); i++) {
                    payloadArr[i] = Blob.ConstructorBlobPartsArrayUnionType.of(payloads.get(i));
                    size += payloads.get(i).length;
                }
                init.setBody(new Blob(payloadArr));
                init.setSignal(abortController.signal);
                log.fine(prefix() + "Calling " + url + " with payload size " + size);

                DomGlobal.fetch(url, init).then(response -> {
                    log.finest(prefix() + "Response " + response);
                    if (response.status == 0) {
                        // Connectivity issue - headers are garbage, just fail. Dev tools logs may have more details,
                        // such as a CORS/cert issue
                        responseListener.onClose(Status.INTERNAL.withDescription("Fetch failed, status 0"), new Metadata());
                        return null;
                    }
                    final Metadata headers = makeMetadata(response.headers);
                    responseListener.onHeaders(headers);

                    assert response.body != null : "Browser should always include a response body";
                    this.readFromStream(response.body.getReader().asReadableStreamDefaultReader(), headers, response);

                    return null;
                }).catch_(error -> {
                    responseListener.onClose(Status.UNAVAILABLE.withDescription("fetch() failed: " + error), new Metadata());
                    return null;
                });
            }

            /**
             * Continue reading from the stream until done.
             *
             * @param reader   the stream to read from
             * @param headers  headers to use if we never see trailers
             * @param response the original response object
             */
            private void readFromStream(final ReadableStreamDefaultReader<Uint8Array> reader, final Metadata headers, final Response response) {
                if (abortController.signal.aborted) {
                    return;
                }

                reader.read().then(result -> {
                    if (result.isDone()) {
                        if (closed) {
                            // nothing to do, already passed along the body and trailers
                            return null;
                        }
                        closed = true;
                        // Try to use headers to signal that we closed
                        Status status = getStatus(headers, null, response, "Response closed without trailers, headers did not indicate grpc status");
                        log.fine(prefix() + "Stream closed for " + methodDescriptor.getFullMethodName() + ", status " + status);
                        responseListener.onClose(status, headers);

                        return null;
                    }

                    try {
                        // Must be a separate assignment to avoid an incorrect cast
                        final Uint8Array value = result.getValue();
                        final List<GrpcWebMessageDeframer<ResponseT>.Payload> frames = deframer.deframe(TypedArrayHelper.wrap(value));
                        log.fine(prefix() + "Reading " + result.getValue().byteLength + " bytes from " + methodDescriptor.getFullMethodName() + ", resulting in " + frames.size() + " frames");
                        for (int i = 0; i < frames.size(); i++) {
                            final GrpcWebMessageDeframer<ResponseT>.Payload frame = frames.get(i);
                            if (frame instanceof GrpcWebMessageDeframer.Message) {
                                log.fine(prefix() + "Received message");
                                responseListener.onMessage(((GrpcWebMessageDeframer<ResponseT>.Message) frame).message);
                            } else if (frame instanceof GrpcWebMessageDeframer.Trailers) {
                                final GrpcWebMessageDeframer<ResponseT>.Trailers trailers = (GrpcWebMessageDeframer<ResponseT>.Trailers) frame;
                                log.fine(prefix() + "Received trailers" + ": " + trailers.metadata);
                                Status status = getStatus(trailers.metadata, headers, response,
                                        "Response closed without grpc status in trailers");
                                responseListener.onClose(status, trailers.metadata);
                                closed = true;
                                return null;
                            } else {
                                throw new IllegalStateException("Unknown frame type: " + frame.getClass());
                            }
                        }

                        //read the next chunk
                        readFromStream(reader, headers, response);
                        return null;
                    } catch (Exception e) {
                        // Failure while reading - close the connection and signal error to client
                        responseListener.onClose(Status.INTERNAL.withDescription("Error processing response: " + e).withCause(e), new Metadata());
                        abortController.abort(e);
                        return Promise.reject(e);
                    }
                }, fail -> {
                    if (closed) {
                        // already finished anyway
                        return null;
                    }
                    // We didn't see trailers, but see if we happened to have a status in the headers
                    Status status = getStatus(headers, null, response, "Response closed without trailers, headers did not indicate grpc status");
                    // Failure while reading - close the connection and signal error to client
                    responseListener.onClose(status, headers);
                    return null;
                });
            }
        };
        Context.current().addListener(context -> {
            Throwable cause = context.cancellationCause();
            call.cancel(cause != null ? cause.getMessage() : null, cause);
        }, MoreExecutors.directExecutor());

        return call;
    }

    private static Status getStatus(final Metadata latest, final Metadata fallback, final Response response, final String description) {
        Status status = latest.get(InternalStatus.CODE_KEY);
        if (status != null) {
            return status.withDescription(latest.get(InternalStatus.MESSAGE_KEY));
        }

        if (fallback != null) {
            return getStatus(fallback, null, response, description);
        }

        status = grpcStatusFromHttpStatus(response);
        if (status != null) {
            return status;
        }

        return Status.UNKNOWN.withDescription(description);
    }

    private static Status grpcStatusFromHttpStatus(final Response response) {
        final Status status;
        switch (response.status) {
            // improbable-eng/grpc-web mappings
            case 400:
                status = Status.INVALID_ARGUMENT;
                break;
            case 401:
                status = Status.UNAUTHENTICATED;
                break;
            case 403:
                status = Status.PERMISSION_DENIED;
                break;
            case 404:
                // grpc-java disagrees here, says this should be UNIMPLEMENTED
                status = Status.NOT_FOUND;
                break;
            case 409:
                status = Status.ABORTED;
                break;
            case 412:
                status = Status.FAILED_PRECONDITION;
                break;
            case 429:
                status = Status.RESOURCE_EXHAUSTED;
                break;
            case 499:
                status = Status.CANCELLED;
                break;
            case 500:
                status = Status.UNKNOWN;
                break;
            case 501:
                status = Status.UNIMPLEMENTED;
                break;
            case 503:
                status = Status.UNAVAILABLE;
                break;
            case 504:
                // grpc-java disagrees here, says this should be UNAVAILABLE
                status = Status.DEADLINE_EXCEEDED;
                break;
            // grpc-java mappings
            case 431:
                status = Status.INTERNAL;
                break;
            case 502:
                status = Status.UNAVAILABLE;
                break;
            default:
                // explicitly returning here, so that all other cases can have a description added with the original status code
                return null;
        }
        return status.withDescription("HTTP status " + response.status + ": " + status.getDescription());
    }

    private Metadata makeMetadata(final Headers headers) {
        final Metadata result = new Metadata();
        final JsIteratorIterable<String, Object, Object> keys = headers.keys();
        JsIIterableResult<String> next = keys.next();
        while (!next.isDone()) {
            final String key = next.getValue();
            final String value = headers.get(key);
            if (value != null) {
                if (key.endsWith("-bin")) {
                    result.put(Metadata.Key.of(key, Metadata.BINARY_BYTE_MARSHALLER), BASE64.decode(value));
                } else {
                    result.put(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER), value);
                }
            }
            next = keys.next();
        }
        return result;
    }

    @Override
    public String authority() {
        return server.host;
    }

    /**
     * Takes the place of having a full-fledged deframer type, since browsers don't support streaming requests and responses simultaneously.
     * Produces a collection of byte arrays to send over the wire.
     *
     * @param message the single message to send
     * @param requestMarshaller the marshaller to use to serialize
     * @return the list of byte arrays to send, including the grpc-web prefix
     * @param <RequestT> the request type
     */
    private static <RequestT> List<Uint8Array> frame(final RequestT message, final MethodDescriptor.Marshaller<RequestT> requestMarshaller) {
        final List<Uint8Array> result = new ArrayList<>();
        try (final InputStream stream = requestMarshaller.stream(message)) {
            final Drainable drainable = (Drainable) stream;
            drainable.drainTo(new OutputStream() {
                @Override
                public void write(final int b) {
                    // TODO buffer this instead, for custom marshallers to write individual bytes
                    write(new byte[]{(byte) b}, 0, 1);
                }

                @Override
                public void write(final byte[] b, final int off, final int len) {
                    final Uint8Array e = new Uint8Array(len);
                    for (int i = off; i < len; i++) {
                        e.setAt(i, (double) b[i]);
                    }
                    result.add(e);
                }
            });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        final Uint8Array prefix = new Uint8Array(5);
        int sum = 0;
        for (final Uint8Array arr : result) {
            sum += arr.length;
        }
        new DataView(prefix.buffer).setUint32(1, sum, false);
        result.add(0, prefix);
        return result;
    }

    private Headers makeHeaders(final Metadata metadata) {
        final Headers result = new Headers();
        final byte[][] bytes = InternalMetadata.serialize(metadata);
        for (int i = 0; i < bytes.length; i += 2) {
            final String key = new String(bytes[i], StandardCharsets.UTF_8);
            final String value;
            if (key.endsWith("-bin")) {
                value = BASE64.encode(bytes[i + 1]);
            } else {
                value = new String(bytes[i + 1], StandardCharsets.UTF_8);
            }
            result.set(key, value);
        }
        return result;
    }
}
