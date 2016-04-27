/*
 * Copyright (c) 2015, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

package org.wso2.carbon.transport.http.netty.listener;

import com.lmax.disruptor.RingBuffer;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.LastHttpContent;
import org.apache.commons.pool.impl.GenericObjectPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.messaging.CarbonCallback;
import org.wso2.carbon.messaging.CarbonMessage;
import org.wso2.carbon.messaging.Constants;
import org.wso2.carbon.messaging.DefaultCarbonMessage;
import org.wso2.carbon.transport.http.netty.NettyCarbonMessage;
import org.wso2.carbon.transport.http.netty.common.HttpRoute;
import org.wso2.carbon.transport.http.netty.common.Util;
import org.wso2.carbon.transport.http.netty.common.disruptor.config.DisruptorConfig;
import org.wso2.carbon.transport.http.netty.common.disruptor.config.DisruptorFactory;
import org.wso2.carbon.transport.http.netty.common.disruptor.publisher.CarbonEventPublisher;
import org.wso2.carbon.transport.http.netty.internal.NettyTransportContextHolder;
import org.wso2.carbon.transport.http.netty.sender.channel.TargetChannel;
import org.wso2.carbon.transport.http.netty.sender.channel.pool.ConnectionManager;

import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;

/**
 * A Class responsible for handle  incoming message through netty inbound pipeline.
 */
public class SourceHandler extends ChannelInboundHandlerAdapter {
    private static Logger log = LoggerFactory.getLogger(SourceHandler.class);

    private RingBuffer disruptor;
    protected ChannelHandlerContext ctx;
    protected NettyCarbonMessage cMsg;
    protected ConnectionManager connectionManager;
    private Map<String, TargetChannel> channelFutureMap = new HashMap<>();

    private DisruptorConfig disruptorConfig;
    protected Map<String, GenericObjectPool> targetChannelPool;

    public SourceHandler(ConnectionManager connectionManager) throws Exception {
        this.connectionManager = connectionManager;
    }

    @Override
    public void channelActive(final ChannelHandlerContext ctx) throws Exception {
        // Start the server connection Timer

        NettyTransportContextHolder.getInstance().getHandlerExecutor()
                .executeAtSourceConnectionInitiation(Integer.toString(ctx.hashCode()));

        disruptorConfig = DisruptorFactory.getDisruptorConfig(DisruptorFactory.DisruptorType.INBOUND);
        disruptor = disruptorConfig.getDisruptor();
        this.ctx = ctx;
        this.targetChannelPool = connectionManager.getTargetChannelPool();

    }

    @SuppressWarnings("unchecked")
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof HttpRequest) {

            cMsg = (NettyCarbonMessage) setUPCarbonMessage(msg);
            cMsg.setProperty(org.wso2.carbon.transport.http.netty.common.Constants.IS_DISRUPTOR_ENABLE, "true");
            if (disruptorConfig.isShared()) {
                cMsg.setProperty(Constants.DISRUPTOR, disruptor);
            }
            boolean continueRequest = NettyTransportContextHolder.getInstance().getHandlerExecutor()
                    .executeRequestContinuationValidator(cMsg, carbonMessage -> {
                        CarbonCallback responseCallback = (CarbonCallback) cMsg.getProperty(Constants.CALL_BACK);
                        responseCallback.done(carbonMessage);
                    });
            if (continueRequest && !RequestSizeValidationConfiguration.getInstance().isRequestSizeValidation()) {
                disruptor.publishEvent(new CarbonEventPublisher(cMsg));
            }

        } else {
            if (cMsg != null) {
                if (msg instanceof HttpContent) {
                    HttpContent httpContent = (HttpContent) msg;
                    cMsg.addHttpContent(httpContent);
                    if (msg instanceof LastHttpContent) {
                        cMsg.setEndOfMsgAdded(true);
                        if (RequestSizeValidationConfiguration.getInstance().isRequestSizeValidation()) {
                            if (cMsg.getMessageBodyLength() > RequestSizeValidationConfiguration.getInstance()
                                    .getRequestMaxSize()) {
                                CarbonCallback responseCallback = (CarbonCallback) cMsg
                                        .getProperty(Constants.CALL_BACK);
                                responseCallback.done(createRejectResponse());

                            } else {
                                disruptor.publishEvent(new CarbonEventPublisher(cMsg));
                            }
                        }
                        NettyTransportContextHolder.getInstance().getHandlerExecutor()
                                .executeAtSourceRequestSending(cMsg);

                    }
                }
            }
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        // Stop the connector timer

        NettyTransportContextHolder.getInstance().getHandlerExecutor()
                .executeAtSourceConnectionTermination(Integer.toString(ctx.hashCode()));

        disruptorConfig.notifyChannelInactive();
        connectionManager.notifyChannelInactive();
    }

    public void addTargetChannel(HttpRoute route, TargetChannel targetChannel) {
        channelFutureMap.put(route.toString(), targetChannel);
    }

    public void removeChannelFuture(HttpRoute route) {
        log.debug("Removing channel future from map");
        channelFutureMap.remove(route.toString());
    }

    public TargetChannel getChannel(HttpRoute route) {
        return channelFutureMap.get(route.toString());
    }

    public Map<String, GenericObjectPool> getTargetChannelPool() {
        return targetChannelPool;
    }

    public ChannelHandlerContext getInboundChannelContext() {
        return ctx;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        super.exceptionCaught(ctx, cause);
        log.error("Exception caught in Netty Source handler", cause);
    }

    protected CarbonMessage setUPCarbonMessage(Object msg) {
        cMsg = new NettyCarbonMessage();
        NettyTransportContextHolder.getInstance().getHandlerExecutor().executeAtSourceRequestReceiving(cMsg);
        cMsg.setProperty(Constants.PORT, ((InetSocketAddress) ctx.channel().remoteAddress()).getPort());
        cMsg.setProperty(Constants.HOST, ((InetSocketAddress) ctx.channel().remoteAddress()).getHostName());
        ResponseCallback responseCallback = new ResponseCallback(this.ctx);
        cMsg.setProperty(Constants.CALL_BACK, responseCallback);
        HttpRequest httpRequest = (HttpRequest) msg;

        cMsg.setProperty(Constants.TO, httpRequest.getUri());
        cMsg.setProperty(Constants.CHNL_HNDLR_CTX, this.ctx);
        cMsg.setProperty(Constants.SRC_HNDLR, this);
        cMsg.setProperty(Constants.HTTP_VERSION, httpRequest.getProtocolVersion().text());
        cMsg.setProperty(Constants.HTTP_METHOD, httpRequest.getMethod().name());
        cMsg.setProperty(Constants.LISTENER_PORT, ((InetSocketAddress) ctx.channel().localAddress()).getPort());
        cMsg.setProperty(Constants.PROTOCOL, httpRequest.getProtocolVersion().protocolName());
        cMsg.setHeaders(Util.getHeaders(httpRequest));
        return cMsg;
    }

    protected CarbonMessage createRejectResponse() {
        DefaultCarbonMessage rejectResponse = new DefaultCarbonMessage();

        String rejectMessage = RequestSizeValidationConfiguration.getInstance().getRejectMessage();
        rejectResponse.setStringMessageBody(rejectMessage);
        byte[] errorMessageBytes = rejectMessage.getBytes(Charset.defaultCharset());

        Map<String, String> transportHeaders = new HashMap<>();
        transportHeaders.put(Constants.HTTP_CONNECTION, Constants.KEEP_ALIVE);
        transportHeaders.put(Constants.HTTP_CONTENT_ENCODING, Constants.GZIP);
        transportHeaders.put(Constants.HTTP_CONTENT_TYPE,
                RequestSizeValidationConfiguration.getInstance().getRejectMsgContentType());
        transportHeaders.put(Constants.HTTP_CONTENT_LENGTH, (String.valueOf(errorMessageBytes.length)));

        rejectResponse.setHeaders(transportHeaders);

        rejectResponse.setProperty(Constants.HTTP_STATUS_CODE,
                RequestSizeValidationConfiguration.getInstance().getStatusCode());
        rejectResponse.setProperty(Constants.DIRECTION, Constants.DIRECTION_RESPONSE);
        return rejectResponse;

    }

}



