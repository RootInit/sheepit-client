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

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.sheepit.client.Client;
import com.sheepit.client.Configuration;
import com.sheepit.client.Gui;
import com.sheepit.client.SettingsLoader;
import com.sheepit.client.Stats;
import com.sheepit.client.TransferStats;
import com.sheepit.client.standalone.swing.activity.Settings;
import com.sheepit.client.standalone.swing.activity.Working;
import lombok.Getter;
import lombok.Setter;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import javax.swing.border.EmptyBorder;
import java.awt.AWTException;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowEvent;
import java.awt.event.WindowStateListener;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;

public class GuiSwing extends JFrame implements Gui {
	public static final String type = "swing";
	private static final String logoPath = "/sheepit-logo.png";

	public static void drawVersionStringOnImage(final BufferedImage image, String versionString) {
		var watermarkWidth = image.getWidth();
		var watermarkHeight = image.getHeight();

		Graphics2D gph = (Graphics2D) image.getGraphics();
		// use anti aliasing to smooth version string
		gph.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		gph.setFont(gph.getFont().deriveFont(12f));

		FontMetrics fontMetrics = gph.getFontMetrics();
		// move text to the very right of the image respecting its length
		int x = watermarkWidth - fontMetrics.stringWidth(versionString);
		// the bottom of the image, but give enough headroom for descending letters like g
		int y = watermarkHeight - fontMetrics.getMaxDescent();

		gph.drawString(versionString, x, y);
		gph.dispose();
	}

	@NotNull public static JLabel createLogoWithWatermark() {
		final URL logoURL = GuiSwing.class.getResource(logoPath);

		// If logo cannot be found, return dummy image
		if (logoURL == null) {
			System.err.println("Error: Unable to find logo " + logoPath);
			return new JLabel();
		}

		JLabel labelImage;
		try {
			// Include the version of the app as a watermark in the SheepIt logo.
			final BufferedImage watermark = ImageIO.read(logoURL);
			String versionString = "v" + Configuration.jarVersion;
			drawVersionStringOnImage(watermark, versionString);

			labelImage = new JLabel(new ImageIcon(watermark));
		}
		catch (Exception e) {
			// If something fails, we just show the SheepIt logo (without any watermark)
			ImageIcon image = new ImageIcon(logoURL);
			labelImage = new JLabel(image);
		}
		return labelImage;
	}

	public enum ActivityType {
		WORKING, SETTINGS
	}
	
	private SystemTray sysTray;
	private JPanel panel;
	private Working activityWorking;
	private Settings activitySettings;
	private TrayIcon trayIcon;
	private boolean useSysTray;
	private String title;
	
	private int framesRendered;
	
	private boolean waitingForAuthentication;
	private Client client;
	
	private BufferedImage iconSprites;
	private BufferedImage[] trayIconSprites;
	
	@Getter @Setter private SettingsLoader settingsLoader;
	
	private ThreadClient threadClient;
	
	public GuiSwing(boolean useSysTray_, String title_) {
		framesRendered = 0;
		useSysTray = useSysTray_;
		title = title_;
		waitingForAuthentication = true;
		
		new Timer().scheduleAtFixedRate(new TimerTask() {
			@Override public void run() {
				if (activityWorking != null) {
					activityWorking.updateTime();
				}
			}
		}, 2 * 1000, 2 * 1000);
	}
	
	@Override public void start() {
		if (useSysTray) {
			try {
				sysTray = SystemTray.getSystemTray();
				if (SystemTray.isSupported()) {
					addWindowStateListener(new WindowStateListener() {
						public void windowStateChanged(WindowEvent e) {
							if (e.getNewState() == ICONIFIED) {
								hideToTray();
							}
						}
					});
				}
			}
			catch (UnsupportedOperationException e) {
				sysTray = null;
			}
		}
		
		// load the images sprite and split into individual images
		URL spriteSequenceUrl = getClass().getResource("/icon-sprites.png");
		
		if (spriteSequenceUrl != null) {
			try {
				iconSprites = ImageIO.read(spriteSequenceUrl);
				trayIconSprites = new BufferedImage[101 * 1];            // sprite sheet has 101 images in 1 column
				
				setIconImage(extractImageFromSprite(-1));    // sprite 0 is standard Sheep It! icon
			}
			catch (IOException e) {
				e.printStackTrace();
			}
		}
		
		setTitle(title);
		setSize(520, 760);
		
		setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		
		panel = new JPanel();
		panel.setLayout(new GridBagLayout());
		Dimension panelPrefSize = new Dimension(this.getSize().width - 20, this.getSize().height);
		panel.setPreferredSize(panelPrefSize);
		
		JScrollPane scrollPane = new JScrollPane(panel);
		scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
		scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
		
		//adjust scrollbar thickness
		scrollPane.getVerticalScrollBar().setPreferredSize(new Dimension(13, 0));
		scrollPane.getHorizontalScrollBar().setPreferredSize(new Dimension(0, 13));
		
		//adjust the speed of scrolling
		scrollPane.getVerticalScrollBar().setUnitIncrement(50);
		scrollPane.getHorizontalScrollBar().setUnitIncrement(50);
		
		setContentPane(scrollPane);
		panel.setBorder(new EmptyBorder(20, 20, 20, 20));
		
		activityWorking = new Working(this);
		activitySettings = new Settings(this);
		
		this.showActivity(ActivityType.SETTINGS);
		
		try {
			if (client.getConfiguration().getTheme().equals("light")) {
				UIManager.setLookAndFeel(new FlatLightLaf());
			}
			else if (client.getConfiguration().getTheme().equals("dark")) {
				UIManager.setLookAndFeel(new FlatDarkLaf());
			}
			
			// Apply the selected theme to swing components
			SwingUtilities.invokeAndWait(() -> FlatLaf.updateUI());
		}
		catch (UnsupportedLookAndFeelException e1) {
			e1.printStackTrace();
		}
		catch (InterruptedException e) {
			e.printStackTrace();
		}
		catch (InvocationTargetException e) {
			e.printStackTrace();
		}
		
		while (waitingForAuthentication) {
			try {
				synchronized (this) {
					wait();
				}
			}
			catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}
	
	@Override public void stop() {
		System.exit(0);
	}
	
	@Override public void status(String msg_) {
		status(msg_, false);
	}
	
	@Override public void status(String msg_, boolean overwriteSuspendedMsg) {
		if (activityWorking != null) {
			this.activityWorking.setStatus(msg_, overwriteSuspendedMsg);
		}
	}
	
	@Override public void status(String msg, int progress) {
		if (activityWorking != null) {
			this.activityWorking.setStatus(String.format("%s %d%%", msg, progress));
		}
	}
	
	@Override public void status(String msg, int progress, long size) {
		this.status(msg, progress);
	}
	
	@Override public void setRenderingProjectName(String name_) {
		if (activityWorking != null) {
			this.activityWorking.setRenderingProjectName(name_);
		}
	}
	
	@Override public void error(String msg_) {
		status(msg_, true);
	}
	
	@Override public void setRemainingTime(String time_) {
		if (activityWorking != null) {
			this.activityWorking.setRemainingTime(time_);
		}
	}
	
	@Override public void setRenderingTime(String time_) {
		if (activityWorking != null) {
			this.activityWorking.setRenderingTime(time_);
		}
	}
	
	@Override public synchronized void displayTransferStats(TransferStats downloads, TransferStats uploads) {
		this.activityWorking.displayTransferStats(downloads, uploads);
	}
	
	@Override public void AddFrameRendered() {
		framesRendered++;
		
		if (activityWorking != null) {
			this.activityWorking.setRenderedFrame(framesRendered);
		}
		else {
			System.out.println("GuiSwing::AddFrameRendered() error: no working activity");
		}
	}
	
	@Override public void displayStats(Stats stats) {
		if (activityWorking != null) {
			this.activityWorking.displayStats(stats);
		}
	}
	
	@Override public void displayUploadQueueStats(int queueSize, long queueVolume) {
		if (activityWorking != null) {
			this.activityWorking.displayUploadQueueStats(queueSize, queueVolume);
		}
	}
	
	@Override public Client getClient() {
		return client;
	}
	
	@Override public void setClient(Client cli) {
		client = cli;
	}
	
	@Override public void setComputeMethod(String computeMethod) {
		this.activityWorking.setComputeMethod(computeMethod);
	}
	
	public Configuration getConfiguration() {
		return client.getConfiguration();
	}
	
	@Override public void successfulAuthenticationEvent(String publickey) {
		if (settingsLoader != null) {
			if (publickey != null && settingsLoader.getLogin().isLaunchCommand() == false) {
				settingsLoader.getPassword().setValue(publickey);
			}
			settingsLoader.saveFile();
		}
	}
	
	public void setCredentials(String contentLogin, String contentPassword) {
		client.getConfiguration().setLogin(contentLogin);
		client.getConfiguration().setPassword(contentPassword);
		
		waitingForAuthentication = false;
		synchronized (this) {
			notifyAll();
		}
		
		if (threadClient == null || threadClient.isAlive() == false) {
			threadClient = new ThreadClient();
			threadClient.start();
		}
		
		showActivity(ActivityType.WORKING);
	}
	
	public void showActivity(ActivityType type) {
		panel.removeAll();
		panel.doLayout();
		
		if (type == ActivityType.WORKING) {
			activityWorking.show();
		}
		else if (type == ActivityType.SETTINGS) {
			activitySettings.show();
		}
		
		setVisible(true);
		panel.repaint();
	}
	
	public void hideToTray() {
		if (sysTray == null || SystemTray.isSupported() == false) {
			System.out.println("GuiSwing::hideToTray SystemTray not supported!");
			return;
		}
		
		try {
			trayIcon = getTrayIcon();
			sysTray.add(trayIcon);
		}
		catch (AWTException e) {
			System.out.println("GuiSwing::hideToTray an error occured while trying to add system tray icon (exception: " + e + ")");
			return;
		}
		
		setVisible(false);
		
	}
	
	public void restoreFromTray() {
		if (sysTray != null && SystemTray.isSupported()) {
			sysTray.remove(trayIcon);
			setVisible(true);
			setExtendedState(getExtendedState() & ~JFrame.ICONIFIED & JFrame.NORMAL); // for toFront and requestFocus to actually work
			toFront();
			requestFocus();
		}
	}
	
	public TrayIcon getTrayIcon() {
		final PopupMenu trayMenu = new PopupMenu();
		
		// on start, show the base icon
		Image img = extractImageFromSprite(-1);
		final TrayIcon icon = new TrayIcon(img);
		
		MenuItem exit = new MenuItem("Exit");
		exit.addActionListener(new ActionListener() {
			@Override public void actionPerformed(ActionEvent e) {
				System.exit(0);
			}
		});
		trayMenu.add(exit);
		
		MenuItem open = new MenuItem("Open...");
		open.addActionListener(new ActionListener() {
			@Override public void actionPerformed(ActionEvent e) {
				restoreFromTray();
			}
		});
		trayMenu.add(open);
		
		MenuItem settings = new MenuItem("Settings...");
		settings.addActionListener(new ActionListener() {
			@Override public void actionPerformed(ActionEvent e) {
				restoreFromTray();
				showActivity(ActivityType.SETTINGS);
			}
		});
		trayMenu.add(settings);
		
		icon.setPopupMenu(trayMenu);
		icon.setImageAutoSize(true);
		icon.setToolTip("SheepIt! Client");
		
		icon.addActionListener(new ActionListener() {
			@Override public void actionPerformed(ActionEvent e) {
				restoreFromTray();
			}
		});
		
		return icon;
		
	}
	
	public JPanel getContentPanel() {
		return this.panel;
	}
	
	private Image extractImageFromSprite(int spriteNumber) {
		// Sprite structure
		// Image 0: base sprite
		// Images 1-101: progress bar percentage from 0 to 100
		//
		// Always add +1 to the icon requested.
		// -1 turns into 0 (base sprite with no progress bar)
		// 0 to 101 turns into 1 to 101 (progress sequence starts in sprite 1 and ends on sprite 101)
		ImageIcon img = new ImageIcon(iconSprites.getSubimage(0, (spriteNumber + 1) * 114, 114, 114));
		
		return img.getImage();
	}
	
	@Override public void updateTrayIcon(Integer percentage) {
		// update the app icon on the app bar
		Image img = extractImageFromSprite(percentage);
		setIconImage(img);
		
		// if the app supports the system tray, update as well
		if (sysTray != null && SystemTray.isSupported()) {
			if (trayIcon != null) {
				trayIcon.setImage(img);
				trayIcon.setImageAutoSize(true);        // use this method to ensure that icon is refreshed when on
				// the tray
			}
		}
	}
	
	public class ThreadClient extends Thread {
		@Override public void run() {
			if (GuiSwing.this.client != null) {
				GuiSwing.this.client.run();
			}
		}
	}
	
}
