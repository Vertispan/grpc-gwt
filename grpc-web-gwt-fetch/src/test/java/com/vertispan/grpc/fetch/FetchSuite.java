package com.vertispan.grpc.fetch;

import com.google.gwt.core.shared.GwtIncompatible;
import com.google.gwt.junit.tools.GWTTestSuite;

@GwtIncompatible
public class FetchSuite {
    public static GWTTestSuite suite() {
        GWTTestSuite suite = new GWTTestSuite("gRPC Fetch Emulation Tests");

        suite.addTestSuite(FetchCompileTest.class);

        return suite;
    }
}
