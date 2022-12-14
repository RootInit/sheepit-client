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
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.Observable;
import java.util.Observer;
import java.util.Optional;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import com.sheepit.client.Error.ServerCode;
import com.sheepit.client.Error.Type;
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
import com.sheepit.client.exception.FermeExceptionSessionDisabledDenoisingNotSupported;
import com.sheepit.client.exception.FermeServerDown;
import com.sheepit.client.hardware.cpu.CPU;
import com.sheepit.client.hardware.hwid.HWIdentifier;
import com.sheepit.client.os.OS;

import lombok.AllArgsConstructor;
import lombok.Data;
import okhttp3.HttpUrl;

@Data public class Client {
	public static final int MIN_JOB_ID = 20; //to distinguish between actual jobs and test frames
	private static final Locale LOCALE = Locale.ENGLISH;
	private Gui gui;
	private Server server;
	private Configuration configuration;
	private Log log;
	private Job renderingJob;
	private Job previousJob;
	private BlockingQueue<QueuedJob> jobsToValidate;
	private boolean isValidatingJob;
	private long startTime;
	private boolean sessionStarted;
	
	private boolean disableErrorSending;
	private boolean running;
	private boolean awaitingStop;
	private boolean suspended;
	private boolean shuttingdown;
	
	private int maxDownloadFileAttempts = 5;
	
	private int uploadQueueSize;
	private long uploadQueueVolume;
	private int noJobRetryIter;
	
	public Client(Gui gui_, Configuration configuration, String url_) {
		this.configuration = configuration;
		this.server = new Server(url_, this.configuration, this);
		this.log = Log.getInstance(this.configuration);
		this.gui = gui_;
		this.renderingJob = null;
		this.previousJob = null;
		this.jobsToValidate = new ArrayBlockingQueue<QueuedJob>(5);
		this.isValidatingJob = false;
		
		this.disableErrorSending = false;
		this.running = false;
		this.awaitingStop = false;
		this.suspended = false;
		this.shuttingdown = false;
		
		this.uploadQueueSize = 0;
		this.uploadQueueVolume = 0;
		this.noJobRetryIter = 0;
		
		this.sessionStarted = false;
	}
	
	public String toString() {
		return String.format("Client (configuration %s, server %s)", this.configuration, this.server);
	}
	
	public int run() {
		if (this.configuration.checkOSisSupported() == false) {
			this.gui.error(Error.humanString(Error.Type.OS_NOT_SUPPORTED));
			return -3;
		}
		
		if (this.configuration.checkCPUisSupported() == false) {
			this.gui.error(Error.humanString(Error.Type.CPU_NOT_SUPPORTED));
			return -4;
		}
		
		this.running = true;
		
		int step;
		try {
			step = this.log.newCheckPoint();
			this.gui.status("Starting");
			
			Error.Type ret;
			ret = this.server.getConfiguration();
			
			if (ret != Error.Type.OK) {
				this.gui.error(Error.humanString(ret));
				if (ret != Error.Type.AUTHENTICATION_FAILED) {
					Log.printCheckPoint(step);
				}
				return -1;
			}
			
			// If the user has selected to shutdown the computer at any specific time, set a scheduled task
			if (configuration.getShutdownTime() > 0) {
				new Timer().schedule(new TimerTask() {
					@Override public void run() {
						shuttingdown = true;
						log.debug("Initiating the computer's shutting down process");
						
						if (configuration.getShutdownMode().equals("wait")) {
							// Soft stop. Complete current render (if any), finish uploading frames and then shutdown the computer
							askForStop();
						}
						else {
							// Soft stop. Interrupt the current render, abort pending uploads, close the client and shutdown the computer
							stop();
						}
					}
				}, this.configuration.getShutdownTime());
			}
			
			//send "error" log containing config
			step = log.newCheckPoint();
			this.log.info("HWID: " + new HWIdentifier(log).getHardwareHash());
			this.log.info("OS: " + OS.getOS().getVersion() + " " + System.getProperty("os.arch"));
			this.log.info(configuration.toString());
			sendError(step, null, Type.OK);
			
			// Check integrity of all files in the working directories
			this.configuration.cleanWorkingDirectory();

			this.startTime = new Date().getTime();
			this.server.start(); // for staying alive
			
			// create a thread which will send the frame
			Runnable runnable_sender = new Runnable() {
				public void run() {
					senderLoop();
				}
			};
			Thread thread_sender = new Thread(runnable_sender);
			thread_sender.start();
			
			do {
				while (this.running) {
					this.renderingJob = null;

					synchronized (this) {
						if (this.suspended) {
							this.gui.status("Client paused", true);
						}
						while (this.suspended && !this.shuttingdown) {
							wait();
						}
					}
					
					step = this.log.newCheckPoint();
					try {
						Calendar next_request = this.nextJobRequest();
						if (next_request != null) {
							// wait
							Date now = new Date();
							this.gui.status(String.format("Waiting until %tR before requesting job", next_request));
							long wait = next_request.getTimeInMillis() - now.getTime();
							if (wait < 0) {
								// it means the client has to wait until the next day
								wait += 24 * 3600 * 1000;
							}
							try {
								Thread.sleep(wait);
							}
							catch (InterruptedException e3) {
							
							}
							catch (IllegalArgumentException e3) {
								this.log.error("Client::run sleepA failed " + e3);
							}
						}
						this.gui.status("Requesting Job");
						this.renderingJob = this.server.requestJob();
					}
					catch (FermeExceptionNoRightToRender e) {
						this.gui.error("User does not have enough right to render scene");
						return -2;
					}
					catch (FermeExceptionSessionDisabled e) {
						this.gui.error(Error.humanString(Error.Type.SESSION_DISABLED));
						// should wait forever to actually display the message to the user
						while (shuttingdown == false) {
							try {
								Thread.sleep(1000);
							}
							catch (InterruptedException e1) {
							}
						}
					}
					catch (FermeExceptionSessionDisabledDenoisingNotSupported e) {
						this.gui.error(Error.humanString(Error.Type.DENOISING_NOT_SUPPORTED));
						// should wait forever to actually display the message to the user
						while (shuttingdown == false) {
							try {
								Thread.sleep(1000);
							}
							catch (InterruptedException e1) {
							}
						}
					}
					catch (FermeExceptionNoRendererAvailable e) {
						this.gui.error(Error.humanString(Error.Type.RENDERER_NOT_AVAILABLE));
						// should wait forever to actually display the message to the user
						while (shuttingdown == false) {
							try {
								Thread.sleep(1000);
							}
							catch (InterruptedException e1) {
							}
						}
					}
					catch (FermeExceptionNoSession e) {
						this.log.debug("User has no session and needs to re-authenticate");
						ret = this.server.getConfiguration();
						if (ret != Error.Type.OK) {
							this.renderingJob = null;
						}
						else {
							this.startTime = new Date().getTime(); // reset start session time because the server did it
							try {
								Calendar next_request = this.nextJobRequest();
								if (next_request != null) {
									// wait
									Date now = new Date();
									this.gui.status(String.format("Waiting until %tR before requesting job", next_request));
									long timeToSleep = next_request.getTimeInMillis() - now.getTime();
									try {
										int timeSlept = 0;
										while (timeSlept < timeToSleep && this.running && !this.shuttingdown) {
											Thread.sleep(1000);
											timeSlept += 1000;
										}
									}
									catch (InterruptedException e3) {
									
									}
									catch (IllegalArgumentException e3) {
										this.log.error("Client::run sleepB failed " + e3);
									}
								}

								// if we have broken the wait loop because a stop or shutdown signal, go back to the main loop to exit
								if (!this.running || this.shuttingdown) {
									continue;
								}

								this.gui.status("Requesting Job");
								this.renderingJob = this.server.requestJob();
							}
							catch (FermeException e1) {
								this.renderingJob = null;
							}
						}
					}
					catch (FermeServerDown e) {
						int wait = ThreadLocalRandom.current().nextInt(10, 30 + 1); // max is exclusive
						int time_sleep = 1000 * 60 * wait;
						this.gui.status(String.format("Cannot connect to the server. Please check your connectivity. Will try again at %tR",
								new Date(new Date().getTime() + time_sleep)));
						try {
							int timeSlept = 0;
							while (timeSlept < time_sleep && this.running && !this.shuttingdown) {
								Thread.sleep(1000);
								timeSlept += 1000;
							}
						}
						catch (InterruptedException e1) {
							return -3;
						}
						this.log.removeCheckPoint(step);
						continue; // go back to ask job
					}
					catch (FermeExceptionServerOverloaded e) {
						int wait = ThreadLocalRandom.current().nextInt(10, 30 + 1); // max is exclusive
						int time_sleep = 1000 * 60 * wait;
						this.gui.status(String.format("The server is overloaded and cannot allocate a job. Will try again at %tR",
								new Date(new Date().getTime() + time_sleep)));
						try {
							int timeSlept = 0;
							while (timeSlept < time_sleep && this.running && !this.shuttingdown) {
								Thread.sleep(1000);
								timeSlept += 1000;
							}
						}
						catch (InterruptedException e1) {
							return -3;
						}
						this.log.removeCheckPoint(step);
						continue; // go back to ask job
					}
					catch (FermeExceptionServerInMaintenance e) {
						int wait = ThreadLocalRandom.current().nextInt(20, 30 + 1); // max is exclusive
						int time_sleep = 1000 * 60 * wait;
						this.gui.status(String.format("The server is under maintenance and cannot allocate a job. Will try again at %tR",
								new Date(new Date().getTime() + time_sleep)));
						try {
							int timeSlept = 0;
							while (timeSlept < time_sleep && this.running && !this.shuttingdown) {
								Thread.sleep(1000);
								timeSlept += 1000;
							}
						}
						catch (InterruptedException e1) {
							return -3;
						}
						this.log.removeCheckPoint(step);
						continue; // go back to ask job
					}
					catch (FermeExceptionBadResponseFromServer e) {
						int wait = ThreadLocalRandom.current().nextInt(15, 30 + 1); // max is exclusive
						int time_sleep = 1000 * 60 * wait;
						this.gui.status(String.format("Bad answer from the server. Will try again at %tR", new Date(new Date().getTime() + time_sleep)));
						try {
							int timeSlept = 0;
							while (timeSlept < time_sleep && this.running && !this.shuttingdown) {
								Thread.sleep(1000);
								timeSlept += 1000;
							}
						}
						catch (InterruptedException e1) {
							return -3;
						}
						this.log.removeCheckPoint(step);
						continue; // go back to ask job
					}
					catch (FermeException e) {
						this.gui.error("Client::run exception requestJob (1) " + e.getMessage());
						StringWriter sw = new StringWriter();
						PrintWriter pw = new PrintWriter(sw);
						e.printStackTrace(pw);
						this.log.debug("Client::run exception " + e + " stacktrace: " + sw.toString());
						this.sendError(step);
						this.log.removeCheckPoint(step);
						continue;
					}
					
					if (this.renderingJob == null) { // no job
						int[] retrySchemeInMilliSeconds = { 300000, 480000, 720000, 900000, 1200000 };    // 5, 8, 12, 15 and 20 minutes
						
						int time_sleep = retrySchemeInMilliSeconds[(this.noJobRetryIter < retrySchemeInMilliSeconds.length) ?
								this.noJobRetryIter++ :
								(retrySchemeInMilliSeconds.length - 1)];
						this.gui.status(String.format("No job available. Will try again at %tR", new Date(new Date().getTime() + time_sleep)));
						int time_slept = 0;
						while (time_slept < time_sleep && this.running && this.shuttingdown == false) {
							try {
								Thread.sleep(250);
							}
							catch (InterruptedException e) {
								return -3;
							}
							time_slept += 250;
						}
						this.log.removeCheckPoint(step);
						continue; // go back to ask job
					}
					
					this.log.debug("Got work to do id: " + this.renderingJob.getId() + " frame: " + this.renderingJob.getFrameNumber());
					
					// As the server allocated a new job to this client, reset the no_job waiting algorithm
					this.noJobRetryIter = 0;
					
					ret = this.work(this.renderingJob);
					if (ret == Error.Type.NO_SPACE_LEFT_ON_DEVICE || ret == Error.Type.PATH_INVALID || ret == Error.Type.NO_WRITE_PERMISSION ) {
						Job frame_to_reset = this.renderingJob; // copy it because the sendError will take ~5min to execute
						this.renderingJob = null;
						this.gui.error(Error.humanString(ret));
						this.sendError(step, frame_to_reset, ret);
						this.log.removeCheckPoint(step);
						return -50;
					}
					
					if (ret != Error.Type.OK) {
						Job currentJob = this.renderingJob; // copy it because the sendError will take ~5min to execute
						this.renderingJob = null;
						this.gui.error(Error.humanString(ret));
						this.sendError(step, currentJob, ret);
						this.log.removeCheckPoint(step);
						
						// Initial test frames always have the Job ID below 20. If we have any error while trying to render the initial frame just
						// halt the execution
						if (Integer.parseInt(currentJob.getId()) < MIN_JOB_ID) {
							// Add the proper explanation to the existing error message and keep the client waiting forever to ensure the user sees the error
							this.gui.error(Error.humanString(ret) + " The error happened during the test frame render. Restart the client and try again.");
							while (shuttingdown == false) {
								try {
									Thread.sleep(1000);
								}
								catch (InterruptedException e1) {
								}
							}
							break;	// if the shutdown signal is triggered then exit the while (this.running) loop to initiate the shutdown process
						}
						
						continue;
					}
					
					if (this.renderingJob.isSynchronousUpload()) { // power or compute_method job, need to upload right away
						this.gui.status(String.format("Uploading frame (%.2fMB)", (this.renderingJob.getOutputImageSize() / 1024.0 / 1024.0)));
						
						ret = confirmJob(this.renderingJob, step);
						if (ret != Error.Type.OK) {
							gui.error("Client::run problem with confirmJob (returned " + ret + ")");
							sendError(step, this.renderingJob, Error.Type.VALIDATION_FAILED);
						}
					}
					else {
						this.gui.status(String.format("Queuing frame for upload (%.2fMB)", (this.renderingJob.getOutputImageSize() / 1024.0 / 1024.0)));
						
						this.jobsToValidate.add(new QueuedJob(step, this.renderingJob));
						
						this.uploadQueueSize++;
						this.uploadQueueVolume += this.renderingJob.getOutputImageSize();
						this.gui.displayUploadQueueStats(uploadQueueSize, uploadQueueVolume);
						
						this.renderingJob = null;
					}
					
					if (this.shouldWaitBeforeRender()) {
						this.gui.status("Sending frames. Please wait");
						
						while (this.shouldWaitBeforeRender()) {
							try {
								Thread.sleep(4000); // wait a little bit
							}
							catch (InterruptedException e3) {
							}
						}
					}
					this.log.removeCheckPoint(step);
				}
				
				// If we reach this point is bc the main loop (the one that controls all the workflow) has exited
				// due to user requesting to exit the App and we are just waiting for the upload queue to empty
				// If the user cancels the exit, then this.running will be true and the main loop will take
				// control again
				try {
					Thread.sleep(2300); // wait a little bit
					this.gui.status("Uploading rendered frames before exiting. Please wait");
				}
				catch (InterruptedException e3) {
				}
				
				// This loop will remain valid until all the background uploads have
				// finished (unless the stop() method has been triggered)
			}
			while (this.uploadQueueSize > 0);
		}
		catch (Exception e1) {
			// no exception should be raised in the actual launcher (applet or standalone)
			StringWriter sw = new StringWriter();
			PrintWriter pw = new PrintWriter(sw);
			e1.printStackTrace(pw);
			this.log.debug("Client::run exception(D) " + e1 + " stacktrace: " + sw.toString());
			return -99; // the this.stop will be done after the return of this.run()
		}
		
		if (this.shuttingdown) {
			// Shutdown the computer using the appropriate command for the host OS
			this.log.debug("Shutting down the computer in 1 minute");
			
			OS.getOS().shutdownComputer(1);
		}
		
		this.gui.stop();
		return 0;
	}
	
	public synchronized int stop() {
		this.running = false;
		this.disableErrorSending = true;
		
		if (this.renderingJob != null) {
			this.gui.status("Stopping");
			
			if (this.renderingJob.getProcessRender().getProcess() != null) {
				this.renderingJob.setAskForRendererKill(true);
				OS.getOS().kill(this.renderingJob.getProcessRender().getProcess());
			}
		}

		this.configuration.removeWorkingDirectory();
		
		if (this.server == null) {
			return 0;
		}
		
		if (this.server.getPage("logout").isEmpty() == false) {
			this.gui.status("Disconnecting from SheepIt server");
			
			try {
				this.server.HTTPRequest(this.server.getPage("logout"));
			}
			catch (IOException e) {
				// nothing to do: if the logout failed that's ok
			}
		}
		this.server.interrupt();
		try {
			this.server.join();
		}
		catch (InterruptedException e) {
		}
		
		this.server = null;
		
		return 0;
	}
	
	public void suspend() {
		suspended = true;
		this.gui.status("Client will pause when the current job finishes", true);
	}
	
	public synchronized void resume() {
		suspended = false;
		notify();
	}
	
	public void askForStop() {
		this.log.debug("Client::askForStop");
		this.running = false;
		this.awaitingStop = true;
	}
	
	public void cancelStop() {
		this.log.debug("Client::cancelStop");
		this.running = true;
		this.awaitingStop = false;
	}
	
	public int senderLoop() {
		int step = -1;
		Error.Type ret = null;
		while (true) {
			QueuedJob queuedJob = null;
			try {
				queuedJob = jobsToValidate.take();
				step = queuedJob.checkpoint;	// retrieve the checkpoint attached to the job
				
				this.log.debug(step, "will validate " + queuedJob.job);
				
				ret = confirmJob(queuedJob.job, step);
				if (ret != Error.Type.OK) {
					this.gui.error(Error.humanString(ret));
					this.log.debug(step, "Client::senderLoop confirm failed, ret: " + ret);
				}
			}
			catch (InterruptedException e) {
				this.log.error(step, "Client::senderLoop Exception " + e.getMessage());
			}
			finally {
				if (ret != Error.Type.OK) {
					if (queuedJob.job != null) {
						sendError(step, queuedJob.job, ret);
					}
					else {
						sendError(step);
					}
				}
				
				// Remove the checkpoint information
				log.removeCheckPoint(step);
				
				this.uploadQueueSize--;
				if (queuedJob.job != null) {
					this.uploadQueueVolume -= queuedJob.job.getOutputImageSize();
				}
				
				this.gui.displayUploadQueueStats(this.uploadQueueSize, this.uploadQueueVolume);
			}
		}
	}
	
	protected void sendError(int step_) {
		this.sendError(step_, null, null);
	}
	
	protected void sendError(int step_, Job job_to_reset_, Error.Type error) {
		if (this.disableErrorSending) {
			this.log.debug("Error sending is disabled, do not send log");
			return;
		}
		
		this.log.debug("Sending error to server (type: " + error + ")");
		try {
			File temp_file = File.createTempFile("farm_", ".txt");
			temp_file.createNewFile();
			temp_file.deleteOnExit();
			FileOutputStream writer = new FileOutputStream(temp_file);
			
			// Create a header with the information summarised for easier admin error analysis
			Configuration conf = this.configuration;
			CPU cpu = OS.getOS().getCPU();
			
			StringBuilder logHeader = new StringBuilder()
				.append("====================================================================================================\n")
				.append(String.format("%s  /  %s  /  %s  /  SheepIt v%s\n", conf.getLogin(), conf.getHostname(), OS.getOS().name(), Configuration.jarVersion))
				.append(String.format("%s  x%d  %.1f GB RAM\n", cpu.name(), conf.getNbCores(), conf.getMaxAllowedMemory() / 1024.0 / 1024.0));
			
			if (conf.getComputeMethod() == Configuration.ComputeType.GPU || conf.getComputeMethod() == Configuration.ComputeType.CPU_GPU) {
				logHeader.append(String.format("%s   %s   %.1f GB VRAM\n", conf.getGPUDevice().getId(), conf.getGPUDevice().getModel(),
					conf.getGPUDevice().getMemory() / 1024.0 / 1024.0 / 1024.0));
			}
			
			logHeader.append("====================================================================================================\n");
			if (job_to_reset_ != null) {
				logHeader.append(String.format("Project ::: %s\n", job_to_reset_.getName()))
					.append(String.format("Project id: %s  frame: %s\n", job_to_reset_.getId(), job_to_reset_.getFrameNumber())).append(String.format("blender ::: %s\n\n", job_to_reset_.getBlenderLongVersion())).append(String.format("ERROR Type :: %s\n", error));
			}
			else {
				logHeader.append("Project ::: No project allocated.\n")
						.append(String.format("ERROR Type :: %s\n", (error != null ? error : "N/A")));
			}
			logHeader.append("====================================================================================================\n\n");
			
			// Insert the info at the beginning of the error log
			writer.write(logHeader.toString().getBytes());
			
			Optional<ArrayList<String>> logs = this.log.getForCheckPoint(step_);
			if (logs.isPresent()) {
				for (String line : logs.get()) {
					writer.write(line.getBytes());
					writer.write('\n');
				}
			}
			
			writer.close();
			HttpUrl.Builder remoteURL = HttpUrl.parse(this.server.getPage("error")).newBuilder();
			remoteURL.addQueryParameter("type", error == null ? "" : Integer.toString(error.getValue()));
			if (job_to_reset_ != null) {
				remoteURL.addQueryParameter("frame", job_to_reset_.getFrameNumber());
				remoteURL.addQueryParameter("job", job_to_reset_.getId());
				remoteURL.addQueryParameter("render_time", Integer.toString(job_to_reset_.getProcessRender().getRenderDuration()));
				remoteURL.addQueryParameter("memoryused", Long.toString(job_to_reset_.getProcessRender().getPeakMemoryUsed()));
			}
			this.server.HTTPSendFile(remoteURL.build().toString(), temp_file.getAbsolutePath(), step_, this.gui);
			temp_file.delete();
		}
		catch (Exception e) {
			StringWriter sw = new StringWriter();
			PrintWriter pw = new PrintWriter(sw);
			e.printStackTrace(pw);
			this.log.debug("Client::sendError Exception " + e + " stacktrace: " + sw.toString());
			// no exception should be raised to actual launcher (applet or standalone)
		}
		
		if (error != null && (error == Error.Type.RENDERER_CRASHED || error == Error.Type.RENDERER_KILLED_BY_USER || error == Type.OK
				|| error == Type.RENDERER_KILLED_BY_USER_OVER_TIME || error == Error.Type.RENDERER_KILLED_BY_SERVER || error == Type.MISSING_SCENE)) {
			// do nothing, we can ask for a job right away
		}
		else {
			try {
				Thread.sleep(300000); // sleeping for 5min
			}
			catch (InterruptedException e) {
			}
		}
	}
	
	/**
	 * @return the date of the next request, or null if there is not delay (null <=> now)
	 */
	public Calendar nextJobRequest() {
		if (this.configuration.getRequestTime() == null) {
			return null;
		}
		else {
			Calendar next = null;
			Calendar now = Calendar.getInstance();
			for (Pair<Calendar, Calendar> interval : this.configuration.getRequestTime()) {
				Calendar start = (Calendar) now.clone();
				Calendar end = (Calendar) now.clone();
				start.set(Calendar.SECOND, 00);
				start.set(Calendar.MINUTE, interval.first.get(Calendar.MINUTE));
				start.set(Calendar.HOUR_OF_DAY, interval.first.get(Calendar.HOUR_OF_DAY));
				
				end.set(Calendar.SECOND, 59);
				end.set(Calendar.MINUTE, interval.second.get(Calendar.MINUTE));
				end.set(Calendar.HOUR_OF_DAY, interval.second.get(Calendar.HOUR_OF_DAY));
				
				if (start.before(now) && now.before(end)) {
					return null;
				}
				if (next == null || (start.before(next) && start.after(now))) {
					next = start;
				}
			}
			
			return next;
		}
	}
	
	public Error.Type work(final Job ajob) {
		Error.Type downloadRet;
		
		gui.setRenderingProjectName(ajob.getName());
		
		try {
			downloadRet = this.downloadExecutable(ajob);
			if (downloadRet != Error.Type.OK) {
				gui.setRenderingProjectName("");
				this.log.error("Client::work problem with downloadExecutable (ret " + downloadRet + ")");
				return downloadRet;
			}
			
			downloadRet = this.downloadSceneFile(ajob);
			if (downloadRet != Error.Type.OK) {
				gui.setRenderingProjectName("");
				this.log.error("Client::work problem with downloadSceneFile (ret " + downloadRet + ")");
				return downloadRet;
			}
			
			int ret = this.prepareWorkingDirectory(ajob); // decompress renderer and scene archives
			if (ret != 0) {
				gui.setRenderingProjectName("");
				this.log.error("Client::work problem with this.prepareWorkingDirectory (ret " + ret + ")");
				return Error.Type.CAN_NOT_CREATE_DIRECTORY;
			}
		}
		catch (FermeException e) {
			gui.setRenderingProjectName("");
			if (e instanceof FermeExceptionNoSpaceLeftOnDevice) {
				return Error.Type.NO_SPACE_LEFT_ON_DEVICE;
			}
			else if (e instanceof FermeExceptionPathInvalid) {
				return Error.Type.PATH_INVALID;
			}
			else if (e instanceof FermeExceptionNoWritePermission) {
				return Error.Type.NO_WRITE_PERMISSION;
			}
			else {
				return Error.Type.UNKNOWN;
			}
		}
		
		final File scene_file = new File(ajob.getScenePath());
		File renderer_file = new File(ajob.getRendererPath());
		
		if (scene_file.exists() == false) {
			gui.setRenderingProjectName("");
			this.log.error("Client::work job preparation failed (scene file '" + scene_file.getAbsolutePath()
					+ "' does not exist), cleaning directory in hope to recover");
			this.configuration.cleanWorkingDirectory();
			return Error.Type.MISSING_SCENE;
		}
		
		if (renderer_file.exists() == false) {
			gui.setRenderingProjectName("");
			this.log.error("Client::work job preparation failed (renderer file '" + renderer_file.getAbsolutePath()
					+ "' does not exist), cleaning directory in hope to recover");
			this.configuration.cleanWorkingDirectory();
			return Error.Type.MISSING_RENDERER;
		}
		
		Observer removeSceneDirectoryOnceRenderHasStartedObserver = new Observer() {
			@Override public void update(Observable observable, Object o) {
				// only remove the .blend since it's most important data
				// and it's the only file we are sure will not be needed anymore
				// scene_file.delete();
				Utils.move(scene_file, configuration.getArchiveDir().getPath() + File.separator + ajob.getSceneMD5());

			}
		};
		
		Error.Type err = ajob.render(removeSceneDirectoryOnceRenderHasStartedObserver);
		gui.setRenderingProjectName("");
		gui.setRemainingTime("");
		gui.setRenderingTime("");
		gui.setComputeMethod("");
		if (err != Error.Type.OK) {
			this.log.error("Client::work problem with runRenderer (ret " + err + ")");
			if (err == Error.Type.RENDERER_CRASHED_PYTHON_ERROR) {
				this.log.error("Client::work failed with python error, cleaning directory in hope to recover");
				this.configuration.cleanWorkingDirectory();
			}
			return err;
		}
		
		removeSceneDirectory(ajob);
		
		return Error.Type.OK;
	}
	
	protected Error.Type downloadSceneFile(Job ajob_) throws FermeException {
		return this.downloadFile(ajob_, ajob_.getRequiredSceneArchivePath(), ajob_.getSceneMD5(),
				String.format(LOCALE, "%s?type=job&job=%s", this.server.getPage("download-archive"), ajob_.getId()), "project");
	}
	
	protected Error.Type downloadExecutable(Job ajob) throws FermeException {
		return this.downloadFile(ajob, ajob.getRequiredRendererArchivePath(), ajob.getRendererMD5(),
				String.format(LOCALE, "%s?type=binary&job=%s", this.server.getPage("download-archive"), ajob.getId()), "renderer");
	}
	
	private Error.Type downloadFile(Job ajob, String local_path, String md5_server, String url, String download_type) throws FermeException {
		File local_path_file = new File(local_path);
		String update_ui = "Downloading " + download_type;
		
		int remaining = 1800000; // 30 minutes max timeout
		
		try {
			// If the client is using a shared cache then introduce some random delay to minimise race conditions on the partial file creation on multiple
			// instances of a client (when started with a script or rendering a recently downloaded scene)
			if (configuration.getSharedDownloadsDirectory() != null) {
				Thread.sleep((new Random().nextInt(9) + 1) * 1000);
			}
			
			// For a maximum of 30 minutes
			do {
				// if the binary or scene already exists in the cache
				if (local_path_file.exists()) {
					this.gui.status("Reusing cached " + download_type);
					return Type.OK;
				}
				// if the binary or scene is being downloaded by another client
				else if (new File(local_path + ".partial").exists()) {
					// Wait and check every second for file download completion but only update the GUI every 10 seconds to minimise CPU load
					if (remaining % 10000 == 0) {
						this.gui.status(String.format("Another client is downloading the %s. Cancel in %dmin %ds",
							download_type,
							TimeUnit.MILLISECONDS.toMinutes(remaining),
							TimeUnit.MILLISECONDS.toSeconds(remaining) - TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(remaining))
						));
					}
				}
				else {
					// The file doesn't yet existing not is being downloaded by another client, so immediately create the file with zero bytes to allow early
					// detection by other concurrent clients and start downloading process
					try {
						File file = new File(local_path + ".partial");
						file.createNewFile();
						file.deleteOnExit();    // if the client crashes, the temporary file will be removed
					} catch (IOException e) {
						StringWriter sw = new StringWriter();
						e.printStackTrace(new PrintWriter(sw));
						this.log.error("Client::DownloadFile Unable to create .partial temp file for binary/scene " + local_path);
						this.log.error("Client::DownloadFile Exception " + e + " stacktrace " + sw.toString());
					}
					
					break;
				}
				
				// Reduce 1 second the waiting time
				Thread.sleep(1000);
				remaining -= 1000;
			} while (remaining > 0);
		}
		catch (InterruptedException e) {
			log.debug("Error in the thread wait. Exception " + e.getMessage());
		}
		finally {
			// If we have reached the timeout (30 minutes trying to download the client) delete the partial downloaded copy and try to download again
			if (remaining <= 0) {
				log.debug("ERROR while waiting for download to finish in another client. Deleting the partial file and downloading a fresh copy now!.");
				new File(local_path + ".partial").delete();
			}
		}
		
		this.gui.status(String.format("Downloading %s", download_type));
		
		// must download the archive
		Error.Type ret = this.server.HTTPGetFile(url, local_path, this.gui, update_ui);
		
		if (ret == Type.RENDERER_KILLED_BY_SERVER || ret == Type.RENDERER_KILLED_BY_USER_OVER_TIME || ret == Type.RENDERER_KILLED_BY_USER) {
			return ret;
		}
		
		// Try to check the download file even if a download error has occurred (MD5 file check will delete the file if partially downloaded)
		boolean md5_check = this.checkFile(ajob, local_path, md5_server);
		int attempts = 1;
		
		while ((ret != Error.Type.OK || md5_check == false) && attempts < this.maxDownloadFileAttempts) {
			if (ret != Error.Type.OK) {
				this.gui.error(String.format("Unable to download %s (error %s). Retrying now", download_type, ret));
				this.log.debug("Client::downloadFile problem with Server.HTTPGetFile (return: " + ret + ") removing local file (path: " + local_path + ")");
			}
			else if (md5_check == false) {
				this.gui.error(String.format("Verification of downloaded %s has failed. Retrying now", download_type));
				this.log.debug("Client::downloadFile problem with Client::checkFile mismatch on md5, removing local file (path: " + local_path + ")");
			}
			local_path_file.delete();
			
			this.log.debug("Client::downloadFile failed, let's try again (" + (attempts + 1) + "/" + this.maxDownloadFileAttempts + ") ...");
			
			ret = this.server.HTTPGetFile(url, local_path, this.gui, update_ui);
			
			md5_check = this.checkFile(ajob, local_path, md5_server);
			attempts++;
			
			if ((ret != Error.Type.OK || md5_check == false) && attempts >= this.maxDownloadFileAttempts) {
				this.log.debug("Client::downloadFile failed after " + this.maxDownloadFileAttempts + " attempts, removing local file (path: " + local_path
						+ "), stopping...");
				local_path_file.delete();
				return Type.DOWNLOAD_FILE;
			}
		}
		
		return Type.OK;
	}
	
	private boolean checkFile(Job ajob, String local_path, String md5_server) {
		File local_path_file = new File(local_path);
		
		if (local_path_file.exists() == false) {
			this.log.error("Client::checkFile cannot check md5 on a nonexistent file (path: " + local_path + ")");
			return false;
		}
		
		String md5_local = Utils.md5(local_path);
		
		if (md5_local.equals(md5_server) == false) {
			this.log.error(
					"Client::checkFile mismatch on md5 local: '" + md5_local + "' server: '" + md5_server + "' (local size: " + new File(local_path).length()
							+ ")");
			return false;
		}
		
		return true;
	}
	
	protected void removeSceneDirectory(Job ajob) {
		//Utils.delete(new File(ajob.getSceneDirectory()));
	}
	
	protected int prepareWorkingDirectory(Job ajob) {
		int ret;
		String bestRendererArchive = ajob.getRequiredRendererArchivePath();
		String renderer_archive = ajob.getRendererArchivePath();
		String renderer_path = ajob.getRendererDirectory();
		File renderer_path_file = new File(renderer_path);
		
		if (!new File(renderer_archive).exists()) {
			this.gui.status("Copying renderer from shared downloads directory");
			
			copySharedArchive(bestRendererArchive, renderer_archive);
		}
		
		if (!renderer_path_file.exists()) {
			// we create the directory
			renderer_path_file.mkdir();
			
			this.gui.status("Extracting renderer");
			
			// unzip the archive
			ret = Utils.unzipFileIntoDirectory(renderer_archive, renderer_path, null, log);
			if (ret != 0) {
				this.log.error(
						"Client::prepareWorkingDirectory, error(1) with Utils.unzipFileIntoDirectory(" + renderer_archive + ", " + renderer_path + ") returned "
								+ ret);
				this.gui.error(String.format("Unable to extract the renderer (error %d)", ret));
				return -1;
			}
			
			try {
				File f = new File(ajob.getRendererPath());
				f.setExecutable(true);
			}
			catch (SecurityException e) {
			}
		}
		
		String bestSceneArchive = ajob.getRequiredSceneArchivePath();
		String scene_archive = ajob.getSceneArchivePath();
		String scene_path = ajob.getSceneDirectory();
		File scene_path_file = new File(scene_path);
		
		if (!new File(scene_archive).exists()) {
			this.gui.status("Copying scene from common directory");
			copySharedArchive(bestSceneArchive, scene_archive);
		}
		
		if (!scene_path_file.exists()) {
			// we create the directory
			scene_path_file.mkdir();
			
			this.gui.status("Extracting project");
			
			// unzip the archive
			ret = Utils.unzipFileIntoDirectory(scene_archive, scene_path, ajob.getPassword(), log);
			if (ret != 0) {
				this.log.error(
						"Client::prepareWorkingDirectory, error(2) with Utils.unzipFileIntoDirectory(" + scene_archive + ", " + scene_path + ") returned "
								+ ret);
				this.gui.error(String.format("Unable to extract the scene (error %d)", ret));
				return -2;
			}
		}
		
		return 0;
	}

	private void copySharedArchive(String existingArchive, String targetArchive) {
		Path existingArchivePath = Paths.get(existingArchive);
		Path targetArchivePath = Paths.get(targetArchive);
		try {
			try {
				Files.createLink(targetArchivePath, existingArchivePath);
				log.debug("Created hardlink from " + targetArchivePath + " to " + existingArchivePath);
			}
			catch (UnsupportedOperationException // underlying file system does not support hard-linking
				| FileSystemException       // cache-dir and shared-zip are on separate file systems, even though hard-linking is supported
				| SecurityException         // user is not allowed to create hard-links
				ignore) {
				// Creating hardlinks might not be supported on some filesystems
				log.debug("Failed to create hardlink, falling back to copying file to " + targetArchivePath);
				Files.copy(existingArchivePath, targetArchivePath, StandardCopyOption.REPLACE_EXISTING);
			}
		}
		catch (IOException e) {
			this.gui.error("Error while copying " + existingArchive + " from shared downloads directory to working dir");
		}
	}

	protected Error.Type confirmJob(Job ajob, int checkpoint) {
		String url_real = String.format(LOCALE, "%s&rendertime=%d&memoryused=%s", ajob.getValidationUrl(), ajob.getProcessRender().getRenderDuration(),
				ajob.getProcessRender().getPeakMemoryUsed());
		this.log.debug(checkpoint, "Client::confirmeJob url " + url_real);
		this.log.debug(checkpoint, "path frame " + ajob.getOutputImagePath());
		
		this.isValidatingJob = true;
		int max_try = 3;
		int timeToSleep = 22000;
		ServerCode ret = ServerCode.UNKNOWN;
		Type confirmJobReturnCode = Error.Type.OK;
		retryLoop:
		for (int nb_try = 0; nb_try < max_try; nb_try++) {
			if (nb_try >= 1) {
				// sleep before retrying
				this.log.debug(checkpoint, "Sleep for " + timeToSleep / 1000 + "s before trying to re-upload the frame");
				try {
					Thread.sleep(timeToSleep);
				}
				catch (InterruptedException e) {
					confirmJobReturnCode = Error.Type.UNKNOWN;
				}
				timeToSleep *= 2;    // exponential backoff
			}

			ret = this.server.HTTPSendFile(url_real, ajob.getOutputImagePath(), checkpoint, this.gui);
			switch (ret) {
				case OK:
					// no issue, exit the loop
					break retryLoop;
				
				case JOB_VALIDATION_ERROR_SESSION_DISABLED:
				case JOB_VALIDATION_ERROR_BROKEN_MACHINE:
					confirmJobReturnCode = Error.Type.SESSION_DISABLED;
					break retryLoop;
				
				case JOB_VALIDATION_ERROR_IMAGE_WRONG_DIMENSION:
					confirmJobReturnCode = Error.Type.IMAGE_WRONG_DIMENSION;
					break retryLoop;
					
				case JOB_VALIDATION_ERROR_MISSING_PARAMETER:
					// no point to retry the request
					confirmJobReturnCode = Error.Type.UNKNOWN;
					break retryLoop;
					
				case JOB_VALIDATION_IMAGE_TOO_LARGE:
					// the client cannot recover from this error (it's server side config) so exit the retry loop
					confirmJobReturnCode = Type.IMAGE_TOO_LARGE;
					break retryLoop;
					
				case SERVER_CONNECTION_FAILED:
					confirmJobReturnCode = Type.NETWORK_ISSUE;
					break;
				
				case ERROR_BAD_RESPONSE:
					// set the error and retry on next loop
					confirmJobReturnCode = Type.ERROR_BAD_UPLOAD_RESPONSE;
					break;
					
				default:
					// do nothing, try to do a request on the next loop
					break;
			}
		}
		
		this.isValidatingJob = false;
		this.previousJob = ajob;
		
		//count frames if they are not test frames and got validated correctly
		if (confirmJobReturnCode == Error.Type.OK && Integer.parseInt(ajob.getId()) >= MIN_JOB_ID) {
			gui.AddFrameRendered();
		}
		
		// we can remove the frame file
		File frame = new File(ajob.getOutputImagePath());
		// frame.delete();
		Utils.move(frame, configuration.getArchiveDir().getPath() + File.separator + ajob.getSceneMD5());
		ajob.setOutputImagePath(null);
		
		return confirmJobReturnCode;
	}
	
	protected boolean shouldWaitBeforeRender() {
		int concurrent_job = this.jobsToValidate.size();
		if (this.isValidatingJob) {
			concurrent_job++;
		}
		return (concurrent_job >= this.configuration.getMaxUploadingJob());
	}
	
	/****************
	 * Inner class that will hold the queued jobs. The constructor accepts two parameters:
	 * @int checkpoint - the checkpoint associated with the job (to add any additional log to the render output)
	 * @Job job - the job to be validated
	 */
	@AllArgsConstructor class QueuedJob {
		final private int checkpoint;
		final private Job job;
	}
}
