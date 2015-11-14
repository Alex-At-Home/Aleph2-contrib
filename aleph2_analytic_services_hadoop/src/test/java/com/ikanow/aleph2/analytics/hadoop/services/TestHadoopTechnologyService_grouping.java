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
package com.ikanow.aleph2.analytics.hadoop.services;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.util.FileUtils;
import org.junit.Before;
import org.junit.Test;

import com.google.inject.Inject;
import com.google.inject.Injector;
import com.ikanow.aleph2.data_model.interfaces.data_services.IManagementDbService;
import com.ikanow.aleph2.data_model.interfaces.data_services.IStorageService;
import com.ikanow.aleph2.data_model.interfaces.shared_services.IServiceContext;
import com.ikanow.aleph2.data_model.objects.data_analytics.AnalyticThreadBean;
import com.ikanow.aleph2.data_model.objects.data_analytics.AnalyticThreadJobBean;
import com.ikanow.aleph2.data_model.objects.data_analytics.AnalyticThreadJobBean.AnalyticThreadJobInputBean;
import com.ikanow.aleph2.data_model.objects.data_import.DataBucketBean;
import com.ikanow.aleph2.data_model.objects.data_import.DataSchemaBean;
import com.ikanow.aleph2.data_model.objects.data_import.EnrichmentControlMetadataBean;
import com.ikanow.aleph2.data_model.objects.shared.GlobalPropertiesBean;
import com.ikanow.aleph2.data_model.utils.BeanTemplateUtils;
import com.ikanow.aleph2.data_model.utils.ErrorUtils;
import com.ikanow.aleph2.data_model.utils.ModuleUtils;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigValueFactory;

import fj.data.Either;

public class TestHadoopTechnologyService_grouping {

    private static final Logger logger = LogManager.getLogger();

	@Inject
	protected GlobalPropertiesBean _globals = null;

	@Inject
	protected IServiceContext _service_context = null;
	
	@Before
	public void setupDependencies() throws Exception {
		try{
			final String temp_dir = System.getProperty("java.io.tmpdir");

			FileUtils.mkdir(new File(temp_dir + "/lib"), true);
			
			// OK we're going to use guice, it was too painful doing this by hand...				
			Config config = ConfigFactory.parseReader(new InputStreamReader(this.getClass().getResourceAsStream("/context_local_test.properties")))
					.withValue("globals.local_root_dir", ConfigValueFactory.fromAnyRef(temp_dir))
					.withValue("globals.local_cached_jar_dir", ConfigValueFactory.fromAnyRef(temp_dir))
					.withValue("globals.distributed_root_dir", ConfigValueFactory.fromAnyRef(temp_dir))
					.withValue("globals.local_yarn_config_dir", ConfigValueFactory.fromAnyRef(temp_dir));

			Injector app_injector = ModuleUtils.createTestInjector(Arrays.asList(), Optional.of(config));	
			app_injector.injectMembers(this);

		}
		catch (Throwable t) {
			System.out.println(ErrorUtils.getLongForm("{0}", t));
			throw t; 
		}
	} // setup dependencies

	protected boolean isWindows() {
		if (File.separator.equals("\\")) { // windows mode!
			logger.info("WINDOWS MODE SKIPPING THIS TEST");
			return true;
		}				
		return false;
	}
	
	
	@Test
	public void test_enrichment_withGrouping() throws IOException, InterruptedException, ExecutionException {
		if (isWindows()) return;
		
		// This is a duplicate of the testBeJobService but going via the full interface
		
		final AnalyticThreadJobInputBean test_analytic_input = 
				BeanTemplateUtils.build(AnalyticThreadJobInputBean.class)
					.with(AnalyticThreadJobInputBean::data_service, "batch")
					.with(AnalyticThreadJobInputBean::resource_name_or_id, "")
				.done().get();
		
		// Set passthrough topology
		final AnalyticThreadJobBean test_analytic = BeanTemplateUtils.build(AnalyticThreadJobBean.class)
				.with(AnalyticThreadJobBean::name, "simplejob")
				.with(AnalyticThreadJobBean::module_name_or_id, "_module_test")
				.with(AnalyticThreadJobBean::inputs, Arrays.asList(test_analytic_input))
			.done().get();
		
		final DataBucketBean test_bucket = BeanTemplateUtils.build(DataBucketBean.class)
												.with(DataBucketBean::full_name, "/test/simple/reduce")
												.with(DataBucketBean::analytic_thread,
														BeanTemplateUtils.build(AnalyticThreadBean.class)
															.with(AnalyticThreadBean::jobs, Arrays.asList(test_analytic))
														.done().get()
														)
												.with(DataBucketBean::batch_enrichment_configs, 
														Arrays.asList(
																BeanTemplateUtils.build(EnrichmentControlMetadataBean.class)																	
																.done().get()
																,
																BeanTemplateUtils.build(EnrichmentControlMetadataBean.class)
																	.with(EnrichmentControlMetadataBean::grouping_fields, Arrays.asList("key"))
																.done().get()
																))
												.with(DataBucketBean::data_schema,
														BeanTemplateUtils.build(DataSchemaBean.class)
															.with(DataSchemaBean::storage_schema,
																BeanTemplateUtils.build(DataSchemaBean.StorageSchemaBean.class)
																	.with(DataSchemaBean.StorageSchemaBean::processed,
																			BeanTemplateUtils.build(DataSchemaBean.StorageSchemaBean.StorageSubSchemaBean.class)
																				.with(DataSchemaBean.StorageSchemaBean.StorageSubSchemaBean::target_write_settings, 
																						BeanTemplateUtils.build(DataSchemaBean.WriteSettings.class)
																							.with(DataSchemaBean.WriteSettings::batch_flush_interval, 5)
																						.done().get()
																						)
																			.done().get()
																			)
																.done().get()
															)
															.with(DataSchemaBean::search_index_schema,
																BeanTemplateUtils.build(DataSchemaBean.SearchIndexSchemaBean.class)
																.done().get()
															)
														.done().get()
														)
											.done().get();
		
		//Need to store this bucket so that multi-input validation works later
		final IManagementDbService mgmt_db = _service_context.getService(IManagementDbService.class, Optional.empty()).get();
		mgmt_db.getDataBucketStore().storeObject(test_bucket, true).join();
		
		final MockHadoopTestingService test_service = new MockHadoopTestingService(_service_context);
		
		test_service.clearAllDataForBucket(test_bucket);
		assertEquals(0L, test_service.getNumRecordsInSearchIndex(test_bucket));
		assertEquals(0L, test_service.numFilesInInputDirectory(test_bucket));
		assertEquals(0L, test_service.numFilesInBucketStorage(test_bucket, IStorageService.STORED_DATA_SUFFIX_RAW, Either.left(Optional.empty())));
		assertEquals(0L, test_service.numFilesInBucketStorage(test_bucket, IStorageService.STORED_DATA_SUFFIX_PROCESSED, Either.left(Optional.empty())));
		
		final InputStream test_file1 = new ByteArrayInputStream("{\"key\":\"group1\"}".getBytes(StandardCharsets.UTF_8));
		final InputStream test_file2 = new ByteArrayInputStream("{\"key\":\"group1\"}".getBytes(StandardCharsets.UTF_8));
		final InputStream test_file3 = new ByteArrayInputStream("{\"key\":\"group2\"}".getBytes(StandardCharsets.UTF_8));
		final InputStream test_file4 = new ByteArrayInputStream("{\"key\":\"group2\"}".getBytes(StandardCharsets.UTF_8));
		test_service.addFileToInputDirectory(test_file1, test_bucket);
		test_service.addFileToInputDirectory(test_file2, test_bucket);
		test_service.addFileToInputDirectory(test_file3, test_bucket);
		test_service.addFileToInputDirectory(test_file4, test_bucket);
		
		test_service.testAnalyticModule(test_bucket, Optional.empty());

		// Wait for job to finish
		for (int ii = 0; ii < 120; ++ii) {
			Thread.sleep(1000L);
			if (test_service.isJobComplete(test_bucket)) {
				break;
			}
		}
		for (int ii = 0; ii < 40; ++ii) {
			Thread.sleep(500L);
			if ((test_service.getNumRecordsInSearchIndex(test_bucket) > 0)
					&&
					(test_service.numFilesInBucketStorage(test_bucket, IStorageService.STORED_DATA_SUFFIX_PROCESSED, Either.left(Optional.empty())) > 0)	
				)
			{
				break;
			}
		}
		
		// Check results:
		
		// (got an ES record)
		assertEquals(4L, test_service.getNumRecordsInSearchIndex(test_bucket));
		// (file removed from input)
		assertEquals(0L, test_service.numFilesInInputDirectory(test_bucket));
		// (file added to output)
		assertEquals(0L, test_service.numFilesInBucketStorage(test_bucket, IStorageService.STORED_DATA_SUFFIX_RAW, Either.left(Optional.empty())));
		assertEquals(2L, test_service.numFilesInBucketStorage(test_bucket, IStorageService.STORED_DATA_SUFFIX_PROCESSED, Either.left(Optional.empty())));
	}
	
	//TODO: now have a map stage immediately after the reduce stage
	//TODO: now have an automated grouping stage
}
