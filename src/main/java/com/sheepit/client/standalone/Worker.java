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

package com.sheepit.client.standalone;

import com.sheepit.client.hardware.gpu.hip.HIP;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.kohsuke.args4j.OptionHandlerFilter;

import java.io.File;
import java.net.MalformedURLException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Calendar;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sheepit.client.Client;
import com.sheepit.client.Configuration;
import com.sheepit.client.Configuration.ComputeType;
import com.sheepit.client.Error;
import com.sheepit.client.Gui;
import com.sheepit.client.Log;
import com.sheepit.client.Pair;
import com.sheepit.client.SettingsLoader;
import com.sheepit.client.ShutdownHook;
import com.sheepit.client.Utils;
import com.sheepit.client.hardware.gpu.GPU;
import com.sheepit.client.hardware.gpu.GPUDevice;
import com.sheepit.client.hardware.gpu.nvidia.Nvidia;
import com.sheepit.client.network.Proxy;
import com.sheepit.client.os.OS;

public class Worker {
	@Option(name = SettingsLoader.ARG_SERVER, usage = "Render-farm server, default https://client.sheepit-renderfarm.com", metaVar = "URL", required = false) private String server = "https://client.sheepit-renderfarm.com";
	
	@Option(name = SettingsLoader.ARG_LOGIN, usage = "User's login", metaVar = "LOGIN", required = false) private String login = "";
	
	@Option(name = SettingsLoader.ARG_PASSWORD, usage = "User's password or public key (accessible under the Keys tab of the profile page)", metaVar = "PASSWORD", required = false) private String password = "";
	
	@Option(name = SettingsLoader.ARG_CACHE_DIR, usage = "Cache/Working directory. Caution, everything in it not related to the render-farm will be removed", metaVar = "/tmp/cache", required = false) private String cache_dir = null;
	
	@Option(name = SettingsLoader.ARG_SHARED_ZIP, usage = "Shared directory for downloaded binaries and scenes. Useful when running two or more clients in the same computer/network to download once and render many times. IMPORTANT: This option and value must be identical in ALL clients sharing the directory.", required = false) private String sharedDownloadsDir = null;
	
	@Option(name = SettingsLoader.ARG_GPU, usage = "Name of the GPU used for the render, for example CUDA_0 for Nvidia or OPENCL_0 for AMD/Intel card", metaVar = "CUDA_0", required = false) private String gpu_device = null;
	
	@Option(name = SettingsLoader.ARG_NO_GPU, usage = "Don't detect GPUs", required = false) private boolean no_gpu_detection = false;
	
	@Option(name = SettingsLoader.ARG_COMPUTE_METHOD, usage = "CPU: only use cpu, GPU: only use gpu, CPU_GPU: can use cpu and gpu (not at the same time) if -gpu is not use it will not use the gpu", metaVar = "CPU", required = false) private String method = null;
	
	@Option(name = SettingsLoader.ARG_CORES, usage = "Number of cores/threads to use for the render. The minimum is two cores unless your system only has one", metaVar = "3", required = false) private int nb_cores = -1;
	
	@Option(name = SettingsLoader.ARG_MEMORY, usage = "Maximum memory allow to be used by renderer, number with unit (800M, 2G, ...)", required = false) private String max_ram = null;
	
	@Option(name = SettingsLoader.ARG_RENDERTIME, usage = "Maximum time allow for each frame (in minutes)", required = false) private int max_rendertime = -1;
	
	@Option(name = SettingsLoader.ARG_VERBOSE, usage = "Display log", required = false) private boolean print_log = false;
	
	@Option(name = SettingsLoader.ARG_REQUEST_TIME, usage = "H1:M1-H2:M2,H3:M3-H4:M4 Use the 24h format. For example to request job between 2am-8.30am and 5pm-11pm you should do --request-time 2:00-8:30,17:00-23:00 Caution, it's the requesting job time to get a project, not the working time", metaVar = "2:00-8:30,17:00-23:00", required = false) private String request_time = null;
	
	@Option(name = SettingsLoader.ARG_SHUTDOWN, usage = "Specify when the client will close and the host computer will shut down in a proper way. The time argument can have two different formats: an absolute date and time in the format yyyy-mm-ddThh:mm:ss (24h format) or a relative time in the format +m where m is the number of minutes from now.", metaVar = "DATETIME or +N", required = false) private String shutdown = null;
	
	@Option(name = SettingsLoader.ARG_SHUTDOWN_MODE, usage = "Indicates if the shutdown process waits for the upload queue to finish (wait) or interrupt all the pending tasks immediately (hard). The default shutdown mode is wait.", metaVar = "MODE", required = false) private String shutdownMode = null;
	
	@Option(name = SettingsLoader.ARG_PROXY, usage = "URL of the proxy", metaVar = "http://login:password@host:port", required = false) private String proxy = null;
	
	@Option(name = SettingsLoader.ARG_EXTRAS, usage = "Extras data push on the authentication request", required = false) private String extras = null;
	
	@Option(name = SettingsLoader.ARG_UI, usage = "Specify the user interface to use, default '" + GuiSwing.type + "', available '" + GuiTextOneLine.type + "', '"
			+ GuiText.type + "', '" + GuiSwing.type + "' (graphical)", required = false) private String ui_type = null;
	
	@Option(name = SettingsLoader.ARG_CONFIG, usage = "Specify the configuration file", required = false) private String config_file = null;
	
	@Option(name = SettingsLoader.ARG_VERSION, usage = "Display application version", required = false, handler = VersionParameterHandler.class) private VersionParameterHandler versionHandler;
	
	@Option(name = SettingsLoader.ARG_SHOW_GPU, usage = "Print available GPU devices and exit", required = false, handler = ListGpuParameterHandler.class) private ListGpuParameterHandler listGpuParameterHandler;
	
	@Option(name = SettingsLoader.ARG_NO_SYSTRAY, usage = "Don't use SysTray", required = false) private boolean useSysTray = false;
	
	@Option(name = SettingsLoader.ARG_PRIORITY, usage = "Set render process priority (19 lowest to -19 highest)", required = false) private int priority = 19;
	
	@Option(name = SettingsLoader.ARG_TITLE, usage = "Custom title for the GUI Client", required = false) private String title = "SheepIt Render Farm (Modified)";
	
	@Option(name = SettingsLoader.ARG_THEME, usage = "Specify the theme to use for the graphical client, default 'light', available 'light', 'dark'", required = false) private String theme = null;
	
	@Option(name = SettingsLoader.ARG_HOSTNAME, usage = "Set a custom hostname name (name change will be lost when client is closed)", required = false) private String hostname = null;
	
	@Option(name = SettingsLoader.ARG_HEADLESS, usage = "Mark your client manually as headless to block Eevee projects", required = false) private boolean headless = java.awt.GraphicsEnvironment.isHeadless();
	
	public static void main(String[] args) {
		if (OS.getOS() == null) {
			System.err.println(Error.humanString(Error.Type.OS_NOT_SUPPORTED));
			System.exit(1);
		}
		new Worker().doMain(args);
	}
	
	public void doMain(String[] args) {
		CmdLineParser parser = new CmdLineParser(this);
		try {
			parser.parseArgument(args);
		}
		catch (CmdLineException e) {
			System.err.println(e.getMessage());
			System.err.println("Usage: ");
			parser.printUsage(System.err);
			System.err.println();
			System.err.println("Example: java " + this.getClass().getName() + " " + parser.printExample(OptionHandlerFilter.REQUIRED));
			return;
		}
		
		ComputeType compute_method = null;
		Configuration config = new Configuration(null, login, password);
		config.setPrintLog(print_log);
		config.setUsePriority(priority);
		config.setDetectGPUs(!no_gpu_detection);
		
		if (sharedDownloadsDir != null) {
			File dir = new File(sharedDownloadsDir);
			if (dir.exists() == false && dir.mkdirs()) {
				Log.getInstance(config).debug("created shared-zip directory " + dir);
			}
			else if (dir.exists() == false) {
				System.err.println("ERROR: The shared-zip directory " + dir + " does not exist and cannot be automatically created");
				return;
			}

			if (dir.canWrite() == false) {
				System.err.println("ERROR: The shared-zip directory " + dir + " must be writeable");
				return;
			}
			config.setSharedDownloadsDirectory(dir);
		}
		
		if (cache_dir != null) {
			Pattern cache_dirValidator = Pattern.compile("^(\\/|\\\\|[a-z]:)?[a-z0-9\\/\\\\\\s-_.]+$",Pattern.CASE_INSENSITIVE);
			Matcher cache_dirCandidate = cache_dirValidator.matcher(cache_dir);
			
			if (cache_dirCandidate.find()) {

				File a_dir = new File(cache_dir);
				a_dir.mkdirs();
				if (a_dir.isDirectory() && a_dir.canWrite()) {
					config.setCacheDir(a_dir);
				}
				else {
					System.err.println("ERROR: The entered cache path is either not a directory or is not writable!");
					System.exit(2);
				}

			}
			else {
				System.err.println("ERROR: The entered cache path (-cache-dir parameter) contains invalid characters. Allowed characters are a-z, A-Z, 0-9, /, \\, ., - and _");
				System.exit(2);
			}
		}
		
		// We set a hard limit of 3 concurrent uploads. As the server doesn't allocate more than 3 concurrent jobs to
		// a single session to avoid any client taking too many frames and not validating them, we throttle the uploads.
		// If we don't set this limit, in a computer with slow uploads the server will return a "no job available" when
		// the 4th concurrent job is requested and that will put the client in "wait" mode for some random time. To
		// avoid that situation we set this limit.
		config.setMaxUploadingJob(3);
		
		// Store the SysTray preference from the user. Please note that we must ! the value of the variable because the way args4j works. If the --no-systray
		// parameter is detected, args4j will store (boolean)true in the useSysTray variable but we want to store (boolean)false in the configuration class
		// for further checks.
		config.setUseSysTray(!useSysTray);
		
		config.setHeadless(headless);
		
		if (gpu_device != null) {
			if (gpu_device.startsWith(Nvidia.TYPE) == false && gpu_device.startsWith(HIP.TYPE) == false) {
				System.err.println("ERROR: The entered GPU_ID is invalid. The GPU_ID should look like '" + Nvidia.TYPE + "_#' or '" + HIP.TYPE
						+ "_#'. Please use the proper GPU_ID from the GPU list below\n");
				showGPUList(parser);
			}
			
			GPUDevice gpu = GPU.getGPUDevice(gpu_device);
			if (gpu == null) {
				System.err.println("ERROR: The entered GPU_ID is invalid. Please use the proper GPU_ID from the GPU list below\n");
				showGPUList(parser);
			}
			config.setGPUDevice(gpu);
		}
		
		if (request_time != null) {
			String[] intervals = request_time.split(",");
			if (intervals != null) {
				config.setRequestTime(new LinkedList<Pair<Calendar, Calendar>>());
				
				SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm");
				for (String interval : intervals) {
					String[] times = interval.split("-");
					if (times != null && times.length == 2) {
						Calendar start = Calendar.getInstance();
						Calendar end = Calendar.getInstance();
						
						try {
							start.setTime(timeFormat.parse(times[0]));
							end.setTime(timeFormat.parse(times[1]));
						}
						catch (ParseException e) {
							System.err.println(String.format(
									"ERROR: The entered time slot (-request-time parameter) doesn't seem to be valid. Please check the format is correct [%s]",
									e.getMessage()));
							System.exit(2);
						}
						
						if (start.before(end)) {
							config.getRequestTime().add(new Pair<Calendar, Calendar>(start, end));
						}
						else {
							System.err.println(String.format("ERROR: The start (%s) time must be earlier than the finish (%s) time", times[0], times[1]));
							System.exit(2);
						}
					}
				}
			}
		}
		
		if (nb_cores < -1 || nb_cores == 0) { // -1 is the default
			System.err.println("ERROR: The entered number of CPU cores (-cores parameter) is not valid. Please enter a number greater than zero");
			return;
		}
		else {
			config.setNbCores(nb_cores);
		}
		
		if (max_ram != null) {
			try {
				config.setMaxAllowedMemory(Utils.parseNumber(max_ram) / 1024); // internal value is in KiB
			}
			catch (java.lang.IllegalStateException e) {
				System.err.println(
						String.format("ERROR: The entered value of maximum memory (-memory parameter) doesn't seem to be a valid number [%s]", e.getMessage()));
				return;
			}
		}
		
		if (max_rendertime > 0) {
			config.setMaxRenderTime(max_rendertime * 60);
		}
		
		if (method != null) {
			try {
				compute_method = ComputeType.valueOf(method);
			}
			catch (IllegalArgumentException e) {
				System.err.println(String.format(
						"ERROR: The entered compute method (-compute-method parameter) is not valid. Available values are CPU, GPU or CPU_GPU [%s]",
						e.getMessage()));
				System.exit(2);
			}
		}
		else {
			if (config.getGPUDevice() != null) {
				compute_method = ComputeType.GPU;
			}
		}
		
		if (proxy != null) {
			try {
				Proxy.set(proxy);
			}
			catch (MalformedURLException e) {
				System.err.println(String.format("ERROR: The entered proxy URL (-proxy parameter) doesn't seem to have the right format. Please check it [%s]",
						e.getMessage()));
				System.exit(2);
			}
		}
		
		if (extras != null) {
			config.setExtras(extras);
		}
		
		if (compute_method != null) {
			if (compute_method == ComputeType.CPU && config.getGPUDevice() != null) {
				System.err.println(
					"ERROR: The compute method is set to use CPU only, but a GPU has also been specified. Change the compute method to CPU_GPU or remove the GPU");
				System.exit(2);
			}
			else if (compute_method == ComputeType.CPU_GPU && config.getGPUDevice() == null) {
				System.err.println(
					"ERROR: The compute method is set to use both CPU and GPU, but no GPU has been specified. Change the compute method to CPU or add a GPU (via -gpu parameter)");
				System.exit(2);
			}
			else if (compute_method == ComputeType.GPU && config.getGPUDevice() == null) {
				System.err.println("ERROR: The compute method is set to use GPU only, but not GPU has been specified. Please add a GPU (via -gpu parameter)");
				System.exit(2);
			}
			else if (compute_method == ComputeType.CPU) {
				config.setGPUDevice(null); // remove the GPU
			}
		}
		
		config.setComputeMethod(compute_method);
		
		if (ui_type != null) {
			config.setUIType(ui_type);
		}
		
		if (theme != null) {
			if (!theme.equals("light") && !theme.equals("dark")) {
				System.err.println("ERROR: The entered theme (-theme parameter) doesn't exist. Please choose either 'light' or 'dark'");
				System.exit(2);
			}
			
			config.setTheme(this.theme);
		}
		
		// Shutdown process block
		if (shutdown != null) {
			Pattern absoluteTimePattern = Pattern.compile("^([12]\\d{3}-(0[1-9]|1[0-2])-(0[1-9]|[12]\\d|3[01]))T([01]?[0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]$");
			Pattern relativeTimePattern = Pattern.compile("^\\+([0-9]{2,4})$");
			LocalDateTime shutdownTime = null;
			
			Matcher timeAbsolute = absoluteTimePattern.matcher(shutdown);
			Matcher timeRelative = relativeTimePattern.matcher(shutdown);
			
			if (timeAbsolute.find()) {
				if ((shutdownTime = shutdownTimeParse(shutdown)) != null) {
					long diffInMillies = ChronoUnit.MILLIS.between(LocalDateTime.now(), shutdownTime);
					
					if (diffInMillies < 0) {
						System.err.println(String
							.format("\nERROR: The entered shutdown time (%s) is a date on the past. Shutdown time must be at least 30 minutes from now",
								shutdown));
						System.err.println("Aborting");
						System.exit(2);
					}
					else if (diffInMillies < 10 * 60 * 1000) {        // 10 minutes
						System.err.println(String.format(
							"\nERROR: The specified shutdown time (%s) is expected to happen in less than 10 minutes. Shutdown time must be at least 30 minutes from now",
							shutdown));
						System.err.println("Aborting");
						System.exit(2);
					}
					
					config.setShutdownTime(diffInMillies);
				}
				else {
					System.err.println(String.format(
						"\nERROR: The format of the entered shutdown time (%s) is not correct.\nThe time argument can have two different formats: an absolute date and time in the format yyyy-mm-ddThh:mm:ss (24h format) or a relative time in the format +m where m is the number of minutes from now (min. +10 minutes, max. +9999 minutes)",
						shutdown));
					System.err.println("Aborting");
					System.exit(2);
				}
			}
			else if (timeRelative.find()) {
				int minutesUntilShutdown = Integer.parseInt(timeRelative.group(1));
				config.setShutdownTime(minutesUntilShutdown * 60 * 1000);
				shutdownTime = LocalDateTime.now().plusMinutes(minutesUntilShutdown);
			}
			else {
				System.err.println(String.format(
					"\nERROR: The time especified (%s) is less than 10 minutes or the format is not correct.\nThe time argument can have two different formats: an absolute date and time in the format yyyy-mm-ddThh:mm:ss (24h format) or a relative time in the format +m where m is the number of minutes from now (min. +10 minutes, max. +9999 minutes)",
					shutdown));
				System.err.println("Aborting");
				System.exit(2);
			}
			
			if (shutdownMode != null) {
				if (shutdownMode.toLowerCase().equals("wait") || shutdownMode.toLowerCase().equals("hard")) {
					config.setShutdownMode(shutdownMode.toLowerCase());
				}
				else {
					System.err
						.println(String.format("ERROR: The entered shutdown-mode (%s) is invalid. Please enter wait or hard shutdown mode.", shutdownMode));
					System.err.println("  - Wait: the shutdown process is initiated once the current job and all the queued uploads are finished.");
					System.err
						.println("  - Hard: Then shutdown process is executed immediately. Any ongoing rendering process or upload queues will be cancelled.");
					System.err.println("Aborting");
					System.exit(2);
				}
			}
			else {
				// if no shutdown mode specified, then set "wait" mode by default
				config.setShutdownMode("wait");
			}
			
			System.out.println("==============================================================================");
			if (config.getShutdownMode().equals("wait")) {
				System.out.println(String.format(
					"WARNING!\n\nThe client will stop requesting new jobs at %s.\nTHE EFFECTIVE SHUTDOWN MIGHT OCCUR LATER THAN THE REQUESTED TIME AS THE UPLOAD\nQUEUE MUST BE FULLY UPLOADED BEFORE THE SHUTDOWN PROCESS STARTS.\n\nIf you want to shutdown the computer sharp at the specified time, please\ninclude the '-shutdown-mode hard' parameter in the application call",
					shutdownTime));
			}
			else {
				System.out.println(String.format(
					"WARNING!\n\nThe client will initiate the shutdown process at %s.\nALL RENDERS IN PROGRESS AND UPLOAD QUEUES WILL BE CANCELED.\n\nIf you prefer to shutdown the computer once the pending jobs are completed,\nplease include the '-shutdown-mode wait' parameter in the application call",
					shutdownTime));
			}
			System.out.println("==============================================================================\n");
		}
		else if (shutdown == null && shutdownMode != null) {
			System.err.println(
				"ERROR: The shutdown-mode parameter cannot be entered alone. Please make sure that you also enter a valid shutdown time (using -shutdown parameter)");
			System.err.println("Aborting");
			System.exit(2);
		}
		
		if (config_file != null) {
			if (new File(config_file).exists() == false) {
				System.err.println(
						"ERROR: The entered configuration file (-config parameter) cannot be loaded. Please check that you've entered an existing filename");
				System.exit(2);
			}
			config.setConfigFilePath(config_file);
		}
		
		SettingsLoader settingsLoader = new SettingsLoader(config_file);
		settingsLoader.merge(config, true);
		
		if (args.length > 0) {
			settingsLoader.markLaunchSettings(List.of(args));
		}
		
		Log.getInstance(config).debug("client version " + Configuration.jarVersion);
		
		// Hostname change will overwrite the existing one (default or read from configuration file) but changes will be lost when the client closes
		if (hostname != null) {
			Pattern hostnameValidator = Pattern.compile("[^a-z0-9-_]", Pattern.CASE_INSENSITIVE);
			Matcher hostnameCandidate = hostnameValidator.matcher(hostname);
			
			if (hostnameCandidate.find()) {
				System.err.println(
					"ERROR: The entered hostname (-hostname parameter) contains invalid characters. Allowed hostname characters are a-z, A-Z, 0-9, - and _");
				System.exit(2);
			}
			else {
				config.setHostname(hostname);
			}
		}
		
		Gui gui;
		String type = config.getUIType();
		if (type == null) {
			type = "swing";
		}
		switch (type) {
			case GuiTextOneLine.type:
				if (config.isPrintLog()) {
					System.err.println(
							"ERROR: The oneLine UI and the --verbose parameter cannot be used at the same time. Please either change the ui to text or remove the verbose mode");
					System.exit(2);
				}
				gui = new GuiTextOneLine();
				break;
			case GuiText.type:
				gui = new GuiText();
				break;
			default:
			case GuiSwing.type:
				if (java.awt.GraphicsEnvironment.isHeadless()) {
					System.err.println("ERROR: Your current configuration doesn't support graphical UI.");
					System.err.println("Please use one of the text-based UIs provided (using -ui " + GuiTextOneLine.type + " or -ui " + GuiText.type + ")");
					System.exit(3);
				}
				gui = new GuiSwing(config.isUseSysTray(), title);
				((GuiSwing) gui).setSettingsLoader(settingsLoader);
				break;
		}
		Client cli = new Client(gui, config, server);
		gui.setClient(cli);
		ShutdownHook hook = new ShutdownHook(cli);
		hook.attachShutDownHook();
		
		gui.start();
	}
	
	private void showGPUList(CmdLineParser parser) {
		try {
			parser.parseArgument("--show-gpu");
		}
		catch (CmdLineException e) {
			System.err.println(String.format("ERROR: Unable to parse the provided parameter [%s]", e.getMessage()));
		}
	}
	
	private LocalDateTime shutdownTimeParse(String shutdownTime) {
		try {
			DateTimeFormatter df = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
			return LocalDateTime.parse(shutdownTime, df);
		}
		catch (DateTimeParseException e) {
			e.printStackTrace();
			return null;
		}
	}
}
