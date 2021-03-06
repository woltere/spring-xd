/*
 * Copyright 2011-2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.xd.integration.test;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.springframework.shell.Bootstrap;
import org.springframework.shell.core.JLineShellComponent;
import org.springframework.xd.integration.fixtures.Sinks;
import org.springframework.xd.integration.fixtures.Sources;
import org.springframework.xd.integration.util.ConfigUtil;
import org.springframework.xd.integration.util.StreamUtils;
import org.springframework.xd.integration.util.XdEc2Validation;
import org.springframework.xd.integration.util.XdEnvironment;
import org.springframework.xd.test.fixtures.AbstractModuleFixture;
import org.springframework.xd.test.fixtures.LogSink;
import org.springframework.xd.test.fixtures.SimpleFileSink;
import org.springframework.xd.test.RandomConfigurationSupport;

/**
 * Base Class for Spring XD Integration classes
 * 
 * @author Glenn Renfro
 */
public abstract class AbstractIntegrationTest {

	private final static String STREAM_NAME = "ec2Test3";

	protected XdEnvironment environment;

	protected XdEc2Validation validation;

	protected URL adminServer;

	protected int httpPort;

	protected List<String> streamNames;

	protected int pauseTime;

	protected String XD_DELIMETER = " | ";

	private JLineShellComponent shell;

	protected Sources sources = null;

	protected Sinks sinks = null;

	private boolean initialized = false;

	ConfigUtil configUtil = null;

	public AbstractIntegrationTest() {
		try {
			environment = new XdEnvironment();
		}
		catch (Exception ex) {
			throw new IllegalArgumentException(ex.getMessage());
		}
		httpPort = environment.getHttpPort();
		sinks = new Sinks(environment);

	}

	/**
	 * Initializes the environment before the test.
	 * 
	 * @throws Exception
	 */
	public void initializer() throws Exception {
		if (!initialized) {
			adminServer = environment.getAdminServer();
			validation = new XdEc2Validation();
			validation.verifyXDAdminReady(adminServer);
			pauseTime = environment.getPauseTime();
			validation.verifyAtLeastOneContainerAvailable(environment.getContainers(),
					environment.getJMXPort());
			RandomConfigurationSupport configSupport = new RandomConfigurationSupport();
			Bootstrap bootstrap = new Bootstrap(new String[] { "--port",
				configSupport.getAdminServerPort() });

			shell = bootstrap.getJLineShellComponent();
			sources = new Sources(adminServer, environment.getContainers(), shell, httpPort);
			configUtil = new ConfigUtil(environment.isOnEc2(), environment);
			initialized = true;
		}
	}

	public JLineShellComponent getShell() {
		return shell;
	}

	@AfterClass
	public static void tearDownAfterClass() throws Exception {
		File file = new File(StreamUtils.TMP_DIR);
		if (file.exists()) {
			file.delete();
		}

	}

	@Before
	public void setup() throws Exception {
		initializer();
		StreamUtils.destroyAllStreams(streamNames, adminServer);
		waitForXD();
		streamNames = new ArrayList<String>();
	}

	@After
	public void tearDown() throws IOException, URISyntaxException {
		StreamUtils.destroyAllStreams(streamNames, adminServer);
		waitForXD();
	}


	/**
	 * Creates a stream on the XD cluster defined by the test's Artifact or Environment variables Uses STREAM_NAME as
	 * default stream name.
	 * 
	 * @param stream the stream definition
	 * @throws IOException
	 */
	public void stream(String stream) throws IOException, URISyntaxException {
		stream(STREAM_NAME, stream);
	}

	/**
	 * Creates a stream on the XD cluster defined by the test's Artifact or Environment variables
	 * 
	 * @param stream the stream definition
	 * @throws IOException
	 */
	public void stream(String streamName, String stream) throws IOException, URISyntaxException {
		StreamUtils.stream(streamName, stream, adminServer);
		streamNames.add(streamName);
		waitForXD();
	}

	/**
	 * Gets the URL of the container where the stream was deployed
	 * 
	 * @param streamName
	 * @return
	 */
	public URL getContainerForStream(String streamName) {
		// Assuming one container for now.
		return environment.getContainers().get(0);
	}

	/**
	 * Verifies that a message was received by source of the stream to be tested.
	 * 
	 * @throws Exception
	 */
	public void assertReceived() throws Exception {
		waitForXD();

		validation.assertReceived(StreamUtils.replacePort(
				getContainerForStream(STREAM_NAME), environment.getJMXPort()), STREAM_NAME,
				"http");
	}

	/**
	 * Verifies that the data stored by the sink is what was expected.
	 * 
	 * @param data - expected data
	 * @param sinkInstance determines whether to look at the log or file for the result
	 * @throws IOException
	 */
	public void assertValid(String data, AbstractModuleFixture sinkInstance) throws IOException {

		if (sinkInstance.getClass().equals(SimpleFileSink.class)) {
			assertValidFile(data, getContainerForStream(STREAM_NAME), STREAM_NAME);
		}
		if (sinkInstance.getClass().equals(LogSink.class)) {
			assertLogEntry(data, getContainerForStream(STREAM_NAME));
		}

	}

	/**
	 * Checks the file data to see if it matches what is expected.
	 * 
	 * @param data The data to validate the file content against.
	 * @param url The URL of the server that we will ssh, to get the data.
	 * @param streamName the name of the file we are retrieving from the remote server.
	 * @throws IOException
	 */
	private void assertValidFile(String data, URL url, String streamName)
			throws IOException {
		waitForXD(pauseTime * 2000);
		String fileName = XdEnvironment.RESULT_LOCATION + "/" + streamName
				+ ".out";
		validation.verifyTestContent(environment, url, fileName, data);
	}

	/**
	 * Checks the log to see if the data specified is in the log.
	 * 
	 * @param data The data to check if it is in the log file
	 * @param url The URL of the server we will ssh, to get the data.
	 * @throws IOException
	 */
	private void assertLogEntry(String data, URL url)
			throws IOException {
		waitForXD();
		validation.verifyLogContent(environment, url, environment.getContainerLogLocation(), data);
	}

	protected void waitForXD() {
		waitForXD(pauseTime * 1000);
	}

	protected void waitForXD(int millis) {
		try {
			Thread.sleep(millis);
		}
		catch (Exception ex) {
			// ignore
		}

	}


	public XdEnvironment getEnvironment() {
		return environment;
	}


}
