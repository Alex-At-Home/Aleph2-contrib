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
package com.ikanow.aleph2.analytics.storm.assets;

import static org.junit.Assert.*;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.ikanow.aleph2.analytics.storm.services.MockAnalyticsContext;
import com.ikanow.aleph2.analytics.storm.services.StreamingEnrichmentContextService;
import com.ikanow.aleph2.analytics.storm.utils.ErrorUtils;
import com.ikanow.aleph2.data_model.interfaces.data_services.ISearchIndexService;
import com.ikanow.aleph2.data_model.interfaces.shared_services.ICrudService;
import com.ikanow.aleph2.data_model.interfaces.shared_services.IDataWriteService;
import com.ikanow.aleph2.data_model.interfaces.shared_services.IServiceContext;
import com.ikanow.aleph2.data_model.objects.data_import.DataBucketBean;
import com.ikanow.aleph2.data_model.objects.data_import.DataSchemaBean;
import com.ikanow.aleph2.data_model.objects.shared.SharedLibraryBean;
import com.ikanow.aleph2.data_model.utils.BeanTemplateUtils;
import com.ikanow.aleph2.data_model.utils.CrudUtils;
import com.ikanow.aleph2.data_model.utils.ModuleUtils;
import com.ikanow.aleph2.distributed_services.services.ICoreDistributedServices;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import backtype.storm.LocalCluster;
import backtype.storm.generated.StormTopology;

public class TestPassthroughTopology {
	static final Logger _logger = LogManager.getLogger(); 

	LocalCluster _local_cluster;
	
	protected Injector _app_injector;
	
	@Inject IServiceContext _service_context;
	
	@Before
	public void injectModules() throws Exception {
		final Config config = ConfigFactory.parseFile(new File("./example_config_files/context_local_test.properties"));
		
		try {
			_app_injector = ModuleUtils.createTestInjector(Arrays.asList(), Optional.of(config));
		}
		catch (Exception e) {
			try {
				e.printStackTrace();
			}
			catch (Exception ee) {
				System.out.println(ErrorUtils.getLongForm("{0}", e));
			}
		}
		_local_cluster = new LocalCluster();
	}
	
	@org.junit.Ignore
	@Test
	public void test_passthroughTopology() throws InterruptedException, ExecutionException {
		// PHASE 1: GET AN IN-TECHNOLOGY CONTEXT
		// Bucket
		final DataBucketBean test_bucket = BeanTemplateUtils.build(DataBucketBean.class)
				.with(DataBucketBean::_id, "test_passthroughtopology")
				.with(DataBucketBean::modified, new Date())
				.with(DataBucketBean::full_name, "/test/passthrough")
				.with("data_schema", BeanTemplateUtils.build(DataSchemaBean.class)
						.with("search_index_schema", BeanTemplateUtils.build(DataSchemaBean.SearchIndexSchemaBean.class)
								.done().get())
						.done().get())
				.done().get();

		final SharedLibraryBean library = BeanTemplateUtils.build(SharedLibraryBean.class)
				.with(SharedLibraryBean::path_name, "/test/lib")
				.done().get();

		// Context		
		final MockAnalyticsContext test_analytics_context = new MockAnalyticsContext(_service_context);
		final StreamingEnrichmentContextService test_context = new StreamingEnrichmentContextService(test_analytics_context);
		test_analytics_context.setBucket(test_bucket);
		test_analytics_context.setLibraryConfig(library);		
		
		//TODO: sort this out 
//		test_context.setUserTopologyEntryPoint("com.ikanow.aleph2.data_import.stream_enrichment.storm.PassthroughTopology");
//		test_context.getEnrichmentContextSignature(Optional.empty(), Optional.empty());
//		test_context.overrideSavedContext(); // (THIS IS NEEDED WHEN TESTING THE KAFKA SPOUT)
		
		
		//PHASE 2: CREATE TOPOLOGY AND SUBMit		
		final ICoreDistributedServices cds = test_context.getServiceContext().getService(ICoreDistributedServices.class, Optional.empty()).get();
		final StormTopology topology = (StormTopology) new PassthroughTopology()
											.getTopologyAndConfiguration(test_bucket, test_context)
											._1();

		//(Also: register a listener on the output to generate a secondary queue)
		final String end_queue_topic = cds.generateTopicName(test_bucket.full_name(), ICoreDistributedServices.QUEUE_END_NAME);
		cds.createTopic(end_queue_topic, Optional.of(Collections.emptyMap()));

		final backtype.storm.Config config = new backtype.storm.Config();
		config.setDebug(true);
		_local_cluster.submitTopology("test_passthroughTopology", config, topology);
		_logger.info("******** Submitted storm cluster");
		Thread.sleep(5000L);
		
		//PHASE 3: CHECK INDEX
		final ISearchIndexService index_service = test_context.getServiceContext().getService(ISearchIndexService.class, Optional.empty()).get();
		final ICrudService<JsonNode> crud_service = 
				index_service.getDataService()
					.flatMap(s -> s.getWritableDataService(JsonNode.class, test_bucket, Optional.empty(), Optional.empty()))
					.flatMap(IDataWriteService::getCrudService)
					.get();
		crud_service.deleteDatastore().get();
		_logger.info("******** Cleansed existing datastore");
		Thread.sleep(2000L);
		assertEquals(0L, crud_service.countObjects().get().intValue());
		
		//PHASE4 : WRITE TO KAFKA
		
		cds.produce(cds.generateTopicName(test_bucket.full_name(), Optional.empty()), "{\"test\":\"test1\"}");
		_logger.info("******** Written to CDS");
		
		for (int i = 0; i < 60; ++i) {
			Thread.sleep(1000L);
			if (crud_service.countObjects().get() > 0) { 
				_logger.info("******** Waited for ES object to populate: " + i);
				break;
			}
		}		
		assertEquals("Should be 1 object in the repo", 1L, crud_service.countObjects().get().intValue());		
		assertEquals("Object should be test:test1", 1L, crud_service.countObjectsBySpec(CrudUtils.allOf().when("test", "test1")).get().intValue());		
		
		//PHASE5: CHECK IF ALSO WROTE TO OUTPUT QUEUE
		
		Iterator<String> consumer = cds.consumeAs(end_queue_topic, Optional.empty());
		int message_count = 0;
		//read the item off the queue
		while ( consumer.hasNext() ) {
			consumer.next();
        	message_count++;
		}
		assertEquals(1, message_count);
	}
	
}
