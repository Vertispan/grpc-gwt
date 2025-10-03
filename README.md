# grpc-gwt

gRPC-web support for GWT/J2CL projects - starts with the basic gRPC APIs and stubs, and provides both replacements for
those jars, plus gRPC/gRPC-web Channel implementations.

Generated from the Java projects for grpc-api, grpc-stub, grpc-protobuf, and grpc-protobuf-lite. Makes use of standard
OpenRewrite rules and some rules specifically written to
[simplify projects for GWT](https://github.com/vertispan/gwt-compatible-recipes/).

A future version might split these dependencies into separate jars, but for now a subset of the original classes from
all four are included in a single jar.

## Implementation differences
To simplify the effort of transforming the implementation, two features have been removed. Both technically could be
restored, but at a glance there doesn't seem to be an obvious need.

At present, there is no ASCII charset in GWT, so it is assumed that the server is correctly sending ascii status and 
metadata values. All valid ascii (e.g. below 128) are equal to their corresponding utf8 value, so there should be no 
difference in behavior.

BinaryStreamMarshaller has also been effectively removed, along with the `Metadata.Key.of()` overload that supports it.
The current implementation used Class.isInstance to check which marshaller was in use, though if required this could be
reimplemented without any reflection at all.

## Browser limitations
gRPC is marketed as a "universal" RPC framework that runs in "any environment", but in practice it is entirely unusable
in browsers. Instead, gRPC-web offers a few small changes over gRPC to make it mostly compatible with browsers:
 - http/1.1 is supported instead of just h2. In most browsers this limits the number of concurrent streams (usually
   6) to a single server, so h2 is still desired for most use cases.
    - Additionally, browsers do not support h2c (h2 over cleartext), so gRPC-web over h2 is limited to TLS connections.
      This can make localhost or dev/staging deployments difficult to use.
 - Rather than HTTP trailers, a final body payload can be read as if it were an http/1 headers block. This doesn't apply
   to trailers-only responses
 - The `user-agent` header cannot be controlled by clients in a browser runtime, so `x-user-agent` should be used
   instead.
 - Since all of this adds up to "not actually gRPC", the `content-type` header should be prefixed with
   `application/grpc-web` or `application/grpc-web-text`.

These limitations are intended to "make it easy for a proxy to translate between the protocols as this is the most
likely deployment model." Unfortunately, they only cover a subset of the limitations that browsers impose, so it isn't
actually possible to run a proxy which can translate arbitrary gRPC services. The browser's `fetch()` implementation
does not support any client-streaming use cases, which limits clients from participating in either bidirectional
streaming or client streaming RPCs. At the time the spec was written it was believed that [by 2019 this would be resolved](https://github.com/grpc/grpc/commit/4daba4b6ba9717f7ffdde725043bac7e8fd90d28#diff-5f77fa0e0ca74be75527c69b5e918d30f02999e2285c8bd4787464effea8ed5dR29-R32),
and was [later revised](https://github.com/grpc/grpc/commit/f5809cced8c1161645f964c8b011c9e54e6ae075) (still with a
two-year estimate) to instead be part of the streams spec. While Chrome does now technically support streaming uploads,
it is required that this be done in "half duplex" mode, which still prevents bidirectional streams from being useful.
 * https://fetch.spec.whatwg.org/#ref-for-dom-requestinit-duplex
 * https://github.com/whatwg/fetch/issues/1254
 * https://bugzilla.mozilla.org/show_bug.cgi?id=1387483

For these reasons, other gRPC clients such as (improbable-eng/grpc-web)[https://github.com/improbable-eng/grpc-web/]
have included a websocket transport for gRPC messages. Unfortunately, that repository is no longer maintained, and some
features we have found to be necessary were never shipped or never implemented.

A simple `fetch()` implementation is provided which supports unary and server-streaming calls, but by itself cannot
support other streaming calls.

### Unfinished transports
This library will include two websocket transports - the first is compatible with improbable-eng/grpc-web using one
websocket per stream, and the second supports using a single websocket for multiple streams to the same server (in 
roughly the same way that http2 would do with streams on a single socket). The first is included for compatibility with
any existing proxies that support this feature - we mostly encourage the use of the second where required.

Another implementation will be provided that uses server-streaming `fetch()` requests to receive messages for a given
stream, and a unary `fetch()` to send messages. These can be paired with a server-side tools to modify a Java 
`BindableService` to include pairs of gRPC methods which will be treated by the real server method as a single streaming
method. Some implementation details are likely to be left out, specifically around handling the state of pairing up
messages etc, and naturally any reverse proxy would be required to forward all calls to the same server to be handled
uniformly.

## Usage

Add `com.vertispan.grpc:grpc-web-gwt` to your project dependencies, replacing `io.grpc:grpc-*` dependencies.  The
version will be based on the grpc-java build being used, with an integer suffix to allow for packaging changes. For
example, current released versions:

| grpc-java | grpc-gwt | Description                                                              |
|-----------|----------|--------------------------------------------------------------------------|
| 1.63.1    | 1.63.1-1 | Initial release                                                          |

Replace/exclude any existing grpc-java dependencies with this library. Add an inherits in your project's GWT module:
```xml
<inherits name="io.grpc.Grpc" />
```

Then, generate your own stubs as normal with protoc, taking care to only use the async stubs.

Additionally, one or more `io.grpc.Channel` implementation should be picked from the provided dependencies, and passed to
the `newStub(..)` method when creating your generated client service.


## Building

This can be built simply with `mvn install`.

