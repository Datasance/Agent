/*
 * *******************************************************************************
 *  * Copyright (c) 2023 Datasance Teknoloji A.S.
 *  *
 *  * This program and the accompanying materials are made available under the
 *  * terms of the Eclipse Public License v. 2.0 which is available at
 *  * http://www.eclipse.org/legal/epl-2.0
 *  *
 *  * SPDX-License-Identifier: EPL-2.0
 *  *******************************************************************************
 *
 */
package org.eclipse.iofog.local_api;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.websocketx.*;

import org.eclipse.iofog.exception.AgentSystemException;
import org.eclipse.iofog.status_reporter.StatusReporter;
import org.eclipse.iofog.utils.logging.LoggingService;

import java.util.ArrayList;
import java.util.Map;

import static io.netty.handler.codec.http.HttpHeaderNames.HOST;

/**
 * Handler for the real-time control websocket Open real-time control websocket
 * Send control-signals
 * 
 * @author ashita
 * @since 2016
 */
public class ControlWebsocketHandler {
	private static final String MODULE_NAME = "Local API";

	private static final Byte OPCODE_PING = 0x9;
	private static final Byte OPCODE_PONG = 0xA;
	private static final Byte OPCODE_ACK = 0xB;
	private static final Byte OPCODE_CONTROL_SIGNAL = 0xC;
	private static final Byte OPCODE_RESOURCE_SIGNAL = 0xF;

	private static final String WEBSOCKET_PATH = "/v2/control/socket";

	/**
	 * Handler to open the websocket for the real-time control signals
	 * 
	 * @param ctx,
	 * @param req,
	 *
	 * @return void
	 */
	public void handle(ChannelHandlerContext ctx, HttpRequest req) {
		try {
			LoggingService.logDebug(MODULE_NAME, "Open the websocket for the real-time control signals");
			String uri = req.uri();
			uri = uri.substring(1);
			String[] tokens = uri.split("/");

			String id;

			if (tokens.length < 5) {
				LoggingService.logError(MODULE_NAME, " Missing ID or ID value in URL ",
						new AgentSystemException(" Missing ID or ID value in URL"));
				return;
			} else {
				id = tokens[4].trim();
			}

			// Handshake
			WebSocketServerHandshakerFactory wsFactory = new WebSocketServerHandshakerFactory(getWebSocketLocation(req),
					null, true, Integer.MAX_VALUE);
			WebSocketServerHandshaker handshaker = wsFactory.newHandshaker(req);
			if (handshaker == null) {
				WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
			} else {
				handshaker.handshake(ctx.channel(), req);
			}

			WebSocketMap.addWebsocket('C', id, ctx);
			StatusReporter.setLocalApiStatus().setOpenConfigSocketsCount(WebSocketMap.controlWebsocketMap.size());
			LoggingService.logDebug(MODULE_NAME, "Websocket for the real-time control signals is open");
		} catch (Exception e) {
			LoggingService.logError(MODULE_NAME, "Error in Handler to open the websocket for the real-time control signals",
					new AgentSystemException("Error in Handler to open the websocket for the real-time control signals"));
		}
	}

	/**
	 * Handler for the real-time control signals Receive ping and send pong Send
	 * control signals to container on configuration change
	 * 
	 * @param ctx,
	 * @param frame,
	 * @return void
	 */
	public void handleWebSocketFrame(ChannelHandlerContext ctx, WebSocketFrame frame) {
		LoggingService.logDebug(MODULE_NAME, "Send control signals to container on configuration change");
		if (frame instanceof PingWebSocketFrame) {
			ByteBuf buffer = frame.content();
			if (buffer.readableBytes() == 1) {
				Byte opcode = buffer.readByte();
				if (opcode == OPCODE_PING.intValue()) {
					if (WebsocketUtil.hasContextInMap(ctx, WebSocketMap.controlWebsocketMap)) {
						ByteBuf buffer1 = ctx.alloc().buffer();
						buffer1.writeByte(OPCODE_PONG.intValue());
						ctx.channel().write(new PongWebSocketFrame(buffer1));
					}
				}
			} else {
				LoggingService.logDebug(MODULE_NAME, "Opcode not found for sending control signal");
			}

			return;
		}

		if (frame instanceof BinaryWebSocketFrame) {
			ByteBuf buffer2 = frame.content();
			if (buffer2.readableBytes() == 1) {
				Byte opcode = buffer2.readByte();
				if (opcode == OPCODE_ACK.intValue()) {
					WebSocketMap.unackControlSignalsMap.remove(ctx);
					return;
				}
			}
		}

		if (frame instanceof CloseWebSocketFrame) {
			ctx.channel().close();
			WebsocketUtil.removeWebsocketContextFromMap(ctx, WebSocketMap.controlWebsocketMap);
			StatusReporter.setLocalApiStatus().setOpenConfigSocketsCount(WebSocketMap.controlWebsocketMap.size());
		}
	}

	/**
	 * Helper method to compare the configuration map to start control signals
	 * 
	 * @param oldConfigMap
	 * @param newConfigMap
	 * @return void
	 */
	public void initiateControlSignal(Map<String, String> oldConfigMap, Map<String, String> newConfigMap) {
		// Compare the old and new config map
		Map<String, ChannelHandlerContext> controlMap = WebSocketMap.controlWebsocketMap;
		ArrayList<String> changedConfigElmtsList = new ArrayList<>();
		if (newConfigMap != null && newConfigMap.size() > 0) {
			for (Map.Entry<String, String> newEntry : newConfigMap.entrySet()) {
				String newMapKey = newEntry.getKey();
				if (!oldConfigMap.containsKey(newMapKey)) {
					changedConfigElmtsList.add(newMapKey);
				} else {

					String newConfigValue = newEntry.getValue();
					String oldConfigValue = oldConfigMap.get(newMapKey);
					if (!newConfigValue.equals(oldConfigValue)) {
						changedConfigElmtsList.add(newMapKey);
					}
				}
			}
		}


		for (String changedConfigElmtId : changedConfigElmtsList) {
			if (controlMap.containsKey(changedConfigElmtId)) {
				ChannelHandlerContext ctx = controlMap.get(changedConfigElmtId);
				WebSocketMap.unackControlSignalsMap.put(ctx, new ControlSignalSentInfo(1, System.currentTimeMillis()));

				ByteBuf buffer1 = ctx.alloc().buffer();
				buffer1.writeByte(OPCODE_CONTROL_SIGNAL);
				ctx.channel().writeAndFlush(new BinaryWebSocketFrame(buffer1));
			}
		}
	}

	/**
	 * Websocket path
	 * 
	 * @param req
	 * @return void
	 */
	private static String getWebSocketLocation(HttpRequest req) {
		String location = req.headers().get(HOST) + WEBSOCKET_PATH;
		if (LocalApiServer.SSL) {
			return "wss://" + location;
		} else {
			return "ws://" + location;
		}
	}

	public void initiateResourceSignal() {
		Map<String, ChannelHandlerContext> controlMap = WebSocketMap.controlWebsocketMap;
		for (Map.Entry<String, ChannelHandlerContext> newEntry : controlMap.entrySet()) {
			ChannelHandlerContext ctx = newEntry.getValue();
			WebSocketMap.unackControlSignalsMap.put(ctx, new ControlSignalSentInfo(1, System.currentTimeMillis()));

			ByteBuf buffer1 = ctx.alloc().buffer();
			buffer1.writeByte(OPCODE_RESOURCE_SIGNAL);
			ctx.channel().writeAndFlush(new BinaryWebSocketFrame(buffer1));
		}

	}
}