Examples for grpc-gwt, starting with a minimal GWT project in `gwt-client/` to show how generated Java can be used
in GWT. This can be hosted with any web server, for example
```shell
cd gwt-client
mvn verify
python -m http.server -d target/grpc-gwt-HEAD-SNAPSHOT/hello_grpc/
```
which will compile the content and host the server at http://localhost:8000

Only one server is provided at this time:
<!-- Two servers are provided at this time:
 * `java-server/` - A simple Java project using Jetty to host both the gRPC endpoint, but also a gRPC-web proxy
in-process to avoid the need for an external proxy process. -->
 * `py-server/` - A minimal Python project to host the gRPC endpoint, plus docker-compose wiring to run it along with
an Envoy proxy to translate to gRPC-web, and add required CORS headers.

The helloworld.proto is provided for reference, but all generated code is committed to the repo to simplify setup.
Use your own language or build tool as needed to generate code for your own protos. Generated Java is deliberately
checked in twice to avoid extra build wiring or implying that the Java server (or "shared" project) must exist at all.

The default configuration for these servers means http rather than https, and no browser supports h2c, so http/1.1
will always be used.

In the future, this example will be expanded to demonstrate TLS (and so h2), and alternate transports.
