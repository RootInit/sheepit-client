/*
 * Copyright (C) 2010-2014 Laurent CLOUET
 * Author Laurent CLOUET <laurent.clouet@nopnop.net>
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; version 2
 * of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package com.sheepit.client;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.nio.file.Files;
import java.net.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.sheepit.client.datamodel.SpeedTestTarget;
import com.sheepit.client.datamodel.SpeedTestResult;
import com.sheepit.client.datamodel.SpeedTestTargetResult;
import com.sheepit.client.exception.FermeExceptionSessionDisabledDenoisingNotSupported;
import com.sheepit.client.hardware.hwid.HWIdentifier;
import com.sheepit.client.os.Windows;
import lombok.Getter;
import org.simpleframework.xml.core.Persister;

import okhttp3.Call;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.JavaNetCookieJar;

import com.sheepit.client.hardware.cpu.CPU;
import com.sheepit.client.Configuration.ComputeType;
import com.sheepit.client.Error.ServerCode;
import com.sheepit.client.datamodel.CacheFileMD5;
import com.sheepit.client.datamodel.FileMD5;
import com.sheepit.client.datamodel.HeartBeatInfos;
import com.sheepit.client.datamodel.JobInfos;
import com.sheepit.client.datamodel.JobValidation;
import com.sheepit.client.datamodel.RequestEndPoint;
import com.sheepit.client.datamodel.ServerConfig;
import com.sheepit.client.exception.FermeException;
import com.sheepit.client.exception.FermeExceptionBadResponseFromServer;
import com.sheepit.client.exception.FermeExceptionNoRendererAvailable;
import com.sheepit.client.exception.FermeExceptionNoRightToRender;
import com.sheepit.client.exception.FermeExceptionNoSession;
import com.sheepit.client.exception.FermeExceptionNoSpaceLeftOnDevice;
import com.sheepit.client.exception.FermeExceptionPathInvalid;
import com.sheepit.client.exception.FermeExceptionNoWritePermission;
import com.sheepit.client.exception.FermeExceptionServerInMaintenance;
import com.sheepit.client.exception.FermeExceptionServerOverloaded;
import com.sheepit.client.exception.FermeExceptionSessionDisabled;
import com.sheepit.client.exception.FermeServerDown;
import com.sheepit.client.os.OS;


public class Server extends Thread {
	private static final int NUMBER_OF_SPEEDTEST_RESULTS = 3;
	
	final private String HTTP_USER_AGENT = "Java/" + System.getProperty("java.version");
	private String base_url;
	private final OkHttpClient httpClient;
	
	@Getter private ServerConfig serverConfig;
	
	private Configuration user_config;
	private Client client;
	private Log log;
	private long lastRequestTime;
	private int keepmealive_duration; // time in ms
	
	private TransferStats dlStats = new TransferStats();
	private TransferStats ulStats = new TransferStats();
	
	public Server(String url_, Configuration user_config_, Client client_) {
		super();
		this.base_url = url_;
		this.user_config = user_config_;
		this.client = client_;
		this.log = Log.getInstance(this.user_config);
		this.lastRequestTime = 0;
		this.keepmealive_duration = 15 * 60 * 1000; // default 15min
		
		// OkHttp performs best when we create a single OkHttpClient instance and reuse it for all of the HTTP calls. This is because each client holds its own
		// connection pool and thread pools.Reusing connections and threads reduces latency and saves memory. Conversely, creating a client for each request
		// wastes resources on idle pools.
		this.httpClient = getOkHttpClient();
	}
	
	public void run() {
		this.stayAlive();
	}
	
	public void stayAlive() {
		while (true) {
			long current_time = new Date().getTime();
			if ((current_time - this.lastRequestTime) > this.keepmealive_duration) {
				try {
					HttpUrl.Builder urlBuilder = Objects.requireNonNull(HttpUrl.parse(this.getPage("keepmealive"))).newBuilder();
					urlBuilder.addQueryParameter("paused", String.valueOf(this.client.isSuspended()));
					
					if (this.client != null && this.client.getRenderingJob() != null) {
						Job job = this.client.getRenderingJob();
						
						urlBuilder.addQueryParameter("frame", job.getFrameNumber()).addQueryParameter("job", job.getId());
						
						RenderProcess process = job.getProcessRender();
						if (process != null) {
							urlBuilder.addQueryParameter("rendertime", String.valueOf(process.getDuration()))
								.addQueryParameter("remainingtime", String.valueOf(process.getRemainingDuration()));
						}
					}
					
					Response response = this.HTTPRequest(urlBuilder);
					
					if (response.code() == HttpURLConnection.HTTP_OK && response.body().contentType().toString().startsWith("text/xml")) {
						String in = response.body().string();
						
						try {
							HeartBeatInfos heartBeatInfos = new Persister().read(HeartBeatInfos.class, in);
							ServerCode serverCode = ServerCode.fromInt(heartBeatInfos.getStatus());
							if (serverCode == ServerCode.KEEPMEALIVE_STOP_RENDERING) {
								this.log.debug("Server::stayAlive server asked to kill local render process");
								// kill the current process, it will generate an error but it's okay
								if (this.client != null && this.client.getRenderingJob() != null) {
									this.client.getRenderingJob().setServerBlockJob(true);
									
									if (this.client.getRenderingJob().getProcessRender().getProcess() != null) {
										this.client.getRenderingJob().setAskForRendererKill(true);
										OS.getOS().kill(this.client.getRenderingJob().getProcessRender().getProcess());
									}
								}
							}
						}
						catch (Exception e) { // for the read
							this.log.debug("Server::stayAlive Exception " + e);
						}
					}
				}
				catch (NoRouteToHostException e) {
					this.log.debug("Server::stayAlive can not connect to server");
				}
				catch (IOException e) {
					StringWriter sw = new StringWriter();
					PrintWriter pw = new PrintWriter(sw);
					e.printStackTrace(pw);
					this.log.debug("Server::stayAlive IOException " + e + " stacktrace: " + sw.toString());
				}
			}
			try {
				Thread.sleep(60 * 1000); // 1min
			}
			catch (InterruptedException e) {
				return;
			}
			catch (Exception e) {
				StringWriter sw = new StringWriter();
				PrintWriter pw = new PrintWriter(sw);
				e.printStackTrace(pw);
				this.log.debug("Server::stayAlive Exception " + e + " stacktrace: " + sw.toString());
			}
		}
	}
	
	public String toString() {
		return String.format("Server (base_url '%s', user_config %s", this.base_url, this.user_config);
	}
	
	public Error.Type getConfiguration() {
		OS os = OS.getOS();
		String publickey = null;
		try {
			HttpUrl.Builder remoteURL = Objects.requireNonNull(HttpUrl.parse(this.base_url + "/server/config.php")).newBuilder();
			FormBody formBody = new FormBody.Builder()
				.add("login", user_config.getLogin())
				.add("password", user_config.getPassword())
				.add("cpu_family", os.getCPU().family())
				.add("cpu_model", os.getCPU().model())
				.add("cpu_model_name", os.getCPU().name())
				.add("cpu_cores", String.valueOf(user_config.getNbCores() == -1 ? os.getCPU().cores() : Math.max(CPU.MIN_CORES, user_config.getNbCores())))
				.add("os", os.name())
				.add("os_version", os.getVersion())
				.add("ram", String.valueOf(os.getTotalMemory()))
				.add("bits", os.getCPU().arch())
				.add("version", Configuration.jarVersion)
				.add("hostname", user_config.getHostname())
				.add("ui", client.getGui().getClass().getSimpleName())
				.add("extras", user_config.getExtras())
				.add("headless", java.awt.GraphicsEnvironment.isHeadless() ? "1" : (user_config.isHeadless() ? "1" : "0"))
				.add("hwid", new HWIdentifier(log).getHardwareHash())
				.build();
			
			this.log.debug("Server::getConfiguration url " + remoteURL.build().toString());
			
			Response response = this.HTTPRequest(remoteURL, formBody, false);
			int r = response.code();
			String contentType = response.body().contentType().toString();

			if (r != HttpURLConnection.HTTP_OK || contentType.startsWith("text/xml") == false) {
				return Error.Type.ERROR_BAD_SERVER_RESPONSE;
			}
			else if (r == HttpURLConnection.HTTP_NOT_FOUND) {
				// most likely the server instance is down but not the frontend proxy (traefik)
				return Error.Type.SERVER_DOWN;
			}

			String in = response.body().string();
			serverConfig = new Persister().read(ServerConfig.class, in);

			if (ServerCode.fromInt(serverConfig.getStatus()) != ServerCode.OK) {
				return Error.ServerCodeToType(ServerCode.fromInt(serverConfig.getStatus()));
			}

			publickey = serverConfig.getPublickey();
			if (publickey.isEmpty()) {
				publickey = null;
			}
			else {
				this.user_config.setPassword(publickey);
			}
		}
		catch (ConnectException e) {
			this.log.error("Server::getConfiguration error ConnectException " + e);
			return Error.Type.NETWORK_ISSUE;
		}
		catch (UnknownHostException e) {
			this.log.error("Server::getConfiguration: exception UnknownHostException " + e);
			return Error.Type.NETWORK_ISSUE;
		}
		catch (UnsupportedEncodingException e) {
			this.log.error("Server::getConfiguration: exception UnsupportedEncodingException " + e);
			return Error.Type.UNKNOWN;
		}
		catch (IOException e) {
			this.log.error("Server::getConfiguration: exception IOException " + e);
			return Error.Type.UNKNOWN;
		}
		catch (Exception e) {
			this.log.error("Server::getConfiguration: exception Exception " + e);
			return Error.Type.UNKNOWN;
		}
		
		if (serverConfig.getSpeedTestTargets() != null && serverConfig.getSpeedTestTargets().isEmpty() == false) {
			try {
				client.getGui().status("Checking mirror connection speeds");
				Speedtest speedtest = new Speedtest(log);
				List<SpeedTestTarget> bestSpeedTestTargets = speedtest.doSpeedtests(serverConfig.getSpeedTestTargets().stream().map(m -> m.getUrl()).collect(Collectors.toList()),
					NUMBER_OF_SPEEDTEST_RESULTS);
				SpeedTestResult result = new SpeedTestResult();
				result.setResults(bestSpeedTestTargets.stream().map(m -> {
					SpeedTestTargetResult targetResult = new SpeedTestTargetResult();
					targetResult.setTarget(m.getUrl());
					targetResult.setSpeed(m.getSpeedtest());
					targetResult.setPing((int) (m.getPing().getAverage()));
					return targetResult;
				}).collect(Collectors.toList()));
				
				final Persister persister = new Persister();
				try (StringWriter writer = new StringWriter()) {
					persister.write(result, writer);
					
					HttpUrl.Builder urlBuilder = Objects.requireNonNull(HttpUrl.parse(this.getPage("speedtest-answer"))).newBuilder();
					Response response = this.HTTPRequest(urlBuilder, RequestBody.create(MediaType.parse("application/xml"), writer.toString()));
					if (response.code() != HttpURLConnection.HTTP_OK) {
						this.log.error("Server::getConfiguration Speedtest unexpected response");
						return Error.Type.ERROR_BAD_SERVER_RESPONSE;
					}
				}
				catch (final Exception e) {
					throw new IOException("Server::getConfiguration Speedtest failed to generate payload");
				}
			}
			catch (IOException e) {
				this.log.error("Server::getConfiguration Speedtest failed: " + e);
				return Error.Type.NETWORK_ISSUE;
			}
		}
		
		client.setSessionStarted(true);
		this.client.getGui().successfulAuthenticationEvent(publickey);
		
		return Error.Type.OK;
	}
	
	public Job requestJob() throws FermeException {
		this.log.debug("Server::requestJob");
		String url_contents = "";
		
		try {
			OS os = OS.getOS();
			long maxMemory = this.user_config.getMaxAllowedMemory();
			long freeMemory = os.getFreeMemory() - 1024^2 /*One gigabyte*/ * (os instanceof Windows ? 2 : 1); //Make RAM to reserve 2GB on Windows
			if (maxMemory < 0) {
				maxMemory = freeMemory;
			}
			else if (freeMemory > 0 && maxMemory > 0) {
				maxMemory = Math.min(maxMemory, freeMemory);
			}
			
			HttpUrl.Builder urlBuilder = Objects.requireNonNull(HttpUrl.parse(this.getPage("request-job"))).newBuilder()
				.addQueryParameter("computemethod", String.valueOf(user_config.computeMethodToInt()))
				.addQueryParameter("network_dl", String.valueOf(dlStats.getRawAverageSessionSpeed()))
				.addQueryParameter("network_up", String.valueOf(ulStats.getRawAverageSessionSpeed()))
				.addQueryParameter("cpu_cores", String.valueOf(user_config.getNbCores() == -1 ? os.getCPU().cores() :
					(Math.max(user_config.getNbCores(), CPU.MIN_CORES))))
				.addQueryParameter("ram_max", String.valueOf(maxMemory))
				.addQueryParameter("rendertime_max", String.valueOf(user_config.getMaxRenderTime()));
			
			if (user_config.getComputeMethod() != ComputeType.CPU && user_config.getGPUDevice() != null) {
				urlBuilder.addQueryParameter("gpu_model", user_config.getGPUDevice().getModel())
					.addQueryParameter("gpu_ram", String.valueOf(user_config.getGPUDevice().getMemory()))
					.addQueryParameter("gpu_type", user_config.getGPUDevice().getType());
			}
			
			Response response = this.HTTPRequest(urlBuilder, RequestBody.create(this.generateXMLForMD5cache(), MediaType.parse("application/xml")));
			
			int r = response.code();
			if (r == HttpURLConnection.HTTP_UNAVAILABLE || r == HttpURLConnection.HTTP_CLIENT_TIMEOUT) {
				this.log.error("Server::requestJob server unavailable or down: " + response);
				throw new FermeServerDown();
			}
			else if(response.body().contentType().toString().startsWith("text/xml") == false) {
				this.log.error("Server::requestJob bad contentType received: " + response);
				throw new FermeExceptionBadResponseFromServer();
			}
			else if (r != HttpURLConnection.HTTP_OK) {
				this.log.error("Server::requestJob unexpected response" + response);
				throw new FermeException();
			}

			String in = response.body().string();

			JobInfos jobData = new Persister().read(JobInfos.class, in);

			handleFileMD5DeleteDocument(jobData.getFileMD5s());

			if (jobData.getSessionStats() != null) {
				this.client.getGui().displayStats(
						new Stats(jobData.getSessionStats().getRemainingFrames(), jobData.getSessionStats().getPointsEarnedByUser(),
								jobData.getSessionStats().getPointsEarnedOnSession(), jobData.getSessionStats().getRenderableProjects(),
								jobData.getSessionStats().getWaitingProjects(), jobData.getSessionStats().getConnectedMachines()));
			}

			ServerCode serverCode = ServerCode.fromInt(jobData.getStatus());
			if (serverCode != ServerCode.OK) {
				switch (serverCode) {
					case JOB_REQUEST_NOJOB:
						return null;
					case JOB_REQUEST_ERROR_NO_RENDERING_RIGHT:
						throw new FermeExceptionNoRightToRender();
					case JOB_REQUEST_ERROR_DEAD_SESSION:
						throw new FermeExceptionNoSession();
					case JOB_REQUEST_ERROR_SESSION_DISABLED:
						throw new FermeExceptionSessionDisabled();
					case JOB_REQUEST_ERROR_SESSION_DISABLED_DENOISING_NOT_SUPPORTED:
						throw new FermeExceptionSessionDisabledDenoisingNotSupported();
					case JOB_REQUEST_ERROR_INTERNAL_ERROR:
						throw new FermeExceptionBadResponseFromServer();
					case JOB_REQUEST_ERROR_RENDERER_NOT_AVAILABLE:
						throw new FermeExceptionNoRendererAvailable();
					case JOB_REQUEST_SERVER_IN_MAINTENANCE:
						throw new FermeExceptionServerInMaintenance();
					case JOB_REQUEST_SERVER_OVERLOADED:
						throw new FermeExceptionServerOverloaded();
					default:
						throw new FermeException("error requestJob: status is not ok (it's " + serverCode + ")");
				}
			}

			String validationUrl = URLDecoder.decode(jobData.getRenderTask().getValidationUrl(), "UTF-8");

			return new Job(this.user_config, this.client.getGui(), this.client.getLog(), jobData.getRenderTask().getId(),
					jobData.getRenderTask().getFrame(), jobData.getRenderTask().getPath().replace("/", File.separator),
					jobData.getRenderTask().getUseGpu() == 1, jobData.getRenderTask().getRendererInfos().getCommandline(), validationUrl,
					jobData.getRenderTask().getScript(), jobData.getRenderTask().getArchive_md5(), jobData.getRenderTask().getRendererInfos().getMd5(),
					jobData.getRenderTask().getName(), jobData.getRenderTask().getPassword(),
					jobData.getRenderTask().getSynchronous_upload().equals("1"), jobData.getRenderTask().getRendererInfos().getUpdate_method());
		}
		catch (FermeException e) {
			throw e;
		}
		catch (NoRouteToHostException e) {
			throw new FermeServerDown();
		}
		catch (UnknownHostException e) {
			throw new FermeServerDown();
		}
		catch (Exception e) {
			StringWriter sw = new StringWriter();
			PrintWriter pw = new PrintWriter(sw);
			e.printStackTrace(pw);
			throw new FermeException("error requestJob: unknown exception " + e + " stacktrace: " + sw.toString());
		}
	}
	
	public Response HTTPRequest(String url) throws IOException {
		HttpUrl.Builder httpUrlBuilder = Objects.requireNonNull(HttpUrl.parse(url)).newBuilder();
		return this.HTTPRequest(httpUrlBuilder, null);
	}
	
	public Response HTTPRequest(HttpUrl.Builder httpUrlBuilder) throws IOException {
		return this.HTTPRequest(httpUrlBuilder, null);
	}

	public Response HTTPRequest(HttpUrl.Builder httpUrlBuilder, RequestBody data_) throws IOException {
		String url = httpUrlBuilder.build().toString();
		Request.Builder builder = new Request.Builder().addHeader("User-Agent", HTTP_USER_AGENT).url(url);
		
		this.log.debug("Server::HTTPRequest url(" + url + ")");
		
		if (data_ != null) {
			builder.post(data_);
		}
		
		Request request = builder.build();
		Response response = null;
		
		try {
			response = httpClient.newCall(request).execute();
			
			if (!response.isSuccessful()) {
				this.log.error("Received unsuccessful HTTP response " + response);
			}
			
			this.lastRequestTime = new Date().getTime();
			return response;
		}
		catch (IOException e) {
			throw new IOException("Unexpected response from HTTP Stack" + e.getMessage());
		}
	}
	
	public Response HTTPRequest(HttpUrl.Builder httpUrlBuilder, RequestBody data_, boolean checkIsSuccessful) throws IOException {
		String url = httpUrlBuilder.build().toString();
		Request.Builder builder = new Request.Builder().addHeader("User-Agent", HTTP_USER_AGENT).url(url);
		
		this.log.debug("Server::HTTPRequest url(" + url + ")");
		
		if (data_ != null) {
			builder.post(data_);
		}
		
		Request request = builder.build();
		Response response = null;
		
		try {
			response = httpClient.newCall(request).execute();
			
			if (checkIsSuccessful && !response.isSuccessful()) {
				throw new IOException("Unexpected code " + response);
			}
			
			this.lastRequestTime = new Date().getTime();
			return response;
		}
		catch (IOException e) {
			throw new IOException("Unexpected response from HTTP Stack" + e.getMessage());
		}
	}
	
	public Error.Type HTTPGetFile(String url_, String destination_, Gui gui_, String status_) throws FermeException {
		InputStream is = null;
		OutputStream output = null;

		try {
			Response response = this.HTTPRequest(url_);
			
			if (response.code() != HttpURLConnection.HTTP_OK) {
				this.log.error("Server::HTTPGetFile(" + url_ + ", ...) HTTP code is not " + HttpURLConnection.HTTP_OK + " it's " + response.code());
				return Error.Type.DOWNLOAD_FILE;
			}
			
			is = response.body().byteStream();
			output = new FileOutputStream(destination_ + ".partial");
			
			long size = response.body().contentLength();
			byte[] buffer = new byte[8 * 1024];
			int len = 0;
			long written = 0;
			long lastUpd = 0;    // last GUI progress update
			
			LocalDateTime startRequestTime = LocalDateTime.now();
			
			while ((len = is.read(buffer)) != -1) {
				if (this.client.getRenderingJob().isServerBlockJob()) {
					return Error.Type.RENDERER_KILLED_BY_SERVER;
				}
				else if (this.client.getRenderingJob().isUserBlockJob()) {
					return Error.Type.RENDERER_KILLED_BY_USER;
				}
				
				output.write(buffer, 0, len);
				written += len;
				
				if ((written - lastUpd) > 1000000) { // only update the gui every 1MB
					if (size != -1) { // no header for contentlength
						gui_.status(status_, (int) (100.0 * written / size), written);
					}
					lastUpd = written;
				}
			}
			
			LocalDateTime endRequestTime = LocalDateTime.now();
			Duration duration = Duration.between(startRequestTime, endRequestTime);
			this.dlStats.calc(written, ((duration.getSeconds() * 1000) + (duration.getNano() / 1000000)));
			gui_.displayTransferStats(dlStats, ulStats);
			gui_.status(status_, 100, size);
			
			this.log.debug(String.format("File downloaded at %s/s, written %d bytes", new TransferStats(size, duration.getSeconds() + 1).getAverageSessionSpeed(), written));

			this.lastRequestTime = new Date().getTime();
			return Error.Type.OK;
		}
		catch (Exception e) {
			File destFile = new File(destination_);
			if (Files.isWritable(destFile.getParentFile().toPath()) == false) {
				throw new FermeExceptionNoWritePermission();
			}
			else if (destFile.getParentFile().isDirectory() == false) {
				throw new FermeExceptionPathInvalid();
			}
			else if (Utils.noFreeSpaceOnDisk(destFile.getParent(), log)) {
				throw new FermeExceptionNoSpaceLeftOnDevice();
			}
			
			StringWriter sw = new StringWriter();
			e.printStackTrace(new PrintWriter(sw));
			this.log.error("Server::HTTPGetFile Exception " + e + " stacktrace " + sw.toString());
		}
		finally {
			try {
				if (output != null) {
					output.flush();
					output.close();
				}
				
				File downloadedFile = new File(destination_ + ".partial");
				
				if (downloadedFile.exists()) {
					// Rename file (or directory)
					boolean success = downloadedFile.renameTo(new File(destination_));
					
					if (!success) {
						this.log.debug(String.format("Server::HTTPGetFile Error trying to rename the downloaded file to final name (%s)", destination_));
					}
				}

				if (is != null) {
					is.close();
				}
			}
			catch (Exception e) {
				this.log.debug(String.format("Server::HTTPGetFile Error trying to close the open streams (%s)", e.getMessage()));
			}
		}
		
		this.log.debug(String.format("Server::HTTPGetFile(%s) did fail", url_));
		return Error.Type.DOWNLOAD_FILE;
	}
	
	public ServerCode HTTPSendFile(String surl, String file1, int checkpoint, Gui gui) {
		this.log.debug(checkpoint, "Server::HTTPSendFile(" + surl + "," + file1 + ")");
		
		try {
			String fileMimeType = Utils.findMimeType(file1);
			File fileHandler    = new File(file1);

			MediaType MEDIA_TYPE = MediaType.parse(fileMimeType); // e.g. "image/png"
			
			RequestBody uploadContent = new MultipartBody.Builder().setType(MultipartBody.FORM)
				.addFormDataPart("file", fileHandler.getName(), RequestBody.create(fileHandler, MEDIA_TYPE)).build();
			
			Request request = new Request.Builder().addHeader("User-Agent", HTTP_USER_AGENT).url(surl).post(uploadContent).build();
			
			LocalDateTime startRequestTime = LocalDateTime.now();
			
			Call call = httpClient.newCall(request);
			Response response = call.execute();
			
			LocalDateTime endRequestTime = LocalDateTime.now();
			Duration duration = Duration.between(startRequestTime, endRequestTime);
			
			this.ulStats.calc(fileHandler.length(), ((duration.getSeconds() * 1000) + (duration.getNano() / 1000000)));
			gui.displayTransferStats(dlStats, ulStats);
			
			this.log.debug(String.format("File uploaded at %s/s, uploaded %d bytes", new TransferStats(fileHandler.length(), duration.getSeconds() + 1).getAverageSessionSpeed(), fileHandler.length()));
			
			int r = response.code();
			String contentType = response.body().contentType().toString();
			
			if (r == HttpURLConnection.HTTP_OK && contentType.startsWith("text/xml")) {
				try {
					String in = response.body().string();
					JobValidation jobValidation = new Persister().read(JobValidation.class, in);
					
					this.lastRequestTime = new Date().getTime();
					
					ServerCode serverCode = ServerCode.fromInt(jobValidation.getStatus());
					if (serverCode != ServerCode.OK) {
						this.log.error(checkpoint, "Server::HTTPSendFile wrong status (is " + serverCode + ")");
						return serverCode;
					}
				}
				catch (Exception e) { // for the .read
					e.printStackTrace();
				}
				
				return ServerCode.OK;
			}
			else if (r == HttpURLConnection.HTTP_OK && contentType.startsWith("text/html")) {
				return ServerCode.ERROR_BAD_RESPONSE;
			}
			// We don't check all the HTTP 4xx but the 413 in particular, we can always find a huge image larger than whatever configuration we have in the
			// server and it's worth to send the error back to the server if this happen
			else if (r == HttpURLConnection.HTTP_ENTITY_TOO_LARGE) {
				this.log.error(response.body().string());
				return ServerCode.JOB_VALIDATION_IMAGE_TOO_LARGE;
			}
			else if (r == HttpURLConnection.HTTP_INTERNAL_ERROR) {
				return ServerCode.ERROR_BAD_RESPONSE;
			}
			else {
				this.log.error(String.format("Server::HTTPSendFile Unknown response received from server: %s", response.body().string()));
			}
			
			return ServerCode.UNKNOWN;
		}
		catch (ConnectException e) {
			StringWriter sw = new StringWriter();
			PrintWriter pw = new PrintWriter(sw);
			e.printStackTrace(pw);
			this.log.error(checkpoint, String.format("Server::HTTPSendFile Error in upload process. Exception %s stacktrace ", e.getMessage()) + sw.toString());
			return ServerCode.SERVER_CONNECTION_FAILED;
		}
		catch (IOException e) {
			StringWriter sw = new StringWriter();
			PrintWriter pw = new PrintWriter(sw);
			e.printStackTrace(pw);
			this.log.error(checkpoint, String.format("Server::HTTPSendFile Error in upload process. Exception %s stacktrace ", e.getMessage()) + sw.toString());
			return ServerCode.UNKNOWN;
		}
		catch (OutOfMemoryError e) {
			StringWriter sw = new StringWriter();
			PrintWriter pw = new PrintWriter(sw);
			e.printStackTrace(pw);
			this.log.error(checkpoint, "Server::HTTPSendFile, OutOfMemoryError " + e + " stacktrace " + sw.toString());
			return ServerCode.JOB_VALIDATION_ERROR_UPLOAD_FAILED;
		}
		catch (Exception e) {
			StringWriter sw = new StringWriter();
			PrintWriter pw = new PrintWriter(sw);
			e.printStackTrace(pw);
			this.log.error(checkpoint, "Server::HTTPSendFile, Exception " + e + " stacktrace " + sw.toString());
			return ServerCode.UNKNOWN;
		}
	}
	
	private String generateXMLForMD5cache() {
		List<FileMD5> md5s = new ArrayList<>();
		for (File local_file : this.user_config.getLocalCacheFiles()) {
			try {
				String extension = local_file.getName().substring(local_file.getName().lastIndexOf('.')).toLowerCase();
				String name = local_file.getName().substring(0, local_file.getName().length() - 1 * extension.length());
				if (extension.equals(".zip")) {
					// node_file.setAttribute("md5", name);
					FileMD5 fileMD5 = new FileMD5();
					fileMD5.setMd5(name);
					
					md5s.add(fileMD5);
				}
			}
			catch (StringIndexOutOfBoundsException e) { // because the file does not have an . its path
			}
		}
		
		CacheFileMD5 cache = new CacheFileMD5();
		cache.setMd5s(md5s);
		
		final Persister persister = new Persister();
		try (StringWriter writer = new StringWriter()) {
			persister.write(cache, writer);
			return writer.toString();
		}
		catch (final Exception e) {
			log.debug("Failed to dump md5s " + e);
			return "";
		}
	}
	
	private void handleFileMD5DeleteDocument(List<FileMD5> fileMD5s) {
		if (fileMD5s != null && fileMD5s.isEmpty() == false) {
			for (FileMD5 fileMD5 : fileMD5s) {
				if ("delete".equals(fileMD5.getAction()) && fileMD5.getMd5() != null && fileMD5.getMd5().isEmpty() == false) {
					String path = this.user_config.getWorkingDirectory().getAbsolutePath() + File.separatorChar + fileMD5.getMd5();
					this.log.debug("Server::handleFileMD5DeleteDocument delete old file " + path);
					File file_to_delete = new File(path + ".zip");
					file_to_delete.delete();
					Utils.delete(new File(path));
					
					// If we are using a shared downloads directory, then delete the file from the shared downloads directory as well :)
					if (this.user_config.getSharedDownloadsDirectory() != null) {
						String commonCacheFile = this.user_config.getSharedDownloadsDirectory().getAbsolutePath() + File.separatorChar + fileMD5.getMd5();
						this.log.debug("Server::handleFileMD5DeleteDocument delete common file " + commonCacheFile + ".zip");
						file_to_delete = new File(commonCacheFile + ".zip");
						file_to_delete.delete();
					}
				}
			}
		}
	}
	
	public String getPage(String key) {
		if (this.serverConfig != null) {
			RequestEndPoint endpoint = this.serverConfig.getRequestEndPoint(key);
			if (endpoint != null) {
				return this.base_url + endpoint.getPath();
			}
		}
		return "";
	}
	
	private OkHttpClient getOkHttpClient() {
		try {
			OkHttpClient.Builder builder = new OkHttpClient.Builder();
			
			CookieManager cookieManager = new CookieManager();
			cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
			builder.cookieJar(new JavaNetCookieJar(cookieManager));  // Cookie store to maintain the session across calls
			
			builder.connectTimeout(30, TimeUnit.SECONDS);    // Cancel the HTTP Request if the connection to server takes more than 10 seconds
			builder.writeTimeout(60, TimeUnit.SECONDS);      // Cancel the upload if the client cannot send any byte in 60 seconds
			
			// If the user has selected a proxy, then we must increase the download timeout. Reason being the way proxies work. To download a large file (i.e.
			// a 500MB job), the proxy must first download the file to the proxy cache and then the information is sent fast to the SheepIt client. From a client
			// viewpoint, the HTTP connection will make the CONNECT step really fast but then the time until the fist byte is received (the time measured by
			// readTimeout) will be really long (minutes). Without a proxy in the middle, a connection that does receive nothing in 60 seconds might be
			// considered a dead connection.
			if (this.user_config.getProxy() != null) {
				builder.readTimeout(10, TimeUnit.MINUTES);   // Proxy enabled - 10 minutes
			}
			else {
				builder.readTimeout(1, TimeUnit.MINUTES);    // No proxy - 60 seconds max
			}
			
			return builder.build();
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
}
