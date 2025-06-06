/*
 * Copyright 2024-2025 marks.yag@gmail.com
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

package com.github.yag.cr.local

import com.github.yag.crypto.AESCrypto
import com.google.common.net.HostAndPort
import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.HttpConstants
import io.netty.handler.codec.http.HttpMethod
import ketty.core.client.KettyClient
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.net.URI
import java.util.regex.Pattern

class HttpServerHeadDecoder(private val connectionManager: ConnectionManager, val client: KettyClient, private val crypto: AESCrypto, private val metrics: Metrics) : SimpleChannelInboundHandler<ByteBuf>() {

    override fun channelRead0(ctx: ChannelHandlerContext, buf: ByteBuf) {
        val idx = ByteBufUtil.indexOf(CRLF.slice(), buf)
        check(idx > 0) {
            buf.toString(Charsets.UTF_8)
        }//TODO what if else
        val data = buf.slice(0, idx)
        val seq = data.toString(Charsets.UTF_8)
        LOG.info("Http read: {}", seq)

        val (method, uri, protocolVersion) = seq.split(SP)
        val httpProxyRequestHead = when (method) {
            HttpMethod.CONNECT.name() -> {
                //https tunnel proxy
                val hostAndPort = HostAndPort.fromString(uri)
                HttpProxyRequestHead(hostAndPort.host, hostAndPort.port, HttpProxyType.TUNNEL, protocolVersion, Unpooled.EMPTY_BUFFER)
            }
            else -> {
                LOG.info("method: {}", method)

                //http proxy
                val uri = URI.create(uri)
                if (uri.isAbsolute) {
                    val url = uri.toURL()
                    HttpProxyRequestHead(
                        url.host,
                        if (url.port == -1) 80 else url.port,
                        HttpProxyType.WEB,
                        protocolVersion,
                        buf.resetReaderIndex()
                    )
                } else {
                    val message = metrics.scrape()
                    ctx.write(Unpooled.wrappedBuffer("HTTP/1.1 200 OK\r\n".toByteArray()))
                    ctx.write(Unpooled.wrappedBuffer("Content-Type: text/plain\r\n".toByteArray()))
                    ctx.write(Unpooled.wrappedBuffer("Content-Length: ${message.length}\r\n\r\n".toByteArray()))
                    ctx.writeAndFlush(Unpooled.wrappedBuffer(message.toByteArray()))
                    return
                }
            }
        }
        connectionManager.addConnection(ctx.channel().id(), HttpConnection(ctx.channel().remoteAddress() as InetSocketAddress, httpProxyRequestHead.proxyType, httpProxyRequestHead.host + ":" + httpProxyRequestHead.port, connectionManager))
        ctx.pipeline().addLast(HttpServerConnectHandler(connectionManager, client, crypto, metrics)).remove(this)
        ctx.fireChannelRead(httpProxyRequestHead)
    }

    companion object {

        // See https://tools.ietf.org/html/rfc7230#section-3.5
        private val SP = Pattern.compile("[\u0020\u0009\u000b\u000c\u000d]")

        private val CRLF = Unpooled.wrappedBuffer(byteArrayOf(HttpConstants.CR, HttpConstants.LF))


        private val LOG = LoggerFactory.getLogger(HttpServerConnectHandler::class.java)
    }
}
