/*******************************************************************************
* Copyright 2015, The IKANOW Open Source Project.
* 
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Affero General Public License, version 3,
* as published by the Free Software Foundation.
* 
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Affero General Public License for more details.
* 
* You should have received a copy of the GNU Affero General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/>.
******************************************************************************/
package com.ikanow.aleph2.analytics.hadoop.assets;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import scala.Tuple2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ikanow.aleph2.data_model.interfaces.data_analytics.IBatchRecord;
import com.ikanow.aleph2.data_model.interfaces.data_import.IEnrichmentBatchModule;
import com.ikanow.aleph2.data_model.interfaces.data_import.IEnrichmentModuleContext;
import com.ikanow.aleph2.data_model.objects.data_import.DataBucketBean;

/** Default Batch enrichment module.
 * @author jfreydank
 *
 */
public class BatchEnrichmentModule implements IEnrichmentBatchModule {

	protected IEnrichmentModuleContext context;
	protected DataBucketBean bucket;
	protected boolean final_stage;
	private static final Logger logger = LogManager.getLogger(BatchEnrichmentModule.class);
	private boolean mutable = true;
	protected AtomicLong counter = new AtomicLong();
	
	/** TODO
	 * @param mutable
	 */
	public void setMutable(boolean mutable) {
		this.mutable = mutable;
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_import.IEnrichmentBatchModule#onStageInitialize(com.ikanow.aleph2.data_model.interfaces.data_import.IEnrichmentModuleContext, com.ikanow.aleph2.data_model.objects.data_import.DataBucketBean, boolean)
	 */
	@Override
	public void onStageInitialize(IEnrichmentModuleContext context, DataBucketBean bucket, boolean final_stage) {
		logger.debug("BatchEnrichmentModule.onStageInitialize:"+ context+", DataBucketBean:"+ bucket+", final_stage"+final_stage);
		this.context = context;
		this.bucket = bucket;
		this.final_stage = final_stage;

	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_import.IEnrichmentBatchModule#onObjectBatch(java.util.stream.Stream, java.util.Optional, java.util.Optional)
	 */
	@Override
	public void onObjectBatch(final Stream<Tuple2<Long, IBatchRecord>> batch, Optional<Integer> batch_size, Optional<JsonNode> grouping_key) {
		if (logger.isDebugEnabled()) logger.debug("BatchEnrichmentModule.onObjectBatch:" + batch);
		batch.forEach(t2 -> {
			// if stream is not present data is inside the json object
			if (!t2._2().getContent().isPresent()) {
				if (mutable) {
					ObjectNode mutableObject = context.convertToMutable(t2._2().getJson());

					Long id = probeId(mutableObject);
					if (id == 0) {
						id = counter.addAndGet(1);
					}
					context.emitMutableObject(id, mutableObject, Optional.empty());
				} else {
					JsonNode originalJson = t2._2().getJson();
					Long id = probeId(originalJson);
					if (id == 0) {
						id = counter.addAndGet(1);
					}					
					context.emitImmutableObject(id, originalJson, Optional.empty(), Optional.empty());
				} // else mutable
			} // t3!present, json
			else{
				// here for simplicity reasons we will just dump the stream into a string and emit it.
				Long id = counter.addAndGet(1);				
				context.emitImmutableObject(id, t2._2().getJson(), Optional.empty(), Optional.empty());				
			}
		}); // for 
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_import.IEnrichmentBatchModule#onStageComplete(boolean)
	 */
	@Override
	public void onStageComplete(boolean is_original) {
		logger.debug("BatchEnrichmentModule.onStageComplete()");
	}

	/** TODO
	 * @param mutableObject
	 * @return
	 */
	protected static Long probeId(JsonNode mutableObject){
		// probe for id field
		JsonNode id1 = mutableObject.get("id");
		if(id1==null){
			id1 = mutableObject.get("ID");
		}
		if(id1==null){
			id1 = mutableObject.get("Id");
		}
		if(id1==null){
			id1 = mutableObject.get("_id");
		}
		if(id1==null){
			id1 = mutableObject.get("_ID");
		}
		Long id = 0L;
		// convert to long
		if(id1!=null){
			id = id1.asLong();							
		}
		return id;

	}
}
