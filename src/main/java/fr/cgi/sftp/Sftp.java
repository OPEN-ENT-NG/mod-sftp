/*
 * Copyright © WebServices pour l'Éducation, 2014
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

package fr.cgi.sftp;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.userauth.keyprovider.KeyProvider;
import net.schmizz.sshj.xfer.FileSystemFile;
import org.vertx.java.busmods.BusModBase;
import org.vertx.java.core.Handler;
import org.vertx.java.core.eventbus.Message;
import org.vertx.java.core.json.JsonObject;
import org.vertx.java.core.logging.Logger;

import java.io.File;
import java.util.LinkedList;
import java.util.List;

public class Sftp extends BusModBase implements Handler<Message<JsonObject>> {


	private final String KNOWN_HOSTS = "known-hosts";
	private final String HOSTNAME = "hostname";
	private final String PORT = "port";
	private final String USERNAME = "username";
	private final String PASSWORD = "password";
	private final String SSHKEY = "sshkey";
	private final String LOCAL_FILE = "local-file";
	private final String DIST_FILE = "dist-file";
	private Logger log;

	@Override
	public void start() {
		super.start();
		vertx.eventBus().registerHandler(config.getString("address", "sftp"), this);
		log = container.logger();
	}

	@Override
	public void handle(Message<JsonObject> message) {
		log.info("Message received on SFTP");
		String action = message.body().getString("action", "");
		switch (action) {
			case "send":
				sendSftp(message);
				break;
			default:
				sendError(message, "Invalid action.");
		}
	}

	/**
	 * Send file through sftp
	 * @param message Message containing params and which will be replied to
	 */
	private void sendSftp(Message<JsonObject> message) {
		SSHClient ssh = new SSHClient();
		SFTPClient sftp = null;
		JsonObject params = message.body();
		if(validateParams(message)) {

			try {
				ssh.loadKnownHosts(new File(params.getString(KNOWN_HOSTS)));
				if (params.containsField(PORT)) {
					ssh.connect(params.getString(HOSTNAME), params.getInteger(PORT));
				} else {
					ssh.connect(params.getString(HOSTNAME));
				}
				boolean connected = false;
				try {
					if (params.containsField(SSHKEY)) {
						KeyProvider kp = ssh.loadKeys(params.getString(SSHKEY));
						List<KeyProvider> kplist = new LinkedList<>();
						kplist.add(kp);
						ssh.authPublickey(params.getString(USERNAME), kplist);
						connected = true;
					}
				} catch (Exception e) {
					log.error("Error when connecting with SSH Key ", e);
				}
				if (!connected && params.containsField(PASSWORD)) {
					ssh.authPassword(params.getString(USERNAME), params.getString(PASSWORD));
					connected = true;
				}
				if (connected) {
					sftp = ssh.newSFTPClient();
					sftp.put(new FileSystemFile(params.getString(LOCAL_FILE)), params.getString(DIST_FILE));
					sendOK(message);
				} else {
					sendError(message, "Could not connect to SFTP");
				}
			} catch (Exception e) {
				String errorMessage = "Error when connecting to sftp server " + e.getMessage();
				log.error(errorMessage);
				sendError(message, errorMessage);
			} finally {
				try {
					ssh.disconnect();
					if(sftp != null) {
						sftp.close();
					}
				} catch (Exception e) {
					log.warn("Error when disconnecting from SSH/SFTP ", e);
				}
			}
		}
	}

	/**
	 * Validate all mandatory params are present
	 * Reply to message otherwise
	 * @param message Message containing the params.
	 * @return true if all mandatory params are presents
	 */
	private boolean validateParams(Message<JsonObject> message) {
		JsonObject params = message.body();

		if( params.getString(KNOWN_HOSTS, "").isEmpty() ) {
			sendError(message, KNOWN_HOSTS + " absent");
			return false;
		}
		if( params.getString(HOSTNAME, "").isEmpty() ) {
			sendError(message, HOSTNAME + " absent");
			return false;
		}
		if( params.getString(USERNAME, "").isEmpty() ) {
			sendError(message, USERNAME + " absent");
			return false;
		}
		if( params.getString(PASSWORD, "").isEmpty()
				&& params.getString(SSHKEY, "").isEmpty() ) {
			sendError(message, PASSWORD + " and " + SSHKEY + " absent");
			return false;
		}
		if( params.getString(LOCAL_FILE, "").isEmpty() ) {
			sendError(message, LOCAL_FILE + " absent");
			return false;
		}
		if( params.getString(DIST_FILE, "").isEmpty() ) {
			sendError(message, DIST_FILE + " absent");
			return false;
		}
		if( params.containsField(PORT) && params.getInteger(PORT) == null) {
			params.removeField(PORT);
			log.warn("Wrong port format");
		}
		return true;
	}

}
