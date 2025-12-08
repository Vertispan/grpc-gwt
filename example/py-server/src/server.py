import hello_pb2_grpc
import hello_pb2
from concurrent import futures
import grpc


class Greeter(hello_pb2_grpc.GreeterServicer):
    def SayHello(self, request, context):
        response = hello_pb2.HelloReply()
        response.message = 'Hello, {}'.format(request.name)
        return response

server = grpc.server(futures.ThreadPoolExecutor(max_workers=10))
hello_pb2_grpc.add_GreeterServicer_to_server(Greeter(), server)
server.add_insecure_port('0.0.0.0:8080')
server.start()
server.wait_for_termination()
