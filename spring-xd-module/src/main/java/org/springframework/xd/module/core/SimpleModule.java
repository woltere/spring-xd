/*
 * Copyright 2013 the original author or authors.
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

package org.springframework.xd.module.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.builder.ParentContextCloserApplicationListener;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.ContextIdApplicationContextInitializer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.validation.BindException;
import org.springframework.xd.module.DeploymentMetadata;
import org.springframework.xd.module.ModuleDefinition;
import org.springframework.xd.module.options.ModuleOptions;
import org.springframework.xd.module.options.PassthruModuleOptionsMetadata;

/**
 * A {@link Module} implementation backed by a Spring {@link ApplicationContext}.
 * 
 * @author Mark Fisher
 * @author David Turanski
 * @author Gary Russell
 * @author Dave Syer
 * @author Ilayaperumal Gopinathan
 * @author Eric Bottard
 */
public class SimpleModule extends AbstractModule {

	/**
	 * Dedicated sublcass of {@link ParentContextCloserApplicationListener} used to create its own version of
	 * ContextCloserListener that is aware of module order. Special care is taken so that no strong references to the
	 * module context are retained (this is a *static* inner class).
	 * 
	 * @author Eric Bottard
	 */
	private static final class ModuleParentContextCloserApplicationListener extends
			ParentContextCloserApplicationListener {

		private final int index;

		public ModuleParentContextCloserApplicationListener(int index) {
			this.index = index;
		}

		@Override
		protected ContextCloserListener createContextCloserListener(ConfigurableApplicationContext child) {
			return new ModuleContextCloserListener(child, index);
		}

		/**
		 * Module context closer listener that sets the order based on the module deployment index.
		 */
		final static class ModuleContextCloserListener extends ContextCloserListener implements Ordered {

			private int index;

			public ModuleContextCloserListener(ConfigurableApplicationContext moduleContext, int index) {
				super(moduleContext);
				this.index = index;
			}

			@Override
			public int getOrder() {
				// Make sure producer modules get closed before the consumer modules (sink/processor)
				// by setting them the highest precedence. Smaller values come first.
				return index;
			}

		}
	}

	private final Log logger = LogFactory.getLog(this.getClass());

	private ConfigurableApplicationContext context;

	private final SpringApplicationBuilder application;

	private final AtomicInteger propertiesCounter = new AtomicInteger();

	private final Properties properties = new Properties();

	private final MutablePropertySources propertySources = new MutablePropertySources();

	private ConfigurableApplicationContext parent;

	private final List<ApplicationListener<?>> listeners = new ArrayList<ApplicationListener<?>>();

	private ModuleOptions moduleOptions;

	public SimpleModule(ModuleDefinition definition, DeploymentMetadata metadata) {
		this(definition, metadata, null, defaultModuleOptions());
	}

	private static ModuleOptions defaultModuleOptions() {
		try {
			return new PassthruModuleOptionsMetadata().interpolate(Collections.<String, String> emptyMap());
		}
		catch (BindException e) {
			throw new IllegalStateException(e);
		}
	}

	public SimpleModule(ModuleDefinition definition, DeploymentMetadata metadata, ClassLoader classLoader,
			ModuleOptions moduleOptions) {
		super(definition, metadata);
		this.moduleOptions = moduleOptions;
		application = new SpringApplicationBuilder().sources(PropertyPlaceholderAutoConfiguration.class).web(false);
		if (classLoader != null) {
			application.resourceLoader(new PathMatchingResourcePatternResolver(classLoader));
		}

		// Also add options as properties for now, b/c other parts of the system
		// (eg type conversion plugin) expects it
		this.properties.putAll(moduleOptionsToProperties(moduleOptions));

		application.profiles(moduleOptions.profilesToActivate());

		if (definition != null && definition.getResource().isReadable()) {
			this.addComponents(definition.getResource());
		}
	}


	private Map<Object, Object> moduleOptionsToProperties(ModuleOptions moduleOptions) {
		Map<Object, Object> result = new HashMap<Object, Object>();
		EnumerablePropertySource<?> ps = moduleOptions.asPropertySource();
		for (String propname : ps.getPropertyNames()) {
			Object value = ps.getProperty(propname);
			if (value != null) {
				result.put(propname, value.toString());
			}
		}
		return result;
	}

	@Override
	public void setParentContext(ApplicationContext parent) {
		this.parent = (ConfigurableApplicationContext) parent;
	}

	@Override
	public void addComponents(Resource resource) {
		addSource(resource);
	}

	protected void addSource(Object source) {
		application.sources(source);
	}

	@Override
	public void addProperties(Properties properties) {
		this.registerPropertySource(properties);
		this.properties.putAll(properties);
	}

	@Override
	public void addListener(ApplicationListener<?> listener) {
		this.listeners.add(listener);
	}

	@Override
	public Properties getProperties() {
		return this.properties;
	}

	public ApplicationContext getApplicationContext() {
		return this.context;
	}

	@Override
	public <T> T getComponent(Class<T> requiredType) {
		return this.context.getBean(requiredType);
	}

	@Override
	public <T> T getComponent(String componentName, Class<T> requiredType) {
		if (this.context.containsBean(componentName)) {
			return context.getBean(componentName, requiredType);
		}
		return null;
	}

	private void registerPropertySource(Properties properties) {
		int propertiesIndex = this.propertiesCounter.getAndIncrement();
		String propertySourceName = "properties-" + propertiesIndex;
		PropertySource<?> propertySource = new PropertiesPropertySource(propertySourceName, properties);
		this.propertySources.addLast(propertySource);
	}

	@Override
	public void initialize() {
		this.application.initializers(new ContextIdApplicationContextInitializer(this.toString()));
		ConfigurableEnvironment parentEnvironment = parent == null ? null
				: parent.getEnvironment();
		ModuleEnvironment environment = new ModuleEnvironment(moduleOptions.asPropertySource(), parentEnvironment);
		for (PropertySource<?> source : propertySources) {
			environment.getPropertySources().addFirst(source);
		}
		this.application.parent(parent);
		this.application.environment(environment);
		if (this.listeners.size() > 0) {
			application.listeners(this.listeners.toArray(new ApplicationListener<?>[this.listeners.size()]));
		}
		this.application.listeners(new ModuleParentContextCloserApplicationListener(getDeploymentMetadata().getIndex()));
		this.context = this.application.run();
		if (logger.isInfoEnabled()) {
			logger.info("initialized module: " + this.toString());
		}
	}

	@Override
	public void start() {
		context.start();
	}

	@Override
	public void stop() {
		context.stop(); // Shouldn't need to close() as well?
	}

	@Override
	public boolean isRunning() {
		return context.isRunning();
	}

	@Override
	public void destroy() {
		if (context instanceof DisposableBean) {
			try {
				((DisposableBean) context).destroy();
			}
			catch (RuntimeException e) {
				throw e;
			}
			catch (Exception e) {
				throw new IllegalStateException(e);
			}
		}
	}

}
