package ml.hlaaftana.discordg.request

import java.util.concurrent.*

import org.eclipse.jetty.websocket.api.*
import org.eclipse.jetty.websocket.api.annotations.*

import java.util.Map

import ml.hlaaftana.discordg.util.*
import ml.hlaaftana.discordg.objects.*
import ml.hlaaftana.discordg.objects.Server.VoiceState

/**
 * The websocket client for the API.
 * @author Hlaaftana
 */
@WebSocket
class WSClient{
	CountDownLatch latch = new CountDownLatch(1)
	API api
	Session session
	Thread keepAliveThread
	def threadPool
	WSClient(API api){ this.api = api; threadPool = Executors.newFixedThreadPool(api.eventThreadCount) }

	@OnWebSocketConnect
	void onConnect(Session session){
		Log.info "Connected to server."
		this.session = session
		this.session.policy.maxTextMessageSize = Integer.MAX_VALUE
		this.session.policy.maxTextMessageBufferSize = Integer.MAX_VALUE
		this.session.policy.maxBinaryMessageSize = Integer.MAX_VALUE
		this.session.policy.maxBinaryMessageBufferSize = Integer.MAX_VALUE
		Map a = [
			"op": 2,
			"d": [
				"token": api.token,
				"v": 3,
				"properties": [
					"\$os": System.getProperty("os.name"),
					"\$browser": "DiscordG",
					"\$device": "Groovy",
					"\$referrer": "https://discordapp.com/@me",
					"\$referring_domain": "discordapp.com",
				],
			],
		]
		if (api.largeThreshold) a["d"]["large_threshold"] = api.largeThreshold
		this.send(a)
		Log.info "Sent API details."
		latch.countDown()
	}

	@OnWebSocketMessage
	void onMessage(Session session, String message) throws IOException{
		threadPool.submit({
			Map content = JSONUtil.parse(message)
			String type = content["t"]
			if (api.ignorePresenceUpdate && type == "PRESENCE_UPDATE") return
			Map data = content["d"]
			int responseAmount = content["s"]
			if (type.equals("READY") || type.equals("RESUMED")){
				long heartbeat = data["heartbeat_interval"]
				try{
					keepAliveThread = new Thread({
						while (true){
							this.send(["op": 1, "d": System.currentTimeMillis().toString()])
							try{ Thread.sleep(heartbeat) }catch (InterruptedException ex){}
						}
					})
					keepAliveThread.setDaemon(true)
					keepAliveThread.start()
				}catch (e){
					e.printStackTrace()
					System.exit(0)
				}
				api.readyData = data
				api.readyData.guilds.each { Map g ->
					g.members.each { Map m ->
						m["guild_id"] = g["id"]
					}
					g.channels.each { Map c ->
						c["guild_id"] = g["id"]
						c["is_private"] = false
						if (c["type"] == "text"){
							c["cached_messages"] = []
						}
					}
				}
				api.readyData.private_channels.each { Map pc ->
					pc["cached_messages"] = []
				}
				Log.info "Done loading."
			}
			if (!api.isLoaded()) return
			Map eventData = [:]
			Closure t = { String ty -> return ty.equals(type) }
			// i removed the switch here because it was slow
			try{
				if (t("READY")){
					eventData = data
				}else if (t("CHANNEL_CREATE") || t("CHANNEL_DELETE") || t("CHANNEL_UPDATE")){
					if (!data.containsKey("guild_id")){
						eventData = [
							server: null,
							guild: null,
							channel: new PrivateChannel(api, data)
							]
					}else if (data["type"].equals("text")){
						eventData = [
							server: api.client.getServerById(data["guild_id"]),
							guild: api.client.getServerById(data["guild_id"]),
							channel: new TextChannel(api, data)
							]
					}else if (data["type"].equals("voice")){
						eventData = [
							server: api.client.getServerById(data["guild_id"]),
							guild: api.client.getServerById(data["guild_id"]),
							channel: new VoiceChannel(api, data)
							]
					}
				}else if (t("GUILD_BAN_ADD") && t("GUILD_BAN_REMOVE")){
					eventData = [
						server: api.client.getServerById(data["guild_id"]),
						guild: api.client.getServerById(data["guild_id"]),
						user: new User(api, data["user"])
						]
				}else if (t("GUILD_CREATE")){
					if (!data.containsKey("unavailable")){
						eventData = [
							server: new Server(api, data),
							guild: new Server(api, data)
							]
					}else{
						eventData = data
					}
				}else if (t("GUILD_DELETE")){
					if (!data.containsKey("unavailable")){
						eventData = [
							server: api.client.getServerById(data["id"]),
							guild: api.client.getServerById(data["id"])
							]
					}else{
						eventData = data
					}
				}else if (t("GUILD_INTEGRATIONS_UPDATE")){
					eventData = [
						server: api.client.getServerById(data["guild_id"]),
						guild: api.client.getServerById(data["guild_id"])
						]
				}else if (t("GUILD_MEMBER_ADD") || t("GUILD_MEMBER_UPDATE")){
					eventData = [
						server: api.client.getServerById(data["guild_id"]),
						guild: api.client.getServerById(data["guild_id"]),
						member: new Member(api, data)
						]
				}else if (t("GUILD_MEMBER_REMOVE")){
					try{
						eventData = [
							server: api.client.getServerById(data["guild_id"]),
							guild: api.client.getServerById(data["guild_id"]),
							member: api.client.getServerById(data["guild_id"]).getMembers().find { try{it.getUser().getId().equals(data["user"]["id"])}catch (ex){} }
						]
					}catch (ex){
						println data
						println data["user"]
					}
				}else if (t("GUILD_ROLE_CREATE") || t("GUILD_ROLE_UPDATE")){
					eventData = [
						server: api.client.getServerById(data["guild_id"]),
						guild: api.client.getServerById(data["guild_id"]),
						role: new Role(api, data["role"])
						]
				}else if (t("GUILD_ROLE_DELETE")){
					eventData = [
						server: api.client.getServerById(data["guild_id"]),
						guild: api.client.getServerById(data["guild_id"]),
						role: api.client.getServerById(data["guild_id"]).getRoles().find { it.getId().equals(data["role_id"]) }
						]
				}else if (t("GUILD_UPDATE")){
					Map newData = data
					Server oldServer = api.client.getServerById(newData["id"])
					List<Map> memberJsons = new ArrayList<Map>()
					for (m in oldServer.getMembers()){
						memberJsons.add(m.object)
					}
					newData.put("members", memberJsons)
					eventData = [
						server: new Server(api, newData),
						guild: new Server(api, newData)
						]
				}else if (t("MESSAGE_CREATE")){
					eventData = [
						message: new Message(api, data),
						sendMessage: { String cont, boolean tts=false ->
							eventData.message.channel.sendMessage(cont, tts)
						},
						sendFile: { File file -> eventData.message.channel.sendFile(file) },
						sendFile: { String file -> eventData.message.channel.sendFile(file) }
						]
					if (eventData.message.server == null){
						eventData.message = new Message(api, data)
					}
				}else if (t("MESSAGE_DELETE")){
					List<TextChannel> channels = new ArrayList<TextChannel>()
					for (s in api.client.getServers()) channels.addAll(s.getTextChannels())
					channels.addAll(api.client.getPrivateChannels())
					Message foundMessage = channels*.cachedLogs.find { it.id == data["id"] }
					eventData = [
						channel: channels.find { it.getId().equals(data["channel_id"]) },
						message: (foundMessage != null) ? foundMessage : data["id"]
						]
				}else if (t("MESSAGE_UPDATE")){
					if (data.containsKey("content")){
						eventData = [
							message: new Message(api, data)
							]
					}else{
						List<TextChannel> channels = new ArrayList<TextChannel>()
						for (s in api.client.getServers()) channels.addAll(s.getTextChannels())
						channels.addAll(api.client.getPrivateChannels())
						Message foundMessage = channels*.cachedLogs.find { it.id == data["id"] }
						eventData = [
							channel: api.client.getTextChannelById(data["channel_id"]),
							message: (foundMessage != null) ? foundMessage : data["id"],
							embeds: data["embeds"]
							]
					}
				}else if (t("PRESENCE_UPDATE")){
					try{
						eventData = [
							server: api.client.servers.find { it.id == data["guild_id"] },
							guild: api.client.servers.find { it.id == data["guild_id"] },
							member: api.client.servers.find({ it.id == data["guild_id"] }).members.find { try { it.id == data["user"]["id"] }catch (ex){ false } },
							game: (data["game"] != null) ? data["game"]["name"] : "",
							status: data["status"]
						]
					}catch (ex){
						if (api.client.servers.find { it.id == data["guild_id"] } != null){
							eventData.server = api.client.servers.find { it.id == data["guild_id"] }
							eventData.guild = eventData.server
						}
					}
					if (eventData.server == null){
						if (api.client.servers.find { it.id == data["guild_id"] } != null){
							eventData.server = api.client.servers.find { it.id == data["guild_id"] }
							eventData.guild = eventData.server
						}
					}
					// this here is sort of dangerous
					if (eventData.member == null) eventData.member = new Member(api, data)
					if (data["user"]["username"] != null) eventData["newUser"] = new User(api, data["user"])
				}else if (t("TYPING_START")){
					eventData = [
						channel: api.client.getTextChannelById(data["channel_id"]),
						user: api.client.getUserById(data["user_id"])
						]
				}else if (t("VOICE_STATE_UPDATE")){
					eventData = [
						voiceState: new VoiceState(api, data)
						]
					if (data["user_id"] == api.client.user.id){
						if (api.voiceWsClient != null){ // voice connected
							api.voiceData.channel = api.client.getVoiceChannelById(data["channel_id"])
						}

						api.voiceData.session_id = data["session_id"]
					}
				}else if (t("VOICE_SERVER_UPDATE")){
					api.voiceData << data
					eventData = data
				}else{
					eventData = data
				}
			}catch (ex){
				if (Log.enableEventRegisteringCrashes) ex.printStackTrace()
				Log.info "Ignoring exception from event object registering"
			}
			if (!t("READY")) eventData.put("fullData", data)
			else if (api.copyReady) eventData.put("fullData", data)
			Map event = eventData
			if (api.isLoaded()){
				api.dispatchEvent(type, event)
			}
		} as Callable)
	}

	@OnWebSocketClose
	void onClose(Session session, int code, String reason){
		Log.info "Connection closed. Reason: " + reason + ", code: " + code.toString()
		try{
			if (keepAliveThread != null) keepAliveThread.interrupt(); keepAliveThread = null
		}catch (ex){

		}
	}

	@OnWebSocketError
	void onError(Throwable t){
		t.printStackTrace()
	}

	/**
	 * Converts an object to a string then sends it as a websocket message. Converts Maps to JSON strings.
	 * @param message - the object to convert to a string.
	 */
	void send(Object message){
		try{
			if (message instanceof Map)
				session.remote.sendString(JSONUtil.json(message))
			else
				session.remote.sendString(message.toString())
		}catch (e){
			e.printStackTrace()
		}
	}
}

