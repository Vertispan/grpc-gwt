package helloworld;

import com.google.gwt.core.client.EntryPoint;
import com.vertispan.grpc.fetch.FetchChannel;
import elemental2.dom.DomGlobal;
import elemental2.dom.HTMLTextAreaElement;
import elemental2.dom.URL;
import io.grpc.stub.StreamObserver;

public class HelloClient implements EntryPoint {
    private final FetchChannel channel = new FetchChannel(new URL("http://localhost:8080/"));
    @Override
    public void onModuleLoad() {
        HTMLTextAreaElement message = (HTMLTextAreaElement) DomGlobal.document.getElementById("message");
        DomGlobal.document.getElementById("send").addEventListener("click", evt -> sendGreeting(message.value));
    }

    private void sendGreeting(String message) {
        GreeterGrpc.newStub(channel)
                .sayHello(HelloRequest.newBuilder().setName(message).build(), new StreamObserver<HelloReply>() {
                    @Override
                    public void onNext(HelloReply value) {
                        DomGlobal.alert("Greeting: " + value.getMessage());
                    }

                    @Override
                    public void onError(Throwable t) {
                        DomGlobal.alert("Error: " + t.getMessage());
                    }

                    @Override
                    public void onCompleted() {
                        // No-op, unary ends either in onNext or onError, no need to do anything for this example
                    }
                });
    }
}