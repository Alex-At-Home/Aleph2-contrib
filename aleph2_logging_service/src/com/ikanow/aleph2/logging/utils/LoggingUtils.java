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
package com.ikanow.aleph2.logging.utils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import org.apache.logging.log4j.Level;

import scala.Tuple2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableMap;
import com.ikanow.aleph2.data_model.interfaces.data_services.ISearchIndexService;
import com.ikanow.aleph2.data_model.interfaces.data_services.IStorageService;
import com.ikanow.aleph2.data_model.interfaces.shared_services.ICrudService;
import com.ikanow.aleph2.data_model.interfaces.shared_services.IDataWriteService;
import com.ikanow.aleph2.data_model.objects.data_import.DataBucketBean;
import com.ikanow.aleph2.data_model.objects.data_import.ManagementSchemaBean;
import com.ikanow.aleph2.data_model.objects.data_import.DataSchemaBean.LoggingSchemaBean;
import com.ikanow.aleph2.data_model.objects.shared.BasicMessageBean;
import com.ikanow.aleph2.data_model.utils.BeanTemplateUtils;
import com.ikanow.aleph2.data_model.utils.BucketUtils;
import com.ikanow.aleph2.data_model.utils.ErrorUtils;

/**
 * @author Burch
 *
 */
public class LoggingUtils {
	private static final String EXTERNAL_PREFIX = "/external";
	private static final String DEFAULT_LEVEL_KEY = "__DEFAULT__";
	
	/**
	 * Builds a JsonNode log message object, contains fields for date, message, generated_by, bucket, subsystem, and severity
	 * 
	 * @param level
	 * @param bucket
	 * @param message
	 * @param isSystemMessage
	 * @return
	 */
	public static JsonNode createLogObject(final Level level, final DataBucketBean bucket, final BasicMessageBean message, final boolean isSystemMessage, final String date_field) {
		final ObjectMapper _mapper = new ObjectMapper();
		return Optional.ofNullable(message.details()).map(d -> _mapper.convertValue(d, ObjectNode.class)).orElseGet(() -> _mapper.createObjectNode())
				.put(date_field, message.date().getTime()) //TODO can I actually pass in a date object/need to?
				.put("message", ErrorUtils.show(message))
				.put("generated_by", isSystemMessage ? "system" : "user")
				.put("bucket", bucket.full_name())
				.put("subsystem", message.source())
				.put("severity", level.toString());			
	}
	
	/**
	 * Builds a minimal bucket pointing the full path to the external bucket/subsystem
	 * 
	 * @param subsystem
	 * @return
	 */
	public static DataBucketBean getExternalBucket(final String subsystem, final Level default_log_level) {
		return BeanTemplateUtils.build(DataBucketBean.class)
				.with(DataBucketBean::full_name, EXTERNAL_PREFIX + "/" + subsystem + "/")	
				.with(DataBucketBean::management_schema, BeanTemplateUtils.build(ManagementSchemaBean.class)
							.with(ManagementSchemaBean::logging_schema, BeanTemplateUtils.build(LoggingSchemaBean.class)
										.with(LoggingSchemaBean::log_level, default_log_level)
									.done().get())
						.done().get())
				.done().get();
	}
	

	
	/**
	 * Returns back a IDataWriteService pointed at a logging output location for the given bucket
	 * @param bucket
	 * @return
	 */
	public static IDataWriteService<JsonNode> getLoggingServiceForBucket(final ISearchIndexService search_index_service, final IStorageService storage_service, final DataBucketBean bucket) {
		//change the bucket.full_name to point to a logging location
		final DataBucketBean bucket_logging = BucketUtils.convertDataBucketBeanToLogging(bucket);
		
		//return crudservice pointing to this path
		//TODO in the future need to switch to wrapper that gets the actual services we need (currently everything is ES so this if fine)
		return search_index_service.getDataService().get().getWritableDataService(JsonNode.class, bucket_logging, Optional.empty(), Optional.empty()).get();
	}

	/**
	 * Creates a map of subsystem -> logging level for quick lookups.  Grabs the overrides from
	 * bucket.management_schema().logging_schema().log_level_overrides()
	 * 
	 * Returns an empty list if none exist there
	 * 
	 * @param bucket
	 * @return
	 */
	public static ImmutableMap<String, Level> getBucketLoggingThresholds(final DataBucketBean bucket) {
		//if overrides are set, create a map with them and the default
		if (bucket.management_schema() != null &&
				bucket.management_schema().logging_schema() != null ) {
			return new ImmutableMap.Builder<String, Level>()
		 	.put(DEFAULT_LEVEL_KEY, bucket.management_schema().logging_schema().log_level())
		 	.putAll(Optional.ofNullable(bucket.management_schema().logging_schema().log_level_overrides()).orElse(new HashMap<String, Level>()))
		 .build();
		} else {
			//otherwise just return an empty map
			return new ImmutableMap.Builder<String, Level>().build();
		}
	}
	
	/**
	 * Gets the minimal log level for the given subsystem by checking:
	 * 1. if logging_overrides has that subsystem as a key
	 * 2. if not, checks if there is a default level set
	 * 3. if not, uses default_log_level
	 * Then returns if the passed in log level is above the minimal log level or not.
	 * 
	 * @param level
	 * @param logging_overrides
	 * @param subsystem
	 * @param default_log_level
	 * @return
	 */
	public static boolean meetsLogLevelThreshold(final Level level, final ImmutableMap<String, Level> logging_overrides, final String subsystem, final Level default_log_level) {
		final Level curr_min_level = 
				Optional.ofNullable(logging_overrides.get(subsystem))
				.orElse(Optional.ofNullable(logging_overrides.get(DEFAULT_LEVEL_KEY))
				.orElse(default_log_level));	
		return curr_min_level.isLessSpecificThan(level);
	}
	
	public static class EmptyWritable<T> implements IDataWriteService<T> {

		/* (non-Javadoc)
		 * @see com.ikanow.aleph2.data_model.interfaces.shared_services.IDataWriteService#storeObject(java.lang.Object)
		 */
		@Override
		public CompletableFuture<Supplier<Object>> storeObject(T new_object) {
			return CompletableFuture.completedFuture(() -> true);
		}

		/* (non-Javadoc)
		 * @see com.ikanow.aleph2.data_model.interfaces.shared_services.IDataWriteService#storeObject(java.lang.Object, boolean)
		 */
		@Override
		public CompletableFuture<Supplier<Object>> storeObject(T new_object,
				boolean replace_if_present) {
			return CompletableFuture.completedFuture(() -> true);
		}

		/* (non-Javadoc)
		 * @see com.ikanow.aleph2.data_model.interfaces.shared_services.IDataWriteService#storeObjects(java.util.List)
		 */
		@Override
		public CompletableFuture<Tuple2<Supplier<List<Object>>, Supplier<Long>>> storeObjects(
				List<T> new_objects) {
			return CompletableFuture.completedFuture(new Tuple2<Supplier<List<Object>>, Supplier<Long>>(() -> new ArrayList<Object>(), () -> 0L));
		}

		/* (non-Javadoc)
		 * @see com.ikanow.aleph2.data_model.interfaces.shared_services.IDataWriteService#storeObjects(java.util.List, boolean)
		 */
		@Override
		public CompletableFuture<Tuple2<Supplier<List<Object>>, Supplier<Long>>> storeObjects(
				List<T> new_objects, boolean replace_if_present) {
			return CompletableFuture.completedFuture(new Tuple2<Supplier<List<Object>>, Supplier<Long>>(() -> new ArrayList<Object>(), () -> 0L));
		}

		/* (non-Javadoc)
		 * @see com.ikanow.aleph2.data_model.interfaces.shared_services.IDataWriteService#countObjects()
		 */
		@Override
		public CompletableFuture<Long> countObjects() {
			return CompletableFuture.completedFuture(0L);
		}

		/* (non-Javadoc)
		 * @see com.ikanow.aleph2.data_model.interfaces.shared_services.IDataWriteService#deleteDatastore()
		 */
		@Override
		public CompletableFuture<Boolean> deleteDatastore() {
			return CompletableFuture.completedFuture(true);
		}

		/* (non-Javadoc)
		 * @see com.ikanow.aleph2.data_model.interfaces.shared_services.IDataWriteService#getCrudService()
		 */
		@Override
		public Optional<ICrudService<T>> getCrudService() {
			return Optional.empty();
		}

		/* (non-Javadoc)
		 * @see com.ikanow.aleph2.data_model.interfaces.shared_services.IDataWriteService#getRawService()
		 */
		@Override
		public IDataWriteService<JsonNode> getRawService() {
			return null;
		}

		/* (non-Javadoc)
		 * @see com.ikanow.aleph2.data_model.interfaces.shared_services.IDataWriteService#getUnderlyingPlatformDriver(java.lang.Class, java.util.Optional)
		 */
		@SuppressWarnings("hiding")
		@Override
		public <T> Optional<T> getUnderlyingPlatformDriver(
				Class<T> driver_class, Optional<String> driver_options) {
			return Optional.empty();
		}
	}
}
