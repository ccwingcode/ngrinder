/* 
 * Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License. 
 */
package org.ngrinder;

import static org.ngrinder.common.util.NoOp.noOp;
import static org.ngrinder.common.util.Preconditions.checkNotNull;

import java.io.File;
import java.io.FilenameFilter;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;

import javax.jnlp.DownloadService2;
import javax.jnlp.DownloadService2.ResourceSpec;
import javax.jnlp.ServiceManager;

import net.grinder.AgentControllerDaemon;
import net.grinder.communication.AgentControllerCommunicationDefauts;
import net.grinder.util.NetworkUtil;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.BooleanUtils;
import org.apache.commons.lang.StringUtils;
import org.hyperic.sigar.ProcState;
import org.hyperic.sigar.Sigar;
import org.hyperic.sigar.SigarException;
import org.ngrinder.common.util.CompressionUtil;
import org.ngrinder.infra.AgentConfig;
import org.ngrinder.monitor.MonitorConstants;
import org.ngrinder.monitor.agent.AgentMonitorServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.Context;
import ch.qos.logback.core.joran.spi.JoranException;

/**
 * Main class to start agent or monitor.
 * 
 * @author Mavlarn
 * @author JunHo Yoon
 * @since 3.0
 */
public class NGrinderStarter {

	private static final Logger LOG = LoggerFactory.getLogger(NGrinderStarter.class);

	private AgentConfig agentConfig;

	private AgentControllerDaemon agentController;

	private ReconfigurableURLClassLoader classLoader;

	private String jnlpLibPath;

	private static final String LOCAL_NATIVE_PATH = "./native_lib";

	/**
	 * Constructor.
	 */
	public NGrinderStarter() {
		agentConfig = new AgentConfig();
		agentConfig.init();
		// Configure log.
		Boolean verboseMode = agentConfig.getPropertyBoolean("verbose", false);
		File logDirectory = agentConfig.getHome().getLogDirectory();
		configureLogging(verboseMode, logDirectory);
		addCustomClassLoader();
		addClassPath();
		addLibarayPath();
	}

	private void addCustomClassLoader() {
		URL[] urLs = ((URLClassLoader) Thread.currentThread().getContextClassLoader()).getURLs();
		this.classLoader = new ReconfigurableURLClassLoader(urLs);
		Thread.currentThread().setContextClassLoader(this.classLoader);
	}

	/*
	 * get the start mode, "agent" or "monitor". If it is not set in configuration, it will return
	 * "agent".
	 */
	public String getStartMode() {
		return agentConfig.getAgentProperties().getProperty("start.mode", "agent");
	}

	/**
	 * Get agent version.
	 * 
	 * @return version string
	 */
	public String getVersion() {
		return agentConfig.getInternalProperty("ngrinder.version", "UNKNOWN");
	}

	/**
	 * Start the performance monitor.
	 */
	public void startMonitor() {
		LOG.info("**************************");
		LOG.info("* Start nGrinder Monitor *");
		LOG.info("**************************");
		LOG.info("* Colllect SYSTEM data. **");

		MonitorConstants.init(agentConfig);

		try {

			String localHostAddress = NetworkUtil.getLocalHostAddress();
			System.setProperty("java.rmi.server.hostname", localHostAddress);
			AgentMonitorServer.getInstance().init(agentConfig.getHome().getDirectory());
			AgentMonitorServer.getInstance().start();
		} catch (Exception e) {
			LOG.error("ERROR: {}", e.getMessage());
			printHelpAndExit("Error while starting Monitor", e);
		}
	}

	/**
	 * Stop monitors.
	 */
	public void stopMonitor() {
		AgentMonitorServer.getInstance().stop();
	}

	/**
	 * Start ngrinder agent.
	 * 
	 * @param controllerIp
	 *            controllerIp;
	 */
	public void startAgent(String controllerIp) {
		LOG.info("***************************************************");
		LOG.info(" Start nGrinder Agent ...");
		String consoleIP = StringUtils.isNotEmpty(controllerIp) ? controllerIp : agentConfig.getAgentProperties()
						.getProperty("agent.console.ip", "127.0.0.1");

		if (!NetworkUtil.isValidIP(consoleIP)) {
			LOG.error("Hey!! {} does not seems like IP. Try to resolve the ip by {}.", consoleIP, consoleIP);
			InetAddress byName;
			try {
				byName = InetAddress.getByName(consoleIP);
				consoleIP = byName.getHostAddress();
				agentConfig.getAgentProperties().setProperty("agent.console.ip", consoleIP);
				LOG.info("Console IP is resolved as  {}.", consoleIP);
			} catch (UnknownHostException e) {
				consoleIP = "127.0.0.1";
				LOG.info("Console IP   resolution is failed. Use 127.0.0.1 instead.");
			} finally {
				agentConfig.getAgentProperties().setProperty("agent.console.ip", consoleIP);
			}

		}
		int consolePort = agentConfig.getAgentProperties().getPropertyInt("agent.console.port",
						AgentControllerCommunicationDefauts.DEFAULT_AGENT_CONTROLLER_SERVER_PORT);
		String region = agentConfig.getAgentProperties().getProperty("agent.region", "");
		LOG.info("with console: {}:{}", consoleIP, consolePort);
		boolean serverMode = agentConfig.getPropertyBoolean("agent.servermode", false);
		if (!serverMode) {
			LOG.info("JVM server mode is disabled. If you turn on ngrinder.servermode in agent.conf."
							+ " It will provide the better agent performance.");
		}

		try {
			String localHostAddress = NetworkUtil.getLocalHostAddress();
			System.setProperty("java.rmi.server.hostname", localHostAddress);
			agentController = new AgentControllerDaemon(localHostAddress);
			agentController.getAgentController().setAgentConfig(agentConfig);
			agentController.setRegion(region);
			agentController.setAgentConfig(agentConfig);
			agentController.run(consoleIP, consolePort);
		} catch (Exception e) {
			LOG.error("ERROR: {}", e.getMessage());
			printHelpAndExit("Error while starting Agent", e);
		}
	}

	/**
	 * stop the ngrinder agent.
	 */
	public void stopAgent() {
		LOG.info("Stop nGrinder agent!");
		agentController.shutdown();
	}

	private void addLibarayPath() {
		String property = StringUtils.trimToEmpty(System.getProperty("java.library.path"));
		String nativePath = isWebStart() ? jnlpLibPath : LOCAL_NATIVE_PATH;
		System.setProperty("java.library.path", property + File.pathSeparator + nativePath);
		LOG.info("java.library.path : {} ", System.getProperty("java.library.path"));
	}

	/**
	 * Get jar file list.
	 * 
	 * @return jar file collection
	 */
	protected Collection<File> getJarFileList() {
		jnlpLibPath = agentConfig.getHome().getDirectory() + File.separator + "jnlp_res";
		ArrayList<File> fileString = new ArrayList<File>();
		if (isWebStart()) {
			try {
				DownloadService2 service = (DownloadService2) ServiceManager.lookup("javax.jnlp.DownloadService2");
				ResourceSpec alljars = new ResourceSpec("http://.*", null, DownloadService2.JAR);
				ResourceSpec[] results = service.getCachedResources(alljars);

				for (ResourceSpec r : results) {
					String url = r.getUrl().toString();
					String fileName = url.substring(url.lastIndexOf('/') + 1, url.length());
					File jarFile = new File(jnlpLibPath, fileName);
					FileUtils.copyURLToFile(new URL(url), jarFile);
					if (fileName.equals("native.jar")) {
						CompressionUtil.unjar(jarFile, jnlpLibPath);
					}
					fileString.add(jarFile);
				}
			} catch (Exception e) {
				staticPrintHelpAndExit("Error occurs while getting Jar file from Service !");
			}
		} else {
			File libFolder = new File(".", "lib").getAbsoluteFile();
			if (!libFolder.exists()) {
				printHelpAndExit("lib path (" + libFolder.getAbsolutePath() + ") does not exist");
			}
			String[] exts = new String[] { "jar" };
			fileString.addAll(FileUtils.listFiles(libFolder, exts, false));
		}

		return fileString;
	}

	/**
	 * Add class path.
	 */
	protected void addClassPath() {

		ArrayList<String> libString = new ArrayList<String>();
		Collection<File> libList = getJarFileList();

		// Add patch first
		for (File each : libList) {
			if (each.getName().contains("patch")) {
				addClassPath(classLoader, each);
				libString.add(each.getPath());
			}
		}

		// Add rest of them
		for (File each : libList) {
			if (!each.getName().contains("patch")) {
				addClassPath(classLoader, each);
				libString.add(each.getPath());
			}
		}

		if (!libString.isEmpty()) {
			String base = System.getProperties().getProperty("java.class.path");
			String classpath = base + File.pathSeparator + StringUtils.join(libString, File.pathSeparator);
			System.getProperties().setProperty("java.class.path", classpath);
		}
	}

	private void addClassPath(ReconfigurableURLClassLoader urlClassLoader, File jarFile) {
		try {
			URL jarFileUrl = checkNotNull(jarFile.toURI().toURL());
			urlClassLoader.addURL(jarFileUrl);
		} catch (MalformedURLException e) {
			LOG.error(e.getMessage(), e);
		}
	}

	/**
	 * {@link URLClassLoader} which exposes addURL method.
	 * 
	 * @author JunHo Yoon
	 * @since 3.1
	 */
	static class ReconfigurableURLClassLoader extends URLClassLoader {

		public ReconfigurableURLClassLoader(URL[] urls) {
			super(urls);
		}

		@Override
		public void addURL(URL url) {
			super.addURL(url);
		}
	}

	private void configureLogging(boolean verbose, File logDirectory) {

		final Context context = (Context) LoggerFactory.getILoggerFactory();

		final JoranConfigurator configurator = new JoranConfigurator();
		configurator.setContext(context);
		context.putProperty("LOG_LEVEL", verbose ? "TRACE" : "INFO");
		context.putProperty("LOG_DIRECTORY", logDirectory.getAbsolutePath());
		try {
			configurator.doConfigure(NGrinderStarter.class.getResource("/logback-agent.xml"));
		} catch (JoranException e) {
			staticPrintHelpAndExit("Can not configure logger on " + logDirectory.getAbsolutePath()
							+ ".\n Please check if it's writable.");
		}
	}

	/**
	 * print help and exit. This is provided for mocking.
	 * 
	 * @param message
	 *            message
	 */
	protected void printHelpAndExit(String message) {
		staticPrintHelpAndExit(message);
	}

	/**
	 * print help and exit. This is provided for mocking.
	 * 
	 * @param message
	 *            message
	 * @param e
	 *            exception
	 */
	protected void printHelpAndExit(String message, Exception e) {
		staticPrintHelpAndExit(message, e);
	}

	/**
	 * Agent starter.
	 * 
	 * @param args
	 *            arguments
	 */
	public static void main(String[] args) {

		if (!isValidCurrentDirectory() && !isWebStart()) {
			staticPrintHelpAndExit("nGrinder agent should start in the folder which nGrinder agent exists.");
		}
		NGrinderStarter starter = new NGrinderStarter();
		String startMode = System.getProperty("start.mode");
		LOG.info("- Passing mode " + startMode);
		LOG.info("- nGrinder version " + starter.getVersion());
		if ("stopagent".equalsIgnoreCase(startMode)) {
			starter.stopProcess("agent");
			return;
		} else if ("stopmonitor".equalsIgnoreCase(startMode)) {
			starter.stopProcess("monitor");
			return;
		}
		startMode = (startMode == null) ? starter.getStartMode() : startMode;
		starter.checkDuplicatedRun(startMode);

		if (startMode.equalsIgnoreCase("agent")) {
			String controllerIp = System.getProperty("controller");
			starter.startAgent(controllerIp);
		} else if (startMode.equalsIgnoreCase("monitor")) {
			starter.startMonitor();
		} else {
			staticPrintHelpAndExit("Invalid agent.conf, 'start.mode' must be set as 'monitor' or 'agent'.");
		}
	}

	/**
	 * Stop process.
	 * 
	 * @param mode
	 *            agent or monitor.
	 */
	protected void stopProcess(String mode) {
		String pid = agentConfig.getAgentPidProperties(mode);
		try {
			if (StringUtils.isNotBlank(pid)) {
				new Sigar().kill(pid, 15);
			}
			agentConfig.updateAgentPidProperties(mode);
		} catch (SigarException e) {
			printHelpAndExit(String.format("Error occurs while terminating %s process."
							+ "It can be already stopped or you may not have the permission.\n"
							+ "If everything is OK. Please stop it manually.", mode), e);
		}
	}

	/**
	 * Check if the process is already running in this env.
	 * 
	 * @param startMode
	 *            monitor or agent
	 */
	public void checkDuplicatedRun(String startMode) {
		Sigar sigar = new Sigar();
		String existingPid = this.agentConfig.getAgentPidProperties(startMode);
		if (StringUtils.isNotEmpty(existingPid)) {
			try {
				ProcState procState = sigar.getProcState(existingPid);
				if (procState.getState() == ProcState.RUN || procState.getState() == ProcState.IDLE
								|| procState.getState() == ProcState.SLEEP) {
					printHelpAndExit("Currently " + startMode + " is running on pid " + existingPid
									+ ". Please stop it before run");
				}
				agentConfig.updateAgentPidProperties(startMode);
			} catch (SigarException e) {
				noOp();
			}
		}

		this.agentConfig.saveAgentPidProperties(String.valueOf(sigar.getPid()), startMode);
	}

	/**
	 * Check the current directory is valid or not.<br/>
	 * ngrinder agent should run in the folder agent exists.
	 * 
	 * @return true if it's valid
	 */
	private static boolean isValidCurrentDirectory() {
		File currentFolder = new File(System.getProperty("user.dir"));
		String[] list = currentFolder.list(new FilenameFilter() {
			@Override
			public boolean accept(File dir, String name) {
				return (name.startsWith("ngrinder-core") && name.endsWith(".jar"));
			}
		});
		return (list != null && list.length != 0);
	}

	private static void staticPrintHelpAndExit(String message) {
		staticPrintHelpAndExit(message, null);
	}

	private static void staticPrintHelpAndExit(String message, Exception e) {
		LOG.error(message);
		System.exit(-1);
	}

	/**
	 * Check agent start mode.
	 * 
	 * @return true if it's jnlp web start
	 */
	private static boolean isWebStart() {
		return BooleanUtils.toBoolean(System.getProperty("start.webstart", "false"));
	}
}
