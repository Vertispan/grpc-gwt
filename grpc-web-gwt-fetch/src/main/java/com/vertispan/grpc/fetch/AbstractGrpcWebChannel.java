package com.vertispan.grpc.fetch;

import com.google.common.io.BaseEncoding;
import com.google.common.util.concurrent.MoreExecutors;
import elemental2.core.DataView;
import elemental2.core.JsArray;
import elemental2.core.JsObject;
import elemental2.core.Uint8Array;
import elemental2.dom.URL;
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
import jsinterop.base.JsPropertyMap;
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
public abstract class AbstractGrpcWebChannel extends Channel {
    private static final Logger log = Logger.getLogger(AbstractGrpcWebChannel.class.getName());
    private static final BaseEncoding BASE64 = BaseEncoding.base64().omitPadding();
    private static int nextRequestId = 0;

    private final URL server;

    /**
     * Creates an instance to communicate with the given server. The path component will be ignored, in favor of
     * the particular gRPC service being called.
     *
     * @param server the server URL
     */
    protected AbstractGrpcWebChannel(URL server) {
        this.server = server;
    }

    /**
     * Creates and returns a Transport instance for the method and URL, invoking callbacks when events occur.
     * @param method the HTTP method to send - always POST today, but in theory could be GET in the future
     * @param url the full URL to connect to
     * @param callbacks the callbacks to invoke on events
     * @return the resulting transport instance
     */
    protected abstract Transport createTransport(String method, URL url, TransportCallbacks callbacks);

    /**
     * Whether the provided transport supports bidi streaming calls. If false, the implementation will need to use unary
     * and server-streaming calls to achieve the same effect.
     * <p>
     * Unused at this time, intended for future work.
     *
     * @return true if the transport supports bidi streaming calls
     */
    protected boolean transportSupportsBidiStreaming() {
        return false;
    }

    @Override
    public final <RequestT, ResponseT> ClientCall<RequestT, ResponseT> newCall(MethodDescriptor<RequestT, ResponseT> methodDescriptor, CallOptions callOptions) {
        if (!transportSupportsBidiStreaming() && !methodDescriptor.getType().clientSendsOneMessage()) {
            throw new IllegalStateException("Transport does not support bidi streaming calls required by method " + methodDescriptor.getFullMethodName());
        }

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
            //TODO(grpc-gwt#2) this isn't async, but probably should be
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

        class Call extends ClientCall<RequestT, ResponseT> implements TransportCallbacks {
            private final Transport transport;
            private Listener<ResponseT> responseListener;
            private final GrpcWebMessageDeframer<ResponseT> deframer;
            private final int requestId = nextRequestId++;
            private int httpStatus;
            private Metadata headers;
            private Metadata trailers;
            private boolean closed;

            public Call(URL url, MethodDescriptor<RequestT, ResponseT> methodDescriptor) {
                url = new URL(url);
                url.pathname = methodDescriptor.getFullMethodName();
                transport = createTransport("POST", url, this);
                deframer = new GrpcWebMessageDeframer<>(methodDescriptor);
            }

            // Easy prefix for searching in browser logs
            private String prefix() {
                return "grpc-web[" + requestId + "]: ";
            }

            @Override
            public void onHeaders(int status, JsPropertyMap<String> headers) {
                log.finest(prefix() + "Response status" + status);
                if (status == 0) {
                    // Connectivity issue - headers are garbage, just fail. Dev tools logs may have more details,
                    // such as a CORS/cert issue
                    responseListener.onClose(Status.INTERNAL.withDescription("Request failed, status 0"), new Metadata());
                    return;
                }
                this.httpStatus = status;
                this.headers = makeMetadata(headers);
                responseListener.onHeaders(this.headers);
            }

            @Override
            public void onChunk(Uint8Array chunk) {
                final List<GrpcWebMessageDeframer<ResponseT>.Payload> frames;
                try {
                    frames = deframer.deframe(TypedArrayHelper.wrap(chunk));
                } catch (TrailersFinishedException e) {
                    closed = true;
                    responseListener.onClose(Status.INTERNAL.withDescription("Received data after trailers"), trailers != null ? trailers : new Metadata());
                    throw new RuntimeException(e);
                }
                log.fine(prefix() + "Reading " + chunk.byteLength + " bytes from " + methodDescriptor.getFullMethodName() + ", resulting in " + frames.size() + " frames");
                for (int i = 0; i < frames.size(); i++) {
                    final GrpcWebMessageDeframer<ResponseT>.Payload frame = frames.get(i);
                    if (frame instanceof GrpcWebMessageDeframer.Message) {
                        log.fine(prefix() + "Received message");
                        responseListener.onMessage(((GrpcWebMessageDeframer<ResponseT>.Message) frame).message);
                    } else if (frame instanceof GrpcWebMessageDeframer.Trailers) {
                        final GrpcWebMessageDeframer<ResponseT>.Trailers trailers = (GrpcWebMessageDeframer<ResponseT>.Trailers) frame;
                        log.fine(prefix() + "Received trailers" + ": " + trailers.metadata);
                        this.trailers = trailers.metadata;
                        Status status = getStatus(this.trailers, headers, httpStatus,
                                "Response closed without grpc status in trailers");
                        responseListener.onClose(status, trailers.metadata);
                        closed = true;
                    } else {
                        throw new IllegalStateException("Unknown frame type: " + frame.getClass());
                    }
                }
            }

            @Override
            public void onEnd(Object error) {
                if (closed) {
                    // Nothing to do, already passed along the body and trailers, even if there
                    // was an error, we can't report it anywhere
                    return;
                }
                closed = true;

                Status status = getStatus(headers, trailers, httpStatus, "Response closed without trailers, headers did not indicate grpc status");
                log.fine(prefix() + "Stream closed for " + methodDescriptor.getFullMethodName() + ", status " + status);
                responseListener.onClose(status, headers);
            }

            @Override
            public void start(Listener<ResponseT> responseListener, Metadata headers) {
                // There was a failure before we actually started, send that and give up
                if (failure.get() != null) {
                    responseListener.onClose(failure.get(), new Metadata());
                    closed = true;
                    return;
                }

                baseMetadata.merge(headers);
                JsPropertyMap<String> requestHeaders = makeHeaders(baseMetadata);
                requestHeaders.set("content-type", "application/grpc-web+proto");
                requestHeaders.set("x-grpc-web", "1");

                transport.start(requestHeaders);

                this.responseListener = responseListener;
            }

            @Override
            public void request(int numMessages) {
                // Not implemented
            }

            @Override
            public void cancel(@Nullable String message, @Nullable Throwable cause) {
                transport.cancel();
                closed = true;
            }

            @Override
            public void halfClose() {
                transport.finishSend();
            }

            @Override
            public void sendMessage(RequestT message) {
                final List<Uint8Array> payloads = frame(message, methodDescriptor.getRequestMarshaller());
                log.fine("Calling " + server + " with " + payloads.size() + " frames");
                for (int i = 0; i < payloads.size(); i++) {
                    transport.sendMessage(payloads.get(i));
                }
            }
        }
        Call call =  new Call(server, methodDescriptor);

        Context.current().addListener(context -> {
            Throwable cause = context.cancellationCause();
            call.cancel(cause != null ? cause.getMessage() : null, cause);
        }, MoreExecutors.directExecutor());

        return call;
    }

    private static Status getStatus(final Metadata latest, final Metadata fallback, final int httpStatus, final String description) {
        Status status = latest.get(InternalStatus.CODE_KEY);
        if (status != null) {
            return status.withDescription(latest.get(InternalStatus.MESSAGE_KEY));
        }

        if (fallback != null) {
            return getStatus(fallback, null, httpStatus, description);
        }

        status = grpcStatusFromHttpStatus(httpStatus);
        if (status != null) {
            return status;
        }

        return Status.UNKNOWN.withDescription(description);
    }

    private static Status grpcStatusFromHttpStatus(int httpStatus) {
        final Status status;
        switch (httpStatus) {
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
        return status.withDescription("HTTP status " + httpStatus + ": " + status.getDescription());
    }

    private Metadata makeMetadata(final JsPropertyMap<String> headers) {
        final Metadata result = new Metadata();
        final JsArray<String> keys = JsObject.keys(headers);
        for (int i = 0; i < keys.length; i++) {
            String key = keys.getAt(i);
            final String value = headers.get(key);
            if (value != null) {
                if (key.endsWith("-bin")) {
                    result.put(Metadata.Key.of(key, Metadata.BINARY_BYTE_MARSHALLER), BASE64.decode(value));
                } else {
                    result.put(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER), value);
                }
            }
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
                    for (int i = 0; i < len; i++) {
                        e.setAt(i, (double) b[off + i]);
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

    private JsPropertyMap<String> makeHeaders(final Metadata metadata) {
        final JsPropertyMap<String> result = JsPropertyMap.of();
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
