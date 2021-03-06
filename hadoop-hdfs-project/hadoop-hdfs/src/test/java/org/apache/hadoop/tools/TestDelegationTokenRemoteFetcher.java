/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.tools;

import static org.jboss.netty.handler.codec.http.HttpMethod.GET;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.OK;
import static org.jboss.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.Executors;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.security.token.delegation.DelegationTokenIdentifier;
import org.apache.hadoop.hdfs.tools.DelegationTokenFetcher;
import org.apache.hadoop.hdfs.web.HftpFileSystem;
import org.apache.hadoop.io.DataOutputBuffer;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.security.Credentials;
import org.apache.hadoop.security.token.Token;
import org.apache.log4j.Logger;
import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpChunkAggregator;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpRequestDecoder;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.HttpResponseEncoder;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.UnmodifiableIterator;

public class TestDelegationTokenRemoteFetcher {
  private static final Logger LOG = Logger
      .getLogger(TestDelegationTokenRemoteFetcher.class);

  private static final String EXP_DATE = "124123512361236";
  private static final String tokenFile = "http.file.dta";

  private int httpPort;
  private String serviceUrl;
  private FileSystem fileSys;
  private Configuration conf;
  private ServerBootstrap bootstrap;
  private Token<DelegationTokenIdentifier> testToken;
  private volatile AssertionError assertionError;
  
  @Before
  public void init() throws Exception {
    conf = new Configuration();
    fileSys = FileSystem.getLocal(conf);
    httpPort = NetUtils.getFreeSocketPort();
    serviceUrl = "http://localhost:" + httpPort;
    testToken = createToken(serviceUrl);
  }

  @After
  public void clean() throws IOException {
    if (fileSys != null)
      fileSys.delete(new Path(tokenFile), true);
    if (bootstrap != null)
      bootstrap.releaseExternalResources();
  }

  /**
   * try to fetch token without http server with IOException
   */
  @Test
  public void testTokenFetchFail() throws Exception {
    try {
      DelegationTokenFetcher.main(new String[] { "-webservice=" + serviceUrl,
          tokenFile });
      fail("Token fetcher shouldn't start in absense of NN");
    } catch (IOException ex) {
    }
  }
  
  /**
   * try to fetch token without http server with IOException
   */
  @Test
  public void testTokenRenewFail() {
    try {
      DelegationTokenFetcher.renewDelegationToken(serviceUrl, testToken);
      fail("Token fetcher shouldn't be able to renew tokens in absense of NN");
    } catch (IOException ex) {
    } 
  }     
  
  /**
   * try cancel token without http server with IOException
   */
  @Test
  public void expectedTokenCancelFail() {
    try {
      DelegationTokenFetcher.cancelDelegationToken(serviceUrl, testToken);
      fail("Token fetcher shouldn't be able to cancel tokens in absense of NN");
    } catch (IOException ex) {
    } 
  }
  
  /**
   * try fetch token and get http response with error
   */
  @Test  
  public void expectedTokenRenewErrorHttpResponse() {
    bootstrap = startHttpServer(httpPort, testToken, serviceUrl);
    try {
      DelegationTokenFetcher.renewDelegationToken(serviceUrl + "/exception", 
          createToken(serviceUrl));
      fail("Token fetcher shouldn't be able to renew tokens using an invalid"
          + " NN URL");
    } catch (IOException ex) {
    } 
    if (assertionError != null)
      throw assertionError;
  }
  
  /**
   *   
   *
   */
  @Test
  public void testCancelTokenFromHttp() throws IOException {
    bootstrap = startHttpServer(httpPort, testToken, serviceUrl);
    DelegationTokenFetcher.cancelDelegationToken(serviceUrl, testToken);
    if (assertionError != null)
      throw assertionError;
  }
  
  /**
   * Call renew token using http server return new expiration time
   */
  @Test
  public void testRenewTokenFromHttp() throws IOException {
    bootstrap = startHttpServer(httpPort, testToken, serviceUrl);
    assertTrue("testRenewTokenFromHttp error",
        Long.valueOf(EXP_DATE) == DelegationTokenFetcher.renewDelegationToken(
            serviceUrl, testToken));
    if (assertionError != null)
      throw assertionError;
  }

  /**
   * Call fetch token using http server 
   */
  @Test
  public void expectedTokenIsRetrievedFromHttp() throws Exception {
    bootstrap = startHttpServer(httpPort, testToken, serviceUrl);
    DelegationTokenFetcher.main(new String[] { "-webservice=" + serviceUrl,
        tokenFile });
    Path p = new Path(fileSys.getWorkingDirectory(), tokenFile);
    Credentials creds = Credentials.readTokenStorageFile(p, conf);
    Iterator<Token<?>> itr = creds.getAllTokens().iterator();
    assertTrue("token not exist error", itr.hasNext());
    Token<?> fetchedToken = itr.next();
    Assert.assertArrayEquals("token wrong identifier error",
        testToken.getIdentifier(), fetchedToken.getIdentifier());
    Assert.assertArrayEquals("token wrong password error",
        testToken.getPassword(), fetchedToken.getPassword());
    if (assertionError != null)
      throw assertionError;
  }
  
  private static Token<DelegationTokenIdentifier> createToken(String serviceUri) {
    byte[] pw = "hadoop".getBytes();
    byte[] ident = new DelegationTokenIdentifier(new Text("owner"), new Text(
        "renewer"), new Text("realuser")).getBytes();
    Text service = new Text(serviceUri);
    return new Token<DelegationTokenIdentifier>(ident, pw,
        HftpFileSystem.TOKEN_KIND, service);
  }

  private interface Handler {
    void handle(Channel channel, Token<DelegationTokenIdentifier> token,
        String serviceUrl) throws IOException;
  }

  private class FetchHandler implements Handler {
    
    @Override
    public void handle(Channel channel, Token<DelegationTokenIdentifier> token,
        String serviceUrl) throws IOException {
      Assert.assertEquals(testToken, token);

      Credentials creds = new Credentials();
      creds.addToken(new Text(serviceUrl), token);
      DataOutputBuffer out = new DataOutputBuffer();
      creds.write(out);
      int fileLength = out.getData().length;
      ChannelBuffer cbuffer = ChannelBuffers.buffer(fileLength);
      cbuffer.writeBytes(out.getData());
      HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
      response.setHeader(HttpHeaders.Names.CONTENT_LENGTH,
          String.valueOf(fileLength));
      response.setContent(cbuffer);
      channel.write(response).addListener(ChannelFutureListener.CLOSE);
    }
  }

  private class RenewHandler implements Handler {
    
    @Override
    public void handle(Channel channel, Token<DelegationTokenIdentifier> token,
        String serviceUrl) throws IOException {
      Assert.assertEquals(testToken, token);
      byte[] bytes = EXP_DATE.getBytes();
      ChannelBuffer cbuffer = ChannelBuffers.buffer(bytes.length);
      cbuffer.writeBytes(bytes);
      HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
      response.setHeader(HttpHeaders.Names.CONTENT_LENGTH,
          String.valueOf(bytes.length));
      response.setContent(cbuffer);
      channel.write(response).addListener(ChannelFutureListener.CLOSE);
    }
  }
  
  private class ExceptionHandler implements Handler {

    @Override
    public void handle(Channel channel, Token<DelegationTokenIdentifier> token,
        String serviceUrl) throws IOException {
      Assert.assertEquals(testToken, token);
      HttpResponse response = new DefaultHttpResponse(HTTP_1_1, 
          HttpResponseStatus.METHOD_NOT_ALLOWED);
      channel.write(response).addListener(ChannelFutureListener.CLOSE);
    }    
  }
  
  private class CancelHandler implements Handler {

    @Override
    public void handle(Channel channel, Token<DelegationTokenIdentifier> token,
        String serviceUrl) throws IOException {
      Assert.assertEquals(testToken, token);
      HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
      channel.write(response).addListener(ChannelFutureListener.CLOSE);
    }    
  }
  
  private final class CredentialsLogicHandler extends
      SimpleChannelUpstreamHandler {

    private final Token<DelegationTokenIdentifier> token;
    private final String serviceUrl;
    private ImmutableMap<String, Handler> routes = ImmutableMap.of(
        "/exception", new ExceptionHandler(),
        "/cancelDelegationToken", new CancelHandler(),
        "/getDelegationToken", new FetchHandler() , 
        "/renewDelegationToken", new RenewHandler());

    public CredentialsLogicHandler(Token<DelegationTokenIdentifier> token,
        String serviceUrl) {
      this.token = token;
      this.serviceUrl = serviceUrl;
    }

    @Override
    public void messageReceived(ChannelHandlerContext ctx, final MessageEvent e)
        throws Exception {
      HttpRequest request = (HttpRequest) e.getMessage();
      if (request.getMethod() != GET) {
        return;
      }
      UnmodifiableIterator<Map.Entry<String, Handler>> iter = routes.entrySet()
          .iterator();
      while (iter.hasNext()) {
        Map.Entry<String, Handler> entry = iter.next();
        if (request.getUri().contains(entry.getKey())) {
          Handler handler = entry.getValue();
          try {
            handler.handle(e.getChannel(), token, serviceUrl);
          } catch (AssertionError ee) {
            TestDelegationTokenRemoteFetcher.this.assertionError = ee;
            HttpResponse response = new DefaultHttpResponse(HTTP_1_1, 
                HttpResponseStatus.BAD_REQUEST);
            response.setContent(ChannelBuffers.copiedBuffer(ee.getMessage(), 
                Charset.defaultCharset()));
            e.getChannel().write(response).addListener(ChannelFutureListener.CLOSE);
          }
          return;
        }
      }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e)
        throws Exception {
      Channel ch = e.getChannel();
      Throwable cause = e.getCause();

      if (LOG.isDebugEnabled())
        LOG.debug(cause.getMessage());
      ch.close().addListener(ChannelFutureListener.CLOSE);
    }
  }

  private ServerBootstrap startHttpServer(int port,
      final Token<DelegationTokenIdentifier> token, final String url) {
    ServerBootstrap bootstrap = new ServerBootstrap(
        new NioServerSocketChannelFactory(Executors.newCachedThreadPool(),
            Executors.newCachedThreadPool()));

    bootstrap.setPipelineFactory(new ChannelPipelineFactory() {
      @Override
      public ChannelPipeline getPipeline() throws Exception {
        return Channels.pipeline(new HttpRequestDecoder(),
            new HttpChunkAggregator(65536), new HttpResponseEncoder(),
            new CredentialsLogicHandler(token, url));
      }
    });
    bootstrap.bind(new InetSocketAddress("localhost", port));
    return bootstrap;
  }
  
}
