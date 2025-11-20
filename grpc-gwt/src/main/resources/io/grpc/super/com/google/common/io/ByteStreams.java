package com.google.common.io;

import com.google.common.base.Preconditions;
import com.google.common.math.IntMath;
import com.google.errorprone.annotations.CanIgnoreReturnValue;

import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Queue;

public final class ByteStreams {
    private static final int BUFFER_SIZE = 8192;
    private static final int ZERO_COPY_CHUNK_SIZE = 524288;
    private static final int MAX_ARRAY_LEN = 2147483639;
    private static final int TO_BYTE_ARRAY_DEQUE_SIZE = 20;
    private static final OutputStream NULL_OUTPUT_STREAM = new OutputStream() {
        public void write(int b) {
        }

        public void write(byte[] b) {
            Preconditions.checkNotNull(b);
        }

        public void write(byte[] b, int off, int len) {
            Preconditions.checkNotNull(b);
            Preconditions.checkPositionIndexes(off, off + len, b.length);
        }

        public String toString() {
            return "ByteStreams.nullOutputStream()";
        }
    };

    static byte[] createBuffer() {
        return new byte[8192];
    }

    private ByteStreams() {
    }

    @CanIgnoreReturnValue
    public static long copy(InputStream from, OutputStream to) throws IOException {
        Preconditions.checkNotNull(from);
        Preconditions.checkNotNull(to);
        byte[] buf = createBuffer();
        long total = 0L;

        while(true) {
            int r = from.read(buf);
            if (r == -1) {
                return total;
            }

            to.write(buf, 0, r);
            total += (long)r;
        }
    }

    private static byte[] toByteArrayInternal(InputStream in, Queue<byte[]> bufs, int totalLen) throws IOException {
        int initialBufferSize = Math.min(8192, Math.max(128, Integer.highestOneBit(totalLen) * 2));

        for(int bufSize = initialBufferSize; totalLen < 2147483639; bufSize = IntMath.saturatedMultiply(bufSize, bufSize < 4096 ? 4 : 2)) {
            byte[] buf = new byte[Math.min(bufSize, 2147483639 - totalLen)];
            bufs.add(buf);

            int r;
            for(int off = 0; off < buf.length; totalLen += r) {
                r = in.read(buf, off, buf.length - off);
                if (r == -1) {
                    return combineBuffers(bufs, totalLen);
                }

                off += r;
            }
        }

        if (in.read() == -1) {
            return combineBuffers(bufs, 2147483639);
        } else {
            throw new OutOfMemoryError("input is too large to fit in a byte array");
        }
    }

    private static byte[] combineBuffers(Queue<byte[]> bufs, int totalLen) {
        if (bufs.isEmpty()) {
            return new byte[0];
        } else {
            byte[] result = (byte[])bufs.remove();
            if (result.length == totalLen) {
                return result;
            } else {
                int remaining = totalLen - result.length;

                int bytesToCopy;
                for(result = Arrays.copyOf(result, totalLen); remaining > 0; remaining -= bytesToCopy) {
                    byte[] buf = (byte[])bufs.remove();
                    bytesToCopy = Math.min(remaining, buf.length);
                    int resultOffset = totalLen - remaining;
                    System.arraycopy(buf, 0, result, resultOffset, bytesToCopy);
                }

                return result;
            }
        }
    }

    public static byte[] toByteArray(InputStream in) throws IOException {
        Preconditions.checkNotNull(in);
        return toByteArrayInternal(in, new ArrayDeque(20), 0);
    }

    static byte[] toByteArray(InputStream in, long expectedSize) throws IOException {
        Preconditions.checkArgument(expectedSize >= 0L, "expectedSize (%s) must be non-negative", expectedSize);
        if (expectedSize > 2147483639L) {
            throw new OutOfMemoryError(expectedSize + " bytes is too large to fit in a byte array");
        } else {
            byte[] bytes = new byte[(int)expectedSize];

            int read;
            for(int remaining = (int)expectedSize; remaining > 0; remaining -= read) {
                int off = (int)expectedSize - remaining;
                read = in.read(bytes, off, remaining);
                if (read == -1) {
                    return Arrays.copyOf(bytes, off);
                }
            }

            int b = in.read();
            if (b == -1) {
                return bytes;
            } else {
                Queue<byte[]> bufs = new ArrayDeque(22);
                bufs.add(bytes);
                bufs.add(new byte[]{(byte)b});
                return toByteArrayInternal(in, bufs, bytes.length + 1);
            }
        }
    }

    @CanIgnoreReturnValue
    public static long exhaust(InputStream in) throws IOException {
        long total = 0L;

        long read;
        for(byte[] buf = createBuffer(); (read = (long)in.read(buf)) != -1L; total += read) {
        }

        return total;
    }

    public static OutputStream nullOutputStream() {
        return NULL_OUTPUT_STREAM;
    }

    public static InputStream limit(InputStream in, long limit) {
        return new LimitedInputStream(in, limit);
    }

    public static void readFully(InputStream in, byte[] b) throws IOException {
        readFully(in, b, 0, b.length);
    }

    public static void readFully(InputStream in, byte[] b, int off, int len) throws IOException {
        int read = read(in, b, off, len);
        if (read != len) {
            throw new EOFException("reached end of stream after reading " + read + " bytes; " + len + " bytes expected");
        }
    }

    public static void skipFully(InputStream in, long n) throws IOException {
        long skipped = skipUpTo(in, n);
        if (skipped < n) {
            throw new EOFException("reached end of stream after skipping " + skipped + " bytes; " + n + " bytes expected");
        }
    }

    static long skipUpTo(InputStream in, long n) throws IOException {
        long totalSkipped = 0L;

        long skipped;
        for(byte[] buf = null; totalSkipped < n; totalSkipped += skipped) {
            long remaining = n - totalSkipped;
            skipped = skipSafely(in, remaining);
            if (skipped == 0L) {
                int skip = (int)Math.min(remaining, 8192L);
                if (buf == null) {
                    buf = new byte[skip];
                }

                if ((skipped = (long)in.read(buf, 0, skip)) == -1L) {
                    break;
                }
            }
        }

        return totalSkipped;
    }

    private static long skipSafely(InputStream in, long n) throws IOException {
        int available = in.available();
        return available == 0 ? 0L : in.skip(Math.min((long)available, n));
    }

    @CanIgnoreReturnValue
    public static int read(InputStream in, byte[] b, int off, int len) throws IOException {
        Preconditions.checkNotNull(in);
        Preconditions.checkNotNull(b);
        if (len < 0) {
            throw new IndexOutOfBoundsException("len (%s) cannot be negative");
        } else {
            Preconditions.checkPositionIndexes(off, off + len, b.length);

            int total;
            int result;
            for(total = 0; total < len; total += result) {
                result = in.read(b, off + total, len - total);
                if (result == -1) {
                    break;
                }
            }

            return total;
        }
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private long left;
        private long mark = -1L;

        LimitedInputStream(InputStream in, long limit) {
            super(in);
            Preconditions.checkNotNull(in);
            Preconditions.checkArgument(limit >= 0L, "limit must be non-negative");
            this.left = limit;
        }

        public int available() throws IOException {
            return (int)Math.min((long)this.in.available(), this.left);
        }

        public synchronized void mark(int readLimit) {
            this.in.mark(readLimit);
            this.mark = this.left;
        }

        public int read() throws IOException {
            if (this.left == 0L) {
                return -1;
            } else {
                int result = this.in.read();
                if (result != -1) {
                    --this.left;
                }

                return result;
            }
        }

        public int read(byte[] b, int off, int len) throws IOException {
            if (this.left == 0L) {
                return -1;
            } else {
                len = (int)Math.min((long)len, this.left);
                int result = this.in.read(b, off, len);
                if (result != -1) {
                    this.left -= (long)result;
                }

                return result;
            }
        }

        public synchronized void reset() throws IOException {
            if (!this.in.markSupported()) {
                throw new IOException("Mark not supported");
            } else if (this.mark == -1L) {
                throw new IOException("Mark not set");
            } else {
                this.in.reset();
                this.left = this.mark;
            }
        }

        public long skip(long n) throws IOException {
            n = Math.min(n, this.left);
            long skipped = this.in.skip(n);
            this.left -= skipped;
            return skipped;
        }
    }
}
