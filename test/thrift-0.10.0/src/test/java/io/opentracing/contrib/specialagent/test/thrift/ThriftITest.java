/* Copyright 2019 The OpenTracing Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.opentracing.contrib.specialagent.test.thrift;

import io.opentracing.contrib.specialagent.TestUtil;
import io.opentracing.contrib.specialagent.test.thrift.generated.CustomService;
import org.apache.thrift.TProcessor;
import org.apache.thrift.TProcessorFactory;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.protocol.TProtocolFactory;
import org.apache.thrift.server.TServer;
import org.apache.thrift.server.TThreadPoolServer;
import org.apache.thrift.transport.TServerSocket;
import org.apache.thrift.transport.TServerTransport;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransport;

public class ThriftITest {
  private static final int port = 8883;

  public static void main(final String[] args) throws Exception {
    TestUtil.initTerminalExceptionHandler();
    TServerTransport serverTransport = new TServerSocket(port);
    TServer server = startNewThreadPoolServer(serverTransport);

    TTransport transport = new TSocket("localhost", port);
    transport.open();

    TProtocol protocol = new TBinaryProtocol(transport);

    CustomService.Client client = new CustomService.Client(protocol);
    final String res = client.say("one", "two");
    if(!"Say one two".equals(res))
      throw new AssertionError("ERROR: wrong result");

    server.stop();
    transport.close();

    TestUtil.checkSpan("java-thrift", 2);
  }

  private static TServer startNewThreadPoolServer(TServerTransport transport) {

    TProtocolFactory protocolFactory = new TBinaryProtocol.Factory();

    CustomHandler customHandler = new CustomHandler();
    final TProcessor customProcessor = new CustomService.Processor<CustomService.Iface>(
        customHandler);

    TThreadPoolServer.Args args = new TThreadPoolServer.Args(transport)
        .processorFactory(new TProcessorFactory(customProcessor))
        .protocolFactory(protocolFactory)
        .minWorkerThreads(5)
        .maxWorkerThreads(10);

    final TServer server = new TThreadPoolServer(args);

    new Thread(new Runnable() {
      @Override
      public void run() {
        server.serve();
      }
    }).start();

    return server;
  }

}