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
package com.sheepit.client.os;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.sheepit.client.Log;

public class Linux extends OS {
	private final String NICE_BINARY_PATH = "nice";
	private final String ID_COMMAND_INVOCATION = "id -u";
	
	public Linux() {
		super();
	}
	
	@Override public String name() {
		return "linux";
	}
	
	@Override public String getRenderBinaryPath() {
		return "rend.exe";
	}
	
	@Override public String getCUDALib() {
		return "cuda";
	}
	
	@Override public Process exec(List<String> command, Map<String, String> env_overight) throws IOException {
		Map<String, String> new_env = new HashMap<String, String>();
		new_env.putAll(java.lang.System.getenv()); // clone the env
		
		// if Blender is already loading an OpenGL library, don't need to load Blender's default one (it will
		// create system incompatibilities). If no OpenGL library is found, then load the one included in the binary
		// zip file
		if (isOpenGLAlreadyInstalled(command.get(0)) == false) {
			Boolean has_ld_library_path = new_env.containsKey("LD_LIBRARY_PATH");
			
			String lib_dir = (new File(command.get(0))).getParent() + File.separator + "lib";
			if (has_ld_library_path == false) {
				new_env.put("LD_LIBRARY_PATH", lib_dir);
			}
			else {
				new_env.put("LD_LIBRARY_PATH", new_env.get("LD_LIBRARY_PATH") + ":" + lib_dir);
			}
		}
		
		List<String> actual_command = command;
		if (checkNiceAvailability()) {
			// launch the process in lowest priority
			if (env_overight != null) {
				actual_command.add(0, env_overight.get("PRIORITY"));
			}
			else {
				actual_command.add(0, "19");
			}
			actual_command.add(0, "-n");
			actual_command.add(0, NICE_BINARY_PATH);
		}
		else {
			Log.getInstance(null).error("No low priority binary, will not launch renderer in normal priority");
		}
		
		ProcessBuilder builder = new ProcessBuilder(actual_command);
		builder.redirectErrorStream(true);
		Map<String, String> env = builder.environment();
		env.putAll(new_env);
		if (env_overight != null) {
			env.putAll(env_overight);
		}
		return builder.start();
	}
	
	@Override public boolean isSupported() {
		String arch = System.getProperty("os.arch").toLowerCase();
		return
			super.isSupported() &&
			(
				"amd64".equals(arch) ||
				"x64".equals(arch) ||
				"x86_64".equals(arch)
			);
	}
	
	@Override public boolean getSupportHighPriority() {
		try {
			ProcessBuilder builder = new ProcessBuilder();
			builder.command("bash", "-c", ID_COMMAND_INVOCATION);
			builder.redirectErrorStream(true);
			
			Process process = builder.start();
			InputStream is = process.getInputStream();
			BufferedReader reader = new BufferedReader(new InputStreamReader(is));
			
			String userLevel = null;
			if ((userLevel = reader.readLine()) != null) {
				// Root user in *ix systems -independently of the alias used to login- has a id value of 0. On top of being a user with root capabilities,
				// to support changing the priority the nice tool must be accessible from the current user
				return (userLevel.equals("0")) & checkNiceAvailability();
			}
		}
		catch (IOException e) {
			System.err.println(String.format("ERROR Linux::getSupportHighPriority Unable to execute id command. IOException %s", e.getMessage()));
		}
		
		return false;
	}
	
	@Override public boolean checkNiceAvailability() {
		ProcessBuilder builder = new ProcessBuilder();
		builder.command(NICE_BINARY_PATH);
		builder.redirectErrorStream(true);
		
		Process process = null;
		boolean hasNiceBinary = false;
		try {
			process = builder.start();
			hasNiceBinary = true;
		}
		catch (IOException e) {
			Log.getInstance(null).error("Failed to find low priority binary, will not launch renderer in normal priority (" + e + ")");
		}
		finally {
			if (process != null) {
				process.destroy();
			}
		}
		return hasNiceBinary;
	}
	
	protected boolean isOpenGLAlreadyInstalled(String pathToRendEXE) {
		ProcessBuilder processBuilder = new ProcessBuilder();
		processBuilder.command("bash", "-c", "ldd '" + pathToRendEXE + "'");    // support for paths with an space
		processBuilder.redirectErrorStream(true);
		
		try {
			Process process = processBuilder.start();
			
			BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
			
			String line;
			StringBuilder screenOutput = new StringBuilder();
			while ((line = reader.readLine()) != null) {
				// check the shared libraries that Blender is loading at run time. If it already loads an existing
				// version of OpenGL (ie the one shipped with NVIDIA drivers) then return false to avoid the client
				// replacing them (and glitching the EEVEE render). Otherwise return true and load the /lib folder
				// to ensure that Blender works correctly
				if (line.toLowerCase().contains("libgl.so")) {
					return !line.toLowerCase().contains("not found");
				}
				
				// In case of error we can later check the screen output from ldd
				screenOutput.append(line);
			}
			
			int exitCode = process.waitFor();
			if (exitCode != 0) {
				System.err.println(String.format("ERROR Linux::isOpenGLAlreadyInstalled Unable to execute ldd command. Exit code %d", exitCode));
				System.err.println(String.format("Screen output from ldd execution: %s", screenOutput.toString()));
			}
		}
		catch (IOException e) {
			System.err.println(String.format("ERROR Linux::isOpenGLAreadyInstalled Unable to execute ldd command. IOException %s", e.getMessage()));
		}
		catch (InterruptedException e) {
			System.err.println(String.format("ERROR Linux::isOpenGLAreadyInstalled Unable to execute ldd command. InterruptedException %s", e.getMessage()));
		}
		
		return false;
	}
	
	@Override public void shutdownComputer(int delayInMinutes) {
		try {
			// Shutdown the computer waiting delayInMinutes minutes to allow all SheepIt threads to close and exit the app
			ProcessBuilder builder = new ProcessBuilder("shutdown", "-h", String.valueOf(delayInMinutes));
			Process process = builder.inheritIO().start();
		}
		catch (IOException e) {
			System.err.println(String.format("Linux::shutdownComputer Unable to execute the 'shutdown -h 1' command. Exception %s", e.getMessage()));
		}
	}
}
