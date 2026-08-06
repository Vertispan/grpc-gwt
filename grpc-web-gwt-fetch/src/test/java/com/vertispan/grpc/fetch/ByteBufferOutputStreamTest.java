package com.vertispan.grpc.fetch;

import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.fail;

public class ByteBufferOutputStreamTest {
    @Test
    public void writesSingleByteAndArrayIntoCurrentBuffer() throws IOException {
        ByteBufferOutputStream stream = new ByteBufferOutputStream(8);

        stream.write(1);
        stream.write(new byte[]{2, 3, 4}, 0, 3);

        List<ByteBuffer> buffers = stream.buffers;
        assertEquals(1, buffers.size());
        assertEquals(4, buffers.get(0).position());
        assertByteArrayEquals(new byte[]{1, 2, 3, 4}, writtenBytes(buffers.get(0)));
    }

    @Test
    public void allocatesLargerBufferForRemainingBytes() throws IOException {
        ByteBufferOutputStream stream = new ByteBufferOutputStream(4);

        stream.write(new byte[]{1, 2}, 0, 2);
        stream.write(new byte[]{3, 4, 5, 6, 7, 8, 9, 10, 11, 12}, 0, 10);

        List<ByteBuffer> buffers = stream.buffers;
        assertEquals(2, buffers.size());
        assertEquals(4, buffers.get(0).capacity());
        assertEquals(8, buffers.get(1).capacity());
        assertByteArrayEquals(new byte[]{1, 2, 3, 4}, writtenBytes(buffers.get(0)));
        assertByteArrayEquals(new byte[]{5, 6, 7, 8, 9, 10, 11, 12}, writtenBytes(buffers.get(1)));
    }

    @Test
    public void replacesEmptyBufferForOversizedWrite() throws IOException {
        ByteBufferOutputStream stream = new ByteBufferOutputStream(4);

        stream.write(new byte[]{1, 2, 3, 4, 5, 6}, 0, 6);

        List<ByteBuffer> buffers = stream.buffers;
        assertEquals(1, buffers.size());
        assertEquals(6, buffers.get(0).capacity());
        assertByteArrayEquals(new byte[]{1, 2, 3, 4, 5, 6}, writtenBytes(buffers.get(0)));
    }

    @Test
    public void rejectsInvalidWriteBounds() {
        ByteBufferOutputStream stream = new ByteBufferOutputStream(4);

        assertThrows(NullPointerException.class, () -> stream.write(null, 0, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> stream.write(new byte[]{1, 2, 3}, -1, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> stream.write(new byte[]{1, 2, 3}, 1, 3));
    }

    private static byte[] writtenBytes(ByteBuffer buffer) {
        ByteBuffer copy = buffer.duplicate();
        byte[] bytes = new byte[copy.position()];
        copy.flip();
        copy.get(bytes);
        return bytes;
    }

    private static void assertByteArrayEquals(byte[] expected, byte[] actual) {
        if (!Arrays.equals(expected, actual)) {
            fail("Expected " + Arrays.toString(expected) + " but was " + Arrays.toString(actual));
        }
    }

    private static void assertThrows(Class<? extends Throwable> expected, ThrowingRunnable runnable) {
        try {
            runnable.run();
            fail("Expected exception: " + expected.getName());
        } catch (Throwable t) {
            if (t.getClass() != expected) {
                fail("Expected " + expected.getName() + " but got " + t.getClass().getName());
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Throwable;
    }
}
