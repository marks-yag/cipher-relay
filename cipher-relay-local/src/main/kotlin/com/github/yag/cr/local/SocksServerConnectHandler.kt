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

import com.github.yag.cr.protocol.AtProxyRequest
import com.github.yag.cr.protocol.VirtualChannel
import com.github.yag.crypto.AESCrypto
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.socksx.SocksMessage
import io.netty.handler.codec.socksx.v5.DefaultSocks5CommandResponse
import io.netty.handler.codec.socksx.v5.Socks5CommandRequest
import io.netty.handler.codec.socksx.v5.Socks5CommandStatus
import ketty.core.client.KettyClient
import ketty.core.common.isSuccessful
import ketty.core.common.use
import org.slf4j.LoggerFactory

@Sharable
class SocksServerConnectHandler(private val connectionManager: ConnectionManager, private val client: KettyClient, private val crypto: AESCrypto, private val metrics: Metrics) : SimpleChannelInboundHandler<SocksMessage>() {

    public override fun channelRead0(ctx: ChannelHandlerContext, message: SocksMessage) {
        val request = message as Socks5CommandRequest
        val connection = connectionManager.getConnection(ctx.channel().id())
        log.info("New socks5 connection: {}:{}.", request.dstAddr(), request.dstPort())
        Unpooled.wrappedBuffer((request.dstAddr() + ":" + request.dstPort()).toByteArray()).use {
            client.sendSync(AtProxyRequest.CONNECT, it).use { connect ->
                if (connect.isSuccessful()) {
                    val vc = VirtualChannel(connect.body.slice().readLong())
                    log.info("Connect to {}:{}, id:{}.", request.dstAddr(), request.dstPort(), vc)
                    ctx.channel().writeAndFlush(
                        DefaultSocks5CommandResponse(
                            Socks5CommandStatus.SUCCESS,
                            request.dstAddrType(),
                            request.dstAddr(),
                            request.dstPort()
                        )
                    )
                    MixinServerUtils.relay(connection, client, crypto, connect, ctx, metrics)
                } else {
                    ctx.channel().writeAndFlush(
                        DefaultSocks5CommandResponse(
                            Socks5CommandStatus.FAILURE, request.dstAddrType()
                        )
                    )
                }
            }
        }
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        connectionManager.removeConnection(ctx.channel().id())
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        log.debug("Unknown exception: {}.", connectionManager.getConnection(ctx.channel().id()), cause)
    }

    companion object {
        private val log = LoggerFactory.getLogger(SocksServerConnectHandler::class.java)
    }
}
