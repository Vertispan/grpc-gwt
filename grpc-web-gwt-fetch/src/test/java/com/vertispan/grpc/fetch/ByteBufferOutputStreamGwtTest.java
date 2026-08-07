package com.vertispan.grpc.fetch;

import com.google.gwt.junit.client.GWTTestCase;
import elemental2.core.Uint8Array;

import java.io.IOException;
import java.util.List;

/**
 * Browser-emulation behavior tests for {@link ByteBufferOutputStream}, including Uint8Array export semantics.
 * Also includes delegate stubs for JVM-only ByteBufferOutputStream unit checks.
 */
public class ByteBufferOutputStreamGwtTest extends GWTTestCase {
    @Override
    public String getModuleName() {
        return "com.vertispan.grpc.fetch.Fetch";
    }

    public void testByteBufferOutputStreamExportsWrittenBytes() throws IOException {
        ByteBufferOutputStream stream = new ByteBufferOutputStream(4);
        stream.write(new byte[]{1, 2}, 0, 2);
        stream.write(new byte[]{3, 4, 5, 6, 7, 8, 9, 10, 11, 12}, 0, 10);

        List<Uint8Array> buffers = stream.getBuffers();
        assertEquals(2, buffers.size());

        Uint8Array first = buffers.get(0);
        assertEquals(4, first.length);
        assertEquals(1, first.getAt(0).intValue());
        assertEquals(2, first.getAt(1).intValue());
        assertEquals(3, first.getAt(2).intValue());
        assertEquals(4, first.getAt(3).intValue());

        Uint8Array second = buffers.get(1);
        assertEquals(8, second.length);
        assertEquals(5, second.getAt(0).intValue());
        assertEquals(6, second.getAt(1).intValue());
        assertEquals(7, second.getAt(2).intValue());
        assertEquals(8, second.getAt(3).intValue());
        assertEquals(9, second.getAt(4).intValue());
        assertEquals(10, second.getAt(5).intValue());
        assertEquals(11, second.getAt(6).intValue());
        assertEquals(12, second.getAt(7).intValue());
    }

    public void testByteBufferOutputStreamTruncatesFinalExportedBufferLength() throws IOException {
        ByteBufferOutputStream stream = new ByteBufferOutputStream(8);
        stream.write(new byte[]{1, 2, 3, 4, 5, 6}, 0, 6);
        stream.write(new byte[]{7, 8, 9, 10, 11}, 0, 5);

        List<Uint8Array> buffers = stream.getBuffers();
        assertEquals(2, buffers.size());

        Uint8Array first = buffers.get(0);
        assertEquals(8, first.length);
        assertEquals(1, first.getAt(0).intValue());
        assertEquals(8, first.getAt(7).intValue());

        Uint8Array second = buffers.get(1);
        assertEquals(3, second.length);
        assertEquals(9, second.getAt(0).intValue());
        assertEquals(10, second.getAt(1).intValue());
        assertEquals(11, second.getAt(2).intValue());
    }

    public void testByteBufferOutputStreamExportsEmptyWhenNothingWritten() {
        ByteBufferOutputStream stream = new ByteBufferOutputStream(8);
        assertTrue(stream.getBuffers().isEmpty());
    }

    public void testByteBufferOutputStreamPreservesUnsignedByteValues() throws IOException {
        ByteBufferOutputStream stream = new ByteBufferOutputStream(8);
        stream.write(new byte[]{-1, -128, 127}, 0, 3);

        List<Uint8Array> buffers = stream.getBuffers();
        assertEquals(1, buffers.size());
        assertEquals(255, buffers.get(0).getAt(0).intValue());
        assertEquals(128, buffers.get(0).getAt(1).intValue());
        assertEquals(127, buffers.get(0).getAt(2).intValue());
    }

    public void testByteBufferOutputStreamFillsExactlyWithWriteInt() throws IOException {
        ByteBufferOutputStream stream = new ByteBufferOutputStream(4);
        stream.write(1);
        stream.write(2);
        stream.write(3);
        stream.write(4);

        List<Uint8Array> buffers = stream.getBuffers();
        assertEquals(1, buffers.size());
        assertEquals(4, buffers.get(0).length);
        assertEquals(1, buffers.get(0).getAt(0).intValue());
        assertEquals(2, buffers.get(0).getAt(1).intValue());
        assertEquals(3, buffers.get(0).getAt(2).intValue());
        assertEquals(4, buffers.get(0).getAt(3).intValue());
    }

    public void testByteBufferOutputStreamFillsExactlyWithWriteArray() throws IOException {
        ByteBufferOutputStream stream = new ByteBufferOutputStream(4);
        stream.write(new byte[]{1, 2, 3, 4}, 0, 4);

        List<Uint8Array> buffers = stream.getBuffers();
        assertEquals(1, buffers.size());
        assertEquals(4, buffers.get(0).length);
        assertEquals(1, buffers.get(0).getAt(0).intValue());
        assertEquals(2, buffers.get(0).getAt(1).intValue());
        assertEquals(3, buffers.get(0).getAt(2).intValue());
        assertEquals(4, buffers.get(0).getAt(3).intValue());
    }

    public void testByteBufferOutputStreamRolloverAfterExactFill() throws IOException {
        ByteBufferOutputStream stream = new ByteBufferOutputStream(4);
        stream.write(new byte[]{1, 2, 3, 4}, 0, 4);
        stream.write(5);

        List<Uint8Array> buffers = stream.getBuffers();
        assertEquals(2, buffers.size());
        assertEquals(4, buffers.get(0).length);
        assertEquals(1, buffers.get(1).length);
        assertEquals(5, buffers.get(1).getAt(0).intValue());
    }

    public void testByteBufferOutputStreamLargeFirstWriteThenAppend() throws IOException {
        ByteBufferOutputStream stream = new ByteBufferOutputStream(4);
        stream.write(new byte[]{1, 2, 3, 4, 5, 6}, 0, 6);
        stream.write(new byte[]{7, 8}, 0, 2);

        List<Uint8Array> buffers = stream.getBuffers();
        assertEquals(2, buffers.size());
        assertEquals(6, buffers.get(0).length);
        assertEquals(2, buffers.get(1).length);
        assertEquals(1, buffers.get(0).getAt(0).intValue());
        assertEquals(6, buffers.get(0).getAt(5).intValue());
        assertEquals(7, buffers.get(1).getAt(0).intValue());
        assertEquals(8, buffers.get(1).getAt(1).intValue());
    }

    public void testDelegatesJvmSingleByteAndArrayCase() throws Exception {
        new ByteBufferOutputStreamTest().writesSingleByteAndArrayIntoCurrentBuffer();
    }

    public void testDelegatesJvmOversizedRemainingCase() throws Exception {
        new ByteBufferOutputStreamTest().allocatesLargerBufferForRemainingBytes();
    }

    public void testDelegatesJvmOversizedInitialWriteCase() throws Exception {
        new ByteBufferOutputStreamTest().replacesEmptyBufferForOversizedWrite();
    }

    public void testDelegatesJvmBoundsValidationCase() {
        new ByteBufferOutputStreamTest().rejectsInvalidWriteBounds();
    }
}
