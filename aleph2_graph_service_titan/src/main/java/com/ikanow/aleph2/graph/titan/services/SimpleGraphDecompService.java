/*******************************************************************************
 * Copyright 2016, The IKANOW Open Source Project.
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

package com.ikanow.aleph2.graph.titan.services;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;



import scala.Tuple2;



import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ikanow.aleph2.data_model.interfaces.data_analytics.IBatchRecord;
import com.ikanow.aleph2.data_model.interfaces.data_import.IEnrichmentBatchModule;
import com.ikanow.aleph2.data_model.interfaces.data_import.IEnrichmentModuleContext;
import com.ikanow.aleph2.data_model.objects.data_import.DataBucketBean;
import com.ikanow.aleph2.data_model.objects.data_import.EnrichmentControlMetadataBean;
import com.ikanow.aleph2.data_model.objects.data_import.GraphAnnotationBean;
import com.ikanow.aleph2.data_model.utils.BeanTemplateUtils;
import com.ikanow.aleph2.data_model.utils.Optionals;
import com.ikanow.aleph2.data_model.utils.SetOnce;
import com.ikanow.aleph2.graph.titan.data_model.SimpleDecompConfigBean;

/** Very simple code to build a set of vertices and edges from a data object
 * @author Alex
 */
public class SimpleGraphDecompService implements IEnrichmentBatchModule {
	final protected static ObjectMapper _mapper = BeanTemplateUtils.configureMapper(Optional.empty());
	
	final protected SetOnce<IEnrichmentModuleContext> _context = new SetOnce<>();
	final protected SetOnce<SimpleDecompConfigBean> _config = new SetOnce<>();
	
	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_import.IEnrichmentBatchModule#onStageInitialize(com.ikanow.aleph2.data_model.interfaces.data_import.IEnrichmentModuleContext, com.ikanow.aleph2.data_model.objects.data_import.DataBucketBean, com.ikanow.aleph2.data_model.objects.data_import.EnrichmentControlMetadataBean, scala.Tuple2, java.util.Optional)
	 */
	@Override
	public void onStageInitialize(IEnrichmentModuleContext context,
			DataBucketBean bucket, EnrichmentControlMetadataBean control,
			Tuple2<ProcessingStage, ProcessingStage> previous_next,
			Optional<List<String>> next_grouping_fields) {
		_context.set(context);
		_config.set(BeanTemplateUtils.from(control.config(), SimpleDecompConfigBean.class).get());
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_import.IEnrichmentBatchModule#onObjectBatch(java.util.stream.Stream, java.util.Optional, java.util.Optional)
	 */
	@Override
	public void onObjectBatch(Stream<Tuple2<Long, IBatchRecord>> batch,
			Optional<Integer> batch_size, Optional<JsonNode> grouping_key) {
		batch.forEach(t2 -> {
			Optionals.ofNullable(_config.get().elements()).forEach(el -> {
				final ObjectNode d_o = (ObjectNode) t2._2().getJson();
				final JsonNode from = d_o.get(el.from_field());
				final JsonNode to = d_o.get(el.to_field());
				if ((null != from) && (null != to)) {
					final ObjectNode mutable_from_key = _mapper.createObjectNode();
					mutable_from_key.put(GraphAnnotationBean.name, from);
					mutable_from_key.put(GraphAnnotationBean.type, el.from_type());
					final ObjectNode mutable_to_key = _mapper.createObjectNode();
					mutable_to_key.put(GraphAnnotationBean.name, to);
					mutable_to_key.put(GraphAnnotationBean.type, el.to_type());
					
					final ObjectNode mutable_from_vertex = _mapper.createObjectNode();
					mutable_from_vertex.put(GraphAnnotationBean.type, GraphAnnotationBean.ElementType.vertex.toString());
					mutable_from_vertex.put(GraphAnnotationBean.id, mutable_from_key);
					mutable_from_vertex.put(GraphAnnotationBean.label, from.asText());
					final ObjectNode mutable_to_vertex = _mapper.createObjectNode();
					mutable_to_vertex.put(GraphAnnotationBean.type, GraphAnnotationBean.ElementType.vertex.toString());
					mutable_to_vertex.put(GraphAnnotationBean.id, mutable_to_key);
					mutable_to_vertex.put(GraphAnnotationBean.label, to.asText());
					
					final ObjectNode mutable_edge = _mapper.createObjectNode();
					mutable_edge.put(GraphAnnotationBean.type, GraphAnnotationBean.ElementType.edge.toString());
					mutable_edge.put(GraphAnnotationBean.label, el.edge_name());
					mutable_edge.put(GraphAnnotationBean.outV, mutable_from_key);
					mutable_edge.put(GraphAnnotationBean.inV, mutable_to_key);
					
					_context.get().emitImmutableObject(_context.get().getNextUnusedId(), mutable_from_vertex, Optional.empty(), Optional.empty(), Optional.empty());
					_context.get().emitImmutableObject(_context.get().getNextUnusedId(), mutable_to_vertex, Optional.empty(), Optional.empty(), Optional.empty());
					_context.get().emitImmutableObject(_context.get().getNextUnusedId(), mutable_edge, Optional.empty(), Optional.empty(), Optional.empty());
				}
			});
		});
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_import.IEnrichmentBatchModule#onStageComplete(boolean)
	 */
	@Override
	public void onStageComplete(boolean is_original) {
		// (Nothing to do)		
	}

}
