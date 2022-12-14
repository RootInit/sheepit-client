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

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.sheepit.client.Error.ServerCode;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.exception.ZipException;

public class Utils {
	public static int unzipFileIntoDirectory(String zipFileName_, String destinationDirectory, char[] password, Log log) {
		try {
			ZipFile zipFile = new ZipFile(zipFileName_);
//			unzipParameters.setIgnoreDateTimeAttributes(true);
			
			if (password != null && zipFile.isEncrypted()) {
				zipFile.setPassword(password);
			}
//			zipFile.extractAll(destinationDirectory, unzipParameters);
			zipFile.extractAll(destinationDirectory);
		}
		catch (ZipException e) {
			StringWriter sw = new StringWriter();
			PrintWriter pw = new PrintWriter(sw);
			e.printStackTrace(pw);
			log.debug("Utils::unzipFileIntoDirectory(" + zipFileName_ + "," + destinationDirectory + ") exception " + e + " stacktrace: " + sw.toString());
			return -1;
		}
		return 0;
	}
	
	public static String md5(String path_of_file_) {
		try {
			MessageDigest md = MessageDigest.getInstance("MD5");
			InputStream is = Files.newInputStream(Paths.get(path_of_file_));
			DigestInputStream dis = new DigestInputStream(is, md);
			byte[] buffer = new byte[8192];
			while (dis.read(buffer) > 0)
				; // process the entire file
			String data = convertBinaryToHex(md.digest());
			dis.close();
			is.close();
			return data;
		}
		catch (NoSuchAlgorithmException | IOException e) {
			return "";
		}
	}
	
	public static String convertBinaryToHex(byte[] bytes) {
		StringBuilder hexStringBuilder = new StringBuilder();
		for (byte aByte : bytes) {
			char[] hex = new char[2];
			hex[0] = Character.forDigit((aByte >> 4) & 0xF, 16);
			hex[1] = Character.forDigit((aByte & 0xF), 16);
			hexStringBuilder.append(new String(hex));
		}
		return hexStringBuilder.toString();
	}
	
	public static double lastModificationTime(File directory_) {
		double max = 0.0;
		if (directory_.isDirectory()) {
			File[] list = directory_.listFiles();
			if (list != null) {
				for (File aFile : list) {
					double max1 = lastModificationTime(aFile);
					if (max1 > max) {
						max = max1;
					}
				}
			}
		}
		else if (directory_.isFile()) {
			return directory_.lastModified();
		}
		
		return max;
	}
	
	public static ServerCode statusIsOK(Document document_, String rootname_) {
		if (document_ == null) {
			return Error.ServerCode.UNKNOWN;
		}
		NodeList ns = document_.getElementsByTagName(rootname_);
		if (ns.getLength() == 0) {
			return Error.ServerCode.ERROR_NO_ROOT;
		}
		Element a_node = (Element) ns.item(0);
		if (a_node.hasAttribute("status")) {
			return Error.ServerCode.fromInt(Integer.parseInt(a_node.getAttribute("status")));
		}
		return Error.ServerCode.UNKNOWN;
	}
	
	/**
	 * Will recursively delete a directory
	 */
	public static void delete(File file) {
		if (file == null) {
			return;
		}
		if (file.isDirectory()) {
			String[] files = file.list();
			if (files != null) {
				if (files.length != 0) {
					for (String temp : files) {
						File fileDelete = new File(file, temp);
						delete(fileDelete);
					}
				}
			}
		}
		file.delete();
	}

	/**
	 * Will move a directory
	 */
	public static void move(File file, String dest) {
		if (file == null) {
			return;
		}
		File newfile = new File(dest + File.separator + file.getName());
		newfile.mkdirs();
		try {
			Files.move(file.toPath(), newfile.toPath(), StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
		   System.err.println("Exception while moving file: " + e.getMessage());
		}
	 }
	
	/**
	 * Parse a number string to a number.
	 * Input can be as "32", "10k", "100K", "100G", "1.3G", "0.4T"
	 */
	public static long parseNumber(String in) {
		in = in.trim();
		in = in.replaceAll(",", ".");
		try {
			return Long.parseLong(in);
		}
		catch (NumberFormatException e) {
		}
		final Matcher m = Pattern.compile("([\\d.,]+)\\s*(\\w)").matcher(in);
		m.find();
		int scale = 1;
		switch (m.group(2).charAt(0)) {
			case 'T':
			case 't':
				scale = 1000 * 1000 * 1000 * 1000;
				break;
			case 'G':
			case 'g':
				scale = 1000 * 1000 * 1000;
				break;
			case 'M':
			case 'm':
				scale = 1000 * 1000;
				break;
			case 'K':
			case 'k':
				scale = 1000;
				break;
		}
		return Math.round(Double.parseDouble(m.group(1)) * scale);
	}
	
	public static String humanDuration(Date date) {
		Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("GMT"));
		calendar.setTime(date);
		
		int hours = (calendar.get(Calendar.DAY_OF_MONTH) - 1) * 24 + calendar.get(Calendar.HOUR_OF_DAY);
		int minutes = calendar.get(Calendar.MINUTE);
		int seconds = calendar.get(Calendar.SECOND);
		
		String output = "";
		if (hours > 0) {
			output += hours + "h ";
		}
		if (minutes > 0) {
			output += minutes + "min ";
		}
		if (seconds > 0) {
			output += seconds + "s";
		}
		return output;
	}
	
	public static boolean noFreeSpaceOnDisk(String destination_, Log log) {
		try {
			File file = new File(destination_);
			for (int i = 0; i < 3; i++) { //We poll repeatedly because getUsableSpace() might just return 0 on busy disk IO
				long space = file.getUsableSpace();
				if (space > 512 * 1024) { // at least the same amount as Server.HTTPGetFile
					return false; // If we are not "full", we are done, no need for additional polling
				} else if (i < 2) {
					long time = (long) (Math.random() * (100 - 40 + 1) + 40); //Wait between 40 and 100 milliseconds
					log.debug("Utils::Not enough free disk space(" + space + ") encountered on try " + i + ", waiting " + time + "ms");
					Thread.sleep(time);
				}
			}
			return true;
		}
		catch (SecurityException | InterruptedException e) {
		}
		
		return false;
	}

	public static String findMimeType(String file) throws IOException {
		String mimeType = Files.probeContentType(Paths.get(file));
		if (mimeType == null) {
			InputStream stream = new BufferedInputStream(new FileInputStream(file));
			mimeType = URLConnection.guessContentTypeFromStream(stream);
		}
		if (mimeType == null) {
			mimeType = URLConnection.guessContentTypeFromName(file);
		}
		
		if (mimeType == null && file.endsWith(".tga")) { // fallback for TGA
			mimeType = "image/tga";
		}

		if (mimeType == null && file.endsWith(".exr")) { // fallback for EXR
			mimeType = "image/x-exr";
		}

		return mimeType;
	}
	
	public static String formatDataConsumption(long bytes) {
		float divider = 0;
		String suffix = "";
		
		if (bytes > 1099511627776f) {    // 1TB
			divider = 1099511627776f;
			suffix = "TB";
		}
		else if (bytes > 1073741824) {    // 1GB
			divider = 1073741824;
			suffix = "GB";
		}
		else {    // 1MB
			divider = 1048576;
			suffix = "MB";
		}
		
		return String.format("%.2f%s", (bytes / divider), suffix);
	}
}
