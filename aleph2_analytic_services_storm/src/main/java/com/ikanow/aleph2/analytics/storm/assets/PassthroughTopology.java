/*******************************************************************************
 * Copyright 2015, The IKANOW Open Source Project.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package com.ikanow.aleph2.analytics.storm.assets;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import scala.Tuple2;
import backtype.storm.topology.TopologyBuilder;
import backtype.storm.topology.base.BaseRichBolt;
import backtype.storm.topology.base.BaseRichSpout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ikanow.aleph2.data_model.interfaces.data_import.IEnrichmentModuleContext;
import com.ikanow.aleph2.data_model.interfaces.data_import.IEnrichmentStreamingTopology;
import com.ikanow.aleph2.data_model.objects.data_import.DataBucketBean;
import com.ikanow.aleph2.data_model.utils.BeanTemplateUtils;
import com.ikanow.aleph2.data_model.utils.JsonUtils;
import com.ikanow.aleph2.data_model.utils.Tuples;

/** A topology that just goes straight from the default spout to the default output bolt
 * @author Alex
 */
public class PassthroughTopology implements IEnrichmentStreamingTopology {
	protected static final Logger _logger = LogManager.getLogger();	
	
	private static final String BOLT_NAME = "aleph2_default_output_bolt";

	protected static ObjectMapper _mapper = BeanTemplateUtils.configureMapper(Optional.empty());
	
	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_import.IEnrichmentStreamingTopology#getTopologyAndConfiguration(com.ikanow.aleph2.data_model.objects.data_import.DataBucketBean, com.ikanow.aleph2.data_model.interfaces.data_import.IEnrichmentModuleContext)
	 */
	@Override
	public Tuple2<Object, Map<String, String>> getTopologyAndConfiguration(final DataBucketBean bucket, final IEnrichmentModuleContext context) {		
		final TopologyBuilder builder = new TopologyBuilder();
		
		final Collection<Tuple2<BaseRichSpout, String>>  entry_points = context.getTopologyEntryPoints(BaseRichSpout.class, Optional.of(bucket));
		
		//DEBUG
		_logger.debug("Passthrough topology: loaded: " + entry_points.stream().map(x->x.toString()).collect(Collectors.joining(":")));
		
		entry_points.forEach(spout_name -> builder.setSpout(spout_name._2(), spout_name._1()));
		entry_points.stream().reduce(
				builder.setBolt(BOLT_NAME, context.getTopologyStorageEndpoint(BaseRichBolt.class, Optional.of(bucket))),
				(acc, v) -> acc.localOrShuffleGrouping(v._2()),
				(acc1, acc2) -> acc1 // (not possible in practice)
				) ;
		
		return Tuples._2T(builder.createTopology(), Collections.emptyMap());
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_import.IEnrichmentStreamingTopology#rebuildObject(java.lang.Object, java.util.function.Function)
	 */
	@Override
	public <O> JsonNode rebuildObject(final O raw_outgoing_object, final Function<O, LinkedHashMap<String, Object>> generic_outgoing_object_builder) {
		return JsonUtils.foldTuple(generic_outgoing_object_builder.apply(raw_outgoing_object), _mapper, Optional.empty());
	}

}
