package com.vertispan.grpc.fetch;

import com.google.protobuf.gwt.IterableByteBufferInputStream;
import io.grpc.InternalMetadata;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Manages state when reading framed messages to turn into their individual messages and trailer data.
 * Unlike gRPC, grpc-web includes trailers as a message, so we need to read those separately and signal them
 * to the caller.
 * <p>
 * Rough analog to io.grpc.internal.MessageDeframer, but without compression support, and handles trailers as part of
 * the data stream (a required component of grpc-web).
 *
 * @param <ResponseT> the response message type to deserialize
 */
public class GrpcWebMessageDeframer<ResponseT> {
    private static final int FRAME_PREFIX_LENGTH = 5;
    private static final byte TRAILER_FRAME_MARKER = (byte) 0x80;

    private final MethodDescriptor<?, ResponseT> methodDescriptor;

    /**
     * Construct a new deframer for the given method descriptor.
     * @param methodDescriptor the method descriptor to use for deserializing messages
     */
    public GrpcWebMessageDeframer(final MethodDescriptor<?, ResponseT> methodDescriptor) {
        this.methodDescriptor = methodDescriptor;
    }

    /**
     * Represents the payloads that can be returned from deframe().
     */
    public abstract class Payload {

    }

    /**
     * A message payload, containing a deserialized protobuf message from the server.
     */
    public final class Message extends Payload {
        public ResponseT message;
    }

    /**
     * A trailers payload, containing the trailers metadata from the server.
     */
    public final class Trailers extends Payload {
        public Metadata metadata;
    }

    private ByteBuffer buffer;
    private boolean sawTrailers = false;

    /**
     * Parse the next block of data received, turning it into zero-to-many payloads that the client can handle.
     * If a Trailers payload is returned, it will be the last payload in the list, and there will be no further data in
     * the stream.
     *
     * @param data the bytes received from the server
     * @return a list of fully-decoded payloads. May be empty if we haven't read enough data, or may contain multiple
     * messages, depending on how the DATA frames are constructed.
     * @throws TrailersFinishedException if trailers were already seen, but further data was received. Deframer is now
     * in an invalid state, and must not be used again.
     */
    public List<Payload> deframe(final ByteBuffer data) throws TrailersFinishedException {
        if (sawTrailers) {
            throw new IllegalStateException("Already saw trailers, no further data expected");
        }
        if (data.remaining() == 0) {
            // Empty, ignore it
            return List.of();
        }
        if (this.buffer == null) {
            // First data we've seen, or we previously had consumed all the data we had
            this.buffer = data;
        } else {
            // Replace our existing buffer
            final ByteBuffer newBuffer = ByteBuffer.allocate(this.buffer.remaining() + data.remaining());
            newBuffer.put(this.buffer);
            newBuffer.put(data);
            newBuffer.flip();

            this.buffer = newBuffer;
        }

        final List<Payload> results = new ArrayList<>();
        while (true) {
            if (this.buffer.remaining() < FRAME_PREFIX_LENGTH) {
                // Not enough data to read the next frame prefix, send what we had so far and wait for more
                return results;
            }

            // We have at least a frame prefix, read it
            final byte frameType = buffer.get();
            final int frameLength = buffer.getInt();

            // Make sure we have the whole frame
            if (this.buffer.remaining() < frameLength) {
                // Not enough data to read the next frame body, send what we had so far and wait for more
                // Rewind the buffer to before we read the frame prefix
                this.buffer.position(this.buffer.position() - FRAME_PREFIX_LENGTH);
                return results;
            }

            final ByteBuffer body = buffer.slice().limit(frameLength);
            buffer.position(buffer.position() + frameLength);
            // We have a full frame, read it
            if (frameType == TRAILER_FRAME_MARKER) {
                final Trailers e = new Trailers();
                e.metadata = makeMetadata(body);
                results.add(e);

                // This must be the end
                sawTrailers = true;
                if (buffer.remaining() != 0) {
                    throw new TrailersFinishedException("Received data after trailers");
                }
            } else {
                final Message e = new Message();
                if (body.remaining() != 0) {
                    e.message = methodDescriptor.parseResponse(new IterableByteBufferInputStream(Collections.singleton(body)));
                } else {
                    // Empty message, workaround for https://github.com/protocolbuffers/protobuf/issues/23957
                    e.message = methodDescriptor.parseResponse(new IterableByteBufferInputStream(Collections.emptyList()));
                }
                results.add(e);
            }

            if (buffer.remaining() == 0) {
                // We consumed everything, let go of the buffer
                this.buffer = null;
                return results;
            }
        }
    }

    private static Metadata makeMetadata(final ByteBuffer body) {
        final byte[][] bytes = new byte[0][];
        int start = 0;
        for (int i = body.position(); i < body.limit(); ++i) {
            final byte b = body.get(i);
            if (b == '\n' || b == ':') {
                assert start < i;
                final byte[] line = new byte[i - start];
                body.position(start).get(line);
                // Trim trailing/leading whitespace before passing to InternalMetadata.
                // In practice, this is effectively only leading whitespace after the `:`,
                // but http header names/values must not have trailing whitespace either.
                final String s = new String(line, StandardCharsets.UTF_8).trim();
                bytes[bytes.length] = s.getBytes(StandardCharsets.UTF_8);
                start = i + 1;
            }
        }
        if (start < body.limit()) {
            // No trailing newline - in practice our server always sends a trailing newline,
            // so this will never be used
            final byte[] line = new byte[body.limit() - start];
            body.position(start).get(line);
            bytes[bytes.length] = line;
        }
        return InternalMetadata.newMetadata(bytes);
    }
}
