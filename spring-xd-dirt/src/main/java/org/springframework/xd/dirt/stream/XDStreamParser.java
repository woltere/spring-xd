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

package org.springframework.xd.dirt.stream;


import java.util.ArrayList;
import java.util.List;

import org.springframework.data.repository.CrudRepository;
import org.springframework.util.Assert;
import org.springframework.validation.BindException;
import org.springframework.xd.dirt.core.BaseDefinition;
import org.springframework.xd.dirt.module.CompositeModuleDeploymentRequest;
import org.springframework.xd.dirt.module.ModuleDefinitionRepository;
import org.springframework.xd.dirt.module.ModuleDeploymentRequest;
import org.springframework.xd.dirt.module.NoSuchModuleException;
import org.springframework.xd.dirt.plugins.ModuleConfigurationException;
import org.springframework.xd.dirt.stream.ParsingContext.Position;
import org.springframework.xd.dirt.stream.dsl.ArgumentNode;
import org.springframework.xd.dirt.stream.dsl.ModuleNode;
import org.springframework.xd.dirt.stream.dsl.SinkChannelNode;
import org.springframework.xd.dirt.stream.dsl.SourceChannelNode;
import org.springframework.xd.dirt.stream.dsl.StreamConfigParser;
import org.springframework.xd.dirt.stream.dsl.StreamNode;
import org.springframework.xd.module.ModuleDefinition;
import org.springframework.xd.module.ModuleType;
import org.springframework.xd.module.options.ModuleOptionsMetadata;
import org.springframework.xd.module.options.ModuleOptionsMetadataResolver;

/**
 * @author Andy Clement
 * @author Gunnar Hillert
 * @author Glenn Renfro
 * @author Mark Fisher
 * @since 1.0
 */
public class XDStreamParser implements XDParser {

	private CrudRepository<? extends BaseDefinition, String> repository;

	private final ModuleDefinitionRepository moduleDefinitionRepository;

	private final ModuleOptionsMetadataResolver moduleOptionsMetadataResolver;

	public XDStreamParser(CrudRepository<? extends BaseDefinition, String> repository,
			ModuleDefinitionRepository moduleDefinitionRepository,
			ModuleOptionsMetadataResolver moduleOptionsMetadataResolver) {
		Assert.notNull(moduleDefinitionRepository, "moduleDefinitionRepository can not be null");
		Assert.notNull(moduleOptionsMetadataResolver, "moduleOptionsMetadataResolver can not be null");
		this.repository = repository;
		this.moduleDefinitionRepository = moduleDefinitionRepository;
		this.moduleOptionsMetadataResolver = moduleOptionsMetadataResolver;
	}

	public XDStreamParser(ModuleDefinitionRepository moduleDefinitionRepository,
			ModuleOptionsMetadataResolver moduleOptionsMetadataResolver) {
		this(null, moduleDefinitionRepository, moduleOptionsMetadataResolver);
	}

	@Override
	public List<ModuleDeploymentRequest> parse(String name, String config, ParsingContext parsingContext) {

		StreamConfigParser parser = new StreamConfigParser(repository);
		StreamNode stream = parser.parse(name, config);
		List<ModuleDeploymentRequest> requests = new ArrayList<ModuleDeploymentRequest>();

		List<ModuleNode> moduleNodes = stream.getModuleNodes();
		for (int m = moduleNodes.size() - 1; m >= 0; m--) {
			ModuleNode moduleNode = moduleNodes.get(m);
			ModuleDeploymentRequest request = new ModuleDeploymentRequest();
			request.setGroup(name);
			request.setModule(moduleNode.getName());
			request.setIndex(m);
			if (moduleNode.hasArguments()) {
				ArgumentNode[] arguments = moduleNode.getArguments();
				for (int a = 0; a < arguments.length; a++) {
					request.setParameter(arguments[a].getName(), arguments[a].getValue());
				}
			}
			requests.add(request);
		}
		SourceChannelNode sourceChannel = stream.getSourceChannelNode();
		SinkChannelNode sinkChannel = stream.getSinkChannelNode();

		if (sourceChannel != null) {
			requests.get(requests.size() - 1).setSourceChannelName(sourceChannel.getChannelName());
		}

		if (sinkChannel != null) {
			requests.get(0).setSinkChannelName(sinkChannel.getChannelName());
		}

		// Now that we know about source and sink channel names,
		// do a second pass to determine type. Also convert to composites.
		// And while we're at it (and type is known), validate module name and options
		List<ModuleDeploymentRequest> result = new ArrayList<ModuleDeploymentRequest>(requests.size());
		for (ModuleDeploymentRequest original : requests) {
			original.setType(determineType(original, requests.size() - 1, parsingContext));

			// definition is guaranteed to be non-null here
			ModuleDefinition moduleDefinition = moduleDefinitionRepository.findByNameAndType(original.getModule(),
					original.getType());
			ModuleOptionsMetadata optionsMetadata = moduleOptionsMetadataResolver.resolve(moduleDefinition);
			if (parsingContext.shouldBindAndValidate()) {
				try {
					optionsMetadata.interpolate(original.getParameters());
				}
				catch (BindException e) {
					throw ModuleConfigurationException.fromBindException(original.getModule(), original.getType(), e);
				}
			}

			result.add(convertToCompositeIfNecessary(original));
		}
		return result;
	}

	private ModuleType determineType(ModuleDeploymentRequest request, int lastIndex, ParsingContext parsingContext) {
		ModuleType moduleType = maybeGuessTypeFromNamedChannels(request, lastIndex, parsingContext);
		if (moduleType != null) {
			return moduleType;
		}
		String name = request.getModule();
		int index = request.getIndex();

		Position position = Position.of(index, lastIndex);
		ModuleType[] allowedTypes = parsingContext.allowed(position);
		return verifyModuleOfTypeExists(name, allowedTypes);


	}

	/**
	 * Attempt to guess the type of a module given the presence of named channels references at the start or end of the
	 * stream definition.
	 * 
	 * @return a sure to be valid module type, or null if no named channels were present
	 */
	private ModuleType maybeGuessTypeFromNamedChannels(ModuleDeploymentRequest request, int lastIndex,
			ParsingContext parsingContext) {
		// Should this fail for composed module too?
		if (parsingContext == ParsingContext.job
				&& (request.getSourceChannelName() != null || request.getSinkChannelName() != null)) {
			throw new RuntimeException("TODO");
		}
		ModuleType type = null;
		String moduleName = request.getModule();
		int index = request.getIndex();
		if (request.getSourceChannelName() != null) { // preceded by >, so not a source
			if (index == lastIndex) { // this is the final module of the stream
				if (request.getSinkChannelName() != null) { // but followed by >, so not a sink
					type = ModuleType.processor;
				}
				else { // final module and no >, so IS a sink
					type = ModuleType.sink;
				}
			}
			else { // not final module, must be a processor
				type = ModuleType.processor;
			}
		}
		else if (request.getSinkChannelName() != null) { // followed by >, so not a sink
			if (index == 0) { // first module in a stream, and not preceded by >, so IS a source
				type = ModuleType.source;
			}
			else { // not first module, and followed by >, so not a source or sink
				type = ModuleType.processor;
			}
		}
		return (type == null) ? null : verifyModuleOfTypeExists(moduleName, type);
	}

	private ModuleDeploymentRequest convertToCompositeIfNecessary(ModuleDeploymentRequest request) {
		ModuleDefinition def = moduleDefinitionRepository.findByNameAndType(request.getModule(), request.getType());
		if (def != null && def.getDefinition() != null) {
			List<ModuleDeploymentRequest> composedModuleRequests = parse(def.getName(), def.getDefinition(),
					ParsingContext.module);
			request = new CompositeModuleDeploymentRequest(request, composedModuleRequests);
		}
		return request;
	}

	/**
	 * Asserts that there exists a module with the given name and type (trying each one in order) and returns that type,
	 * fails otherwise.
	 */
	private ModuleType verifyModuleOfTypeExists(String moduleName, ModuleType... candidates) {
		for (ModuleType type : candidates) {
			ModuleDefinition def = moduleDefinitionRepository.findByNameAndType(moduleName, type);
			if (def != null) {
				return type;
			}
		}
		throw new NoSuchModuleException(moduleName, candidates);
	}

}
