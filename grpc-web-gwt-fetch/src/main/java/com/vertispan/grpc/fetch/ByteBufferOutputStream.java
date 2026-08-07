package com.vertispan.grpc.fetch;

import com.google.common.annotations.VisibleForTesting;
import elemental2.core.ArrayBufferView;
import elemental2.core.Uint8Array;
import org.gwtproject.nio.TypedArrayHelper;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * OutputStream implementation that writes to bytebuffers (backed by typed arrays) of a minimum size. Larger buffers
 * may be created if the written data requires it.
 */
public class ByteBufferOutputStream extends OutputStream {
    @VisibleForTesting
    final List<ByteBuffer> buffers = new ArrayList<>();
    private final int bufferSize;

    public ByteBufferOutputStream(int bufferSize) {
        if (bufferSize <= 0) {
            throw new IllegalArgumentException("bufferSize must be positive");
        }
        this.bufferSize = bufferSize;
        buffers.add(ByteBuffer.allocate(bufferSize));
    }

    @Override
    public void write(int i) throws IOException {
        ensureRemaining(1).put((byte) i);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if (b == null) {
            throw new NullPointerException();
        }
        if (off < 0 || len < 0 || len > b.length - off) {
            throw new IndexOutOfBoundsException();
        }
        if (len == 0) {
            return;
        }

        ByteBuffer current = currentBuffer();
        if (len > current.remaining() && current.position() != 0) {
            int chunk = current.remaining();
            current.put(b, off, chunk);
            off += chunk;
            len -= chunk;
        }

        ensureRemaining(len).put(b, off, len);
    }

    private ByteBuffer ensureRemaining(int length) {
        ByteBuffer current = currentBuffer();
        if (current.remaining() >= length) {
            return current;
        }

        ByteBuffer expanded = ByteBuffer.allocate(Math.max(bufferSize, length));
        if (current.position() == 0) {
            buffers.set(buffers.size() - 1, expanded);
        } else {
            buffers.add(expanded);
        }
        return expanded;
    }

    private ByteBuffer currentBuffer() {
        return buffers.get(buffers.size() - 1);
    }

    /**
     * Returns the written contents as typed arrays. Intended to be called after all writes are complete.
     */
    public List<Uint8Array> getBuffers() {
        List<Uint8Array> result = new ArrayList<>(buffers.size());
        for (ByteBuffer buffer : buffers) {
            if (buffer.position() == 0) {
                continue;
            }
            result.add(asUint8Array(buffer));
        }
        return result;
    }

    private Uint8Array asUint8Array(ByteBuffer buffer) {
        ByteBuffer readable = buffer.duplicate();
        readable.flip();
        ArrayBufferView view = TypedArrayHelper.unwrap(readable.slice());
        return new Uint8Array(view.buffer, view.byteOffset, view.byteLength);
    }
}
