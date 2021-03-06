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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ikanow.aleph2.data_model.interfaces.data_import.IEnrichmentModuleContext;
import com.ikanow.aleph2.data_model.interfaces.data_import.IEnrichmentStreamingTopology;
import com.ikanow.aleph2.data_model.objects.data_import.DataBucketBean;
import com.ikanow.aleph2.data_model.utils.ContextUtils;

import backtype.storm.task.OutputCollector;
import backtype.storm.task.TopologyContext;
import backtype.storm.topology.OutputFieldsDeclarer;
import backtype.storm.topology.base.BaseRichBolt;
import backtype.storm.tuple.Tuple;

/** Very simple bolt that outputs the objects it receives to the appropriate Aleph2 storage layers
 * @author Alex
 */
public class OutputBolt extends BaseRichBolt {
	private static final long serialVersionUID = -1801739673297414345L;
	private static final Logger _logger = LogManager.getLogger();
	
	protected final DataBucketBean _bucket; 
	protected final String _context_signature;
	protected final String _user_topology_entry_point; 
	
	protected IEnrichmentModuleContext _context;
	protected IEnrichmentStreamingTopology _user_topology;
	
	protected OutputCollector _collector;
	
	/** User constructor
	 * @param bucket
	 * @param context_signature
	 * @param user_topology_entry_point
	 */
	public OutputBolt(final DataBucketBean bucket, final String context_signature, final String user_topology_entry_point) {
		_bucket = bucket;
		_context_signature = context_signature;
		_user_topology_entry_point = user_topology_entry_point;
	}
	@Override
	public void prepare(final @SuppressWarnings("rawtypes") Map arg0, final TopologyContext arg1, final OutputCollector arg2) {
		try {
			_context = ContextUtils.getEnrichmentContext(_context_signature);
			_user_topology = (IEnrichmentStreamingTopology) Class.forName(_user_topology_entry_point).newInstance();
			_collector = arg2;
		}
		catch (Exception e) { // nothing to be done here?
			_logger.error("Failed to get context", e);
		}
	}
	
	/** Converts from a tuple of a linked hash map
	 * @param t
	 * @return
	 */
	public static LinkedHashMap<String, Object> tupleToLinkedHashMap(final Tuple t) {
		return StreamSupport.stream(t.getFields().spliterator(), false)
							.collect(Collectors.toMap(f -> f, f -> t.getValueByField(f), (m1, m2) -> m1, LinkedHashMap::new));
	}
	
	/* (non-Javadoc)
	 * @see backtype.storm.task.IBolt#execute(backtype.storm.tuple.Tuple)
	 */
	@Override
	public void execute(final Tuple arg0) {		
		_collector.ack(arg0);
		_context.emitMutableObject(0L, (ObjectNode) _user_topology.rebuildObject(arg0, OutputBolt::tupleToLinkedHashMap), Optional.empty(), Optional.empty());
	}

	/* (non-Javadoc)
	 * @see backtype.storm.topology.IComponent#declareOutputFields(backtype.storm.topology.OutputFieldsDeclarer)
	 */
	@Override
	public void declareOutputFields(final OutputFieldsDeclarer arg0) {
		// (nothing to do here)		
	}

}
