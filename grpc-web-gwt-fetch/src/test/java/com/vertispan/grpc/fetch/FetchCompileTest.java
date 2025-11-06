package com.vertispan.grpc.fetch;

import com.google.gwt.junit.client.GWTTestCase;
import elemental2.dom.URL;

public class FetchCompileTest extends GWTTestCase {
    @Override
    public String getModuleName() {
        return "com.vertispan.grpc.fetch.Fetch";
    }

    public void testCompile() {
        // Don't actually use the channel at this time, just make sure it
        // compiles, with other bits of gRPC along with it
        FetchChannel channel = new FetchChannel(new URL("https://localhost:8080"));
        assertEquals("localhost:8080", channel.authority());
    }
}
